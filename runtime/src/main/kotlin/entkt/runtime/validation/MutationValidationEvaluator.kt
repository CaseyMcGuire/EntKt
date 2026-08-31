@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.query.EntktInternal

/** Evaluates one mutation lifecycle's bound validation policy. */
@EntktInternal
fun interface MutationValidationEvaluator<Subject> {
    fun evaluate(subjects: List<Subject>): ValidationEvaluation<Subject>
}

/** Build a final evaluator from bound primary rules and optional additional rules. */
@EntktInternal
fun <RuleClient, Subject, Item> mutationValidationEvaluatorForInternalUse(
    lifecycle: String,
    rules: List<BatchValidationRule<RuleClient, Item>>,
    ruleClientProvider: () -> RuleClient,
    freshItem: (Subject) -> Item,
    additional: ValidationDecisionEvaluator<Subject>? = null,
): MutationValidationEvaluator<Subject> {
    val decisions = validationDecisionEvaluatorForInternalUse(
        lifecycle = lifecycle,
        rules = rules,
        ruleClientProvider = ruleClientProvider,
        freshItem = freshItem,
    ).plusForInternalUse(lifecycle, additional)
    return MutationValidationEvaluator { subjects ->
        val subjectSnapshot = subjects.toList()
        correlateValidationEvaluationForInternalUse(
            lifecycle = lifecycle,
            subjects = subjectSnapshot,
            violationsBySubject = decisions.evaluate(subjectSnapshot),
        )
    }
}
