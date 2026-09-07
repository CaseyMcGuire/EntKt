@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.result.EntBatchRuleContractException

/** Intermediate violations retained only while composing a final validation evaluator. */
@EntktInternal
fun interface ValidationDecisionEvaluator<RuleClient, Subject> {
    fun evaluate(
        context: ValidationRuleContext<RuleClient>,
        subjects: List<Subject>,
    ): List<List<ValidationDecision.Invalid>>
}

/** Bind one typed validation-rule list and subject adapter, with a per-call context. */
@EntktInternal
fun <RuleClient, Subject, Item> validationDecisionEvaluatorForInternalUse(
    lifecycle: String,
    rules: List<BatchValidationRule<RuleClient, Item>>,
    freshItem: (Subject) -> Item,
): ValidationDecisionEvaluator<RuleClient, Subject> {
    val ruleSnapshot = rules.toList()
    return ValidationDecisionEvaluator { context, subjects ->
        if (subjects.isEmpty()) {
            emptyList()
        } else {
            evaluateBatchValidationRulesForInternalUse(
                lifecycle = lifecycle,
                items = subjects,
                rules = ruleSnapshot,
                context = context,
                freshItem = freshItem,
            )
        }
    }
}

/** Append every additional violation to its correlated primary subject. */
internal fun <RuleClient, Subject> ValidationDecisionEvaluator<RuleClient, Subject>.plusForInternalUse(
    lifecycle: String,
    additional: ValidationDecisionEvaluator<RuleClient, Subject>?,
): ValidationDecisionEvaluator<RuleClient, Subject> {
    if (additional == null) return this
    return ValidationDecisionEvaluator { context, subjects ->
        val primary = evaluate(context, subjects)
        val secondary = additional.evaluate(context, subjects)
        if (primary.size != subjects.size) {
            throw EntBatchRuleContractException(lifecycle, subjects.size, primary.size)
        }
        if (secondary.size != subjects.size) {
            throw EntBatchRuleContractException(lifecycle, subjects.size, secondary.size)
        }
        primary.indices.map { index -> primary[index] + secondary[index] }
    }
}
