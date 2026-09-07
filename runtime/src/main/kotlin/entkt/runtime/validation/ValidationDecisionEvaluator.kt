@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.query.EntktInternal

/**
 * A typed rule list and per-rule input converter, without final subject correlation.
 * Used by [MutationValidationEvaluator] for its primary policy and optional additional CREATE rules.
 */
@EntktInternal
class ValidationDecisionEvaluator<RuleClient, Subject, Item>(
    rules: List<BatchValidationRule<RuleClient, Item>>,
    private val freshItem: (Subject) -> Item,
) {
    private val rules = rules.toList()

    internal fun evaluate(
        context: ValidationRuleContext<RuleClient>,
        subjects: List<Subject>,
        lifecycle: String,
    ): List<List<ValidationDecision.Invalid>> = evaluateBatchValidationRulesForInternalUse(
        lifecycle = lifecycle,
        items = subjects,
        rules = rules,
        context = context,
        freshItem = freshItem,
    )
}
