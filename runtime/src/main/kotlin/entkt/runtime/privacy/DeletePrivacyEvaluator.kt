@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.rule.EntRuleClient

/** Evaluates DELETE privacy and optional CREATE derivation for unresolved subjects. */
@EntktInternal
class DeletePrivacyEvaluator<Client : EntRuleClient, Candidate, RuleInput>(
    entityName: String,
    private val rules: List<BatchPrivacyRule<Client, RuleInput>>,
    private val createRules: List<BatchPrivacyRule<Client, Candidate>>,
    private val derivesFromCreate: Boolean,
) {
    private val batchEvaluator = PrivacyBatchEvaluator(
        entityName = entityName,
        operation = PrivacyOperation.DELETE,
    )

    fun <Subject> evaluate(
        context: PrivacyRuleContext<Client>,
        subjects: List<Subject>,
        toRuleInput: (Subject) -> RuleInput,
        toCandidate: (Subject) -> Candidate,
    ): PrivacyEvaluation<Subject> {
        if (derivesFromCreate) {
            return batchEvaluator.evaluate(
                context = context,
                subjects = subjects,
                rules = rules,
                toRuleInput = toRuleInput,
                continuationRules = createRules,
                toContinuationInput = toCandidate,
            )
        }
        return batchEvaluator.evaluate(
            context = context,
            subjects = subjects,
            rules = rules,
            toRuleInput = toRuleInput,
        )
    }
}
