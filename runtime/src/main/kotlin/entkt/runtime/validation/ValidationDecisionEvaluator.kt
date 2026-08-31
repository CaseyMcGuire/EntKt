@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.result.EntBatchRuleContractException

/** Intermediate violations retained only while composing a final validation evaluator. */
@EntktInternal
fun interface ValidationDecisionEvaluator<Subject> {
    fun evaluate(subjects: List<Subject>): List<List<ValidationDecision.Invalid>>
}

/** Bind one typed validation-rule list, read-client provider, and subject adapter. */
@EntktInternal
fun <RuleClient, Subject, Item> validationDecisionEvaluatorForInternalUse(
    lifecycle: String,
    rules: List<BatchValidationRule<RuleClient, Item>>,
    ruleClientProvider: () -> RuleClient,
    freshItem: (Subject) -> Item,
): ValidationDecisionEvaluator<Subject> {
    val ruleSnapshot = rules.toList()
    return ValidationDecisionEvaluator { subjects ->
        if (subjects.isEmpty()) {
            emptyList()
        } else {
            evaluateBatchValidationRulesForInternalUse(
                lifecycle = lifecycle,
                items = subjects,
                rules = ruleSnapshot,
                context = ValidationRuleContext(ruleClientProvider()),
                freshItem = freshItem,
            )
        }
    }
}

/** Append every additional violation to its correlated primary subject. */
internal fun <Subject> ValidationDecisionEvaluator<Subject>.plusForInternalUse(
    lifecycle: String,
    additional: ValidationDecisionEvaluator<Subject>?,
): ValidationDecisionEvaluator<Subject> {
    if (additional == null) return this
    return ValidationDecisionEvaluator { subjects ->
        val primary = evaluate(subjects)
        val secondary = additional.evaluate(subjects)
        if (primary.size != subjects.size) {
            throw EntBatchRuleContractException(lifecycle, subjects.size, primary.size)
        }
        if (secondary.size != subjects.size) {
            throw EntBatchRuleContractException(lifecycle, subjects.size, secondary.size)
        }
        primary.indices.map { index -> primary[index] + secondary[index] }
    }
}
