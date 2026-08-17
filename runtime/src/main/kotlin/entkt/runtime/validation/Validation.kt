package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.result.EntBatchRuleContractException
import java.util.Collections

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
 * A validation rule that can evaluate an ordered batch of operation contexts.
 *
 * Implementations must return exactly one [ValidationDecision] for each
 * supplied context, in the same order. Generated lifecycle evaluators never
 * invoke a rule with an empty list and validate the returned cardinality.
 */
interface BatchValidationRule<in C> {
    fun validateBatch(contexts: List<C>): List<ValidationDecision>
}

/**
 * A scalar validation rule that evaluates one operation context.
 *
 * Scalar rules are also [BatchValidationRule]s: the default adapter evaluates
 * contexts serially in encounter order. All reached rules run —
 * [ValidationDecision.Invalid] results are collected, not short-circuited.
 */
fun interface ValidationRule<in C> : BatchValidationRule<C> {
    fun validate(ctx: C): ValidationDecision

    override fun validateBatch(contexts: List<C>): List<ValidationDecision> =
        contexts.map { validate(it) }
}

/** Construct an explicitly batch-aware validation rule. */
fun <C> batchValidationRule(
    block: (List<C>) -> List<ValidationDecision>,
): BatchValidationRule<C> = object : BatchValidationRule<C> {
    override fun validateBatch(contexts: List<C>): List<ValidationDecision> = block(contexts)
}

/**
 * Evaluate every ordered validation rule for every [item], retaining invalid
 * decisions by original item index and rule registration order.
 */
@EntktInternal
fun <I, C> evaluateBatchValidationRulesForInternalUse(
    lifecycle: String,
    items: List<I>,
    rules: List<BatchValidationRule<C>>,
    freshContext: (I) -> C,
): List<List<ValidationDecision.Invalid>> {
    if (items.isEmpty()) return emptyList()

    val itemSnapshot = items.toList()
    val ruleSnapshot = rules.toList()
    val violations = List(itemSnapshot.size) { mutableListOf<ValidationDecision.Invalid>() }
    for (rule in ruleSnapshot) {
        val contexts = immutableList(itemSnapshot.map(freshContext))
        val returnedDecisions: List<ValidationDecision>? = rule.validateBatch(contexts)
        val ruleDecisions: List<*> = returnedDecisions?.toList()
            ?: throw EntBatchRuleContractException(lifecycle, contexts.size, actualSize = null)
        if (ruleDecisions.size != contexts.size) {
            throw EntBatchRuleContractException(lifecycle, contexts.size, ruleDecisions.size)
        }

        ruleDecisions.forEachIndexed { index, decision ->
            when (decision) {
                ValidationDecision.Valid -> Unit
                is ValidationDecision.Invalid -> violations[index] += decision
                else -> throw EntBatchRuleContractException(
                    lifecycle = lifecycle,
                    expectedSize = contexts.size,
                    actualSize = ruleDecisions.size,
                    invalidDecisionIndex = index,
                )
            }
        }
    }

    return violations.map { it.toList() }
}

private fun <T> immutableList(elements: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(elements))

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
