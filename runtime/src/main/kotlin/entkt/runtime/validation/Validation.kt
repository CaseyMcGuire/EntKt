package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions
import entkt.runtime.rule.decisionsForInternalUse
import entkt.runtime.rule.ruleBatchForInternalUse
import entkt.runtime.result.EntBatchRuleContractException

/**
 * State shared by every item and rule in one validation evaluation phase.
 *
 * Generated lifecycle item types contain only per-item state. The
 * validation-capable [client] lives here so scalar and batch rules receive the
 * same item shape. The framework passes the exact same instance to every rule
 * in that phase.
 */
class ValidationRuleContext<out Client>(
    val client: Client,
)

/**
 * The result of evaluating a single validation rule.
 *
 * - [Valid] — the rule passes.
 * - [Invalid] — the rule fails with a message and optional field/code.
 *
 * Unlike privacy rules, there is no `Continue` — every rule runs
 * regardless of prior results. Generated mutation pipelines collect all
 * [Invalid] results into an `EntValidationException` carried by
 * `MutationResult.Failed`.
 */
sealed interface ValidationDecision {
    data object Valid : ValidationDecision
    data class Invalid(
        val message: String,
        val field: String? = null,
        val code: String? = null,
    ) : ValidationDecision
}

/**
 * A validation rule that can evaluate an ordered batch of operation items.
 *
 * Implementations return decisions through [RuleBatch.decideEach] or
 * [RuleBatch.decideEachIndexed]. Those operations preserve correlation with
 * the supplied items; callers cannot construct or reorder the result directly.
 * Generated lifecycle evaluators never invoke a rule with an empty batch.
 */
interface BatchValidationRule<in Client, in Item> {
    @JvmSuppressWildcards
    fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision>
}

/**
 * A scalar validation rule that evaluates one operation item.
 *
 * Scalar rules are also [BatchValidationRule]s: the default adapter evaluates
 * items serially in encounter order. All reached rules run —
 * [ValidationDecision.Invalid] results are collected, not short-circuited.
 */
fun interface ValidationRule<in Client, in Item> : BatchValidationRule<Client, Item> {
    @JvmSuppressWildcards
    fun validate(
        context: ValidationRuleContext<Client>,
        item: Item,
    ): ValidationDecision

    @JvmSuppressWildcards
    override fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision> =
        batch.decideEach { validate(context, it) }
}

/** Construct an explicitly batch-aware validation rule. */
fun <Client, Item> batchValidationRule(
    block: (
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ) -> RuleDecisions<ValidationDecision>,
): BatchValidationRule<Client, Item> = object : BatchValidationRule<Client, Item> {
    override fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision> = block(context, batch)
}

/**
 * Evaluate every ordered validation rule for every [item], retaining invalid
 * decisions by original item index and rule registration order.
 */
@EntktInternal
fun <I, Client, Item> evaluateBatchValidationRulesForInternalUse(
    lifecycle: String,
    items: List<I>,
    rules: List<BatchValidationRule<Client, Item>>,
    context: ValidationRuleContext<Client>,
    freshItem: (I) -> Item,
): List<List<ValidationDecision.Invalid>> {
    if (items.isEmpty()) return emptyList()

    val itemSnapshot = items.toList()
    val ruleSnapshot = rules.toList()
    val violations = List(itemSnapshot.size) { mutableListOf<ValidationDecision.Invalid>() }
    for (rule in ruleSnapshot) {
        val ruleItems = itemSnapshot.map(freshItem)
        val batch = ruleBatchForInternalUse(ruleItems)
        val returnedDecisions: RuleDecisions<ValidationDecision>? = rule.validateBatch(context, batch)
        val ruleDecisions: List<*> = returnedDecisions
            ?.let { batch.decisionsForInternalUse(lifecycle, it) }
            ?: throw EntBatchRuleContractException(lifecycle, ruleItems.size, actualSize = null)
        if (ruleDecisions.size != ruleItems.size) {
            throw EntBatchRuleContractException(lifecycle, ruleItems.size, ruleDecisions.size)
        }

        ruleDecisions.forEachIndexed { index, decision ->
            when (decision) {
                ValidationDecision.Valid -> Unit
                is ValidationDecision.Invalid -> violations[index] += decision
                else -> throw EntBatchRuleContractException(
                    lifecycle = lifecycle,
                    expectedSize = ruleItems.size,
                    actualSize = ruleDecisions.size,
                    invalidDecisionIndex = index,
                )
            }
        }
    }

    return violations.map { it.toList() }
}

/**
 * Legacy standalone representation of one or more invalid validation
 * decisions. Generated mutation terminals do not throw this type; they return
 * `MutationResult.Failed(EntValidationException)` with the canonical
 * validation payload.
 */
class ValidationException(
    val entity: String,
    val violations: List<ValidationDecision.Invalid>,
) : RuntimeException(
    "Validation failed on $entity: ${violations.joinToString("; ") { it.message }}",
)
