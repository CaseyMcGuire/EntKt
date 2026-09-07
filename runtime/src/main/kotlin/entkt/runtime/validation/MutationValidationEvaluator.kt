@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.result.EntBatchRuleContractException

/**
 * Evaluates one mutation lifecycle's bound validation policy, including additional CREATE rules.
 * The caller supplies the rule context per evaluation; no client or viewer is retained.
 */
@EntktInternal
class MutationValidationEvaluator<RuleClient, Subject>(
    private val lifecycle: String,
    private val primary: ValidationDecisionEvaluator<RuleClient, Subject, *>,
    private val additional: ValidationDecisionEvaluator<RuleClient, Subject, *>? = null,
) {
    /** Bind rules whose inputs already match the evaluated subjects, such as CREATE candidates. */
    constructor(
        lifecycle: String,
        rules: List<BatchValidationRule<RuleClient, Subject>>,
        additional: ValidationDecisionEvaluator<RuleClient, Subject, *>? = null,
    ) : this(
        lifecycle = lifecycle,
        primary = ValidationDecisionEvaluator(rules, freshItem = { subject: Subject -> subject }),
        additional = additional,
    )

    fun evaluate(
        context: ValidationRuleContext<RuleClient>,
        subjects: List<Subject>,
    ): ValidationEvaluation<Subject> {
        val subjectSnapshot = subjects.toList()
        return correlateValidationEvaluationForInternalUse(
            lifecycle = lifecycle,
            subjects = subjectSnapshot,
            violationsBySubject = evaluateDecisions(context, subjectSnapshot),
        )
    }

    /** Append every additional violation to its correlated primary subject. */
    private fun evaluateDecisions(
        context: ValidationRuleContext<RuleClient>,
        subjects: List<Subject>,
    ): List<List<ValidationDecision.Invalid>> {
        val primaryViolations = primary.evaluate(context, subjects, lifecycle)
        if (additional == null) return primaryViolations

        val additionalViolations = additional.evaluate(context, subjects, lifecycle)
        if (primaryViolations.size != subjects.size) {
            throw EntBatchRuleContractException(lifecycle, subjects.size, primaryViolations.size)
        }
        if (additionalViolations.size != subjects.size) {
            throw EntBatchRuleContractException(lifecycle, subjects.size, additionalViolations.size)
        }
        return primaryViolations.indices.map { index -> primaryViolations[index] + additionalViolations[index] }
    }
}
