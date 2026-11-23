package entkt.runtime.validation

import entkt.runtime.rule.EntRuleClient
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions
import entkt.runtime.privacy.ViewerContext

/**
 * State shared by every item and rule in one validation evaluation phase.
 *
 * Generated lifecycle item types contain only per-item state. The
 * validation-capable [client] lives here so scalar and batch rules receive the
 * same item shape. The framework passes the exact same instance to every rule
 * in that phase.
 */
class ValidationRuleContext<out Client : EntRuleClient>(
    val client: Client,
) {
    /** Explicit privileged context for reads performed while validating invariants. */
    val readViewerContext: ViewerContext.PrivacyBypass
        get() = VALIDATION_READ_VIEWER_CONTEXT

    private companion object {
        val VALIDATION_READ_VIEWER_CONTEXT: ViewerContext.PrivacyBypass =
            ViewerContext.privacyBypass_DANGEROUS("validation read")
    }
}

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
interface BatchValidationRule<in Client : EntRuleClient, in Item> {
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
fun interface ValidationRule<in Client : EntRuleClient, in Item> : BatchValidationRule<Client, Item> {
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
fun <Client : EntRuleClient, Item> batchValidationRule(
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
