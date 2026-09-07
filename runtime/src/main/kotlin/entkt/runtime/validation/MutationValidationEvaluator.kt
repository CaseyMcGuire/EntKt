@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.query.EntktInternal

/** Evaluates one mutation lifecycle's bound validation policy. */
@EntktInternal
fun interface MutationValidationEvaluator<RuleClient, Subject> {
    fun evaluate(
        context: ValidationRuleContext<RuleClient>,
        subjects: List<Subject>,
    ): ValidationEvaluation<Subject>
}

/** Build a final evaluator from bound primary rules and optional additional rules. */
@EntktInternal
fun <RuleClient, Subject, Item> mutationValidationEvaluatorForInternalUse(
    lifecycle: String,
    rules: List<BatchValidationRule<RuleClient, Item>>,
    freshItem: (Subject) -> Item,
    additional: ValidationDecisionEvaluator<RuleClient, Subject>? = null,
): MutationValidationEvaluator<RuleClient, Subject> {
    val decisions = validationDecisionEvaluatorForInternalUse(
        lifecycle = lifecycle,
        rules = rules,
        freshItem = freshItem,
    ).plusForInternalUse(lifecycle, additional)
    return MutationValidationEvaluator { context, subjects ->
        val subjectSnapshot = subjects.toList()
        correlateValidationEvaluationForInternalUse(
            lifecycle = lifecycle,
            subjects = subjectSnapshot,
            violationsBySubject = decisions.evaluate(context, subjectSnapshot),
        )
    }
}
