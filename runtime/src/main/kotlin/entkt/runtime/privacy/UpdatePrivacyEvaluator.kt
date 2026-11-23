@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.rule.EntRuleClient

/** Evaluates UPDATE privacy and optional CREATE derivation for unresolved subjects. */
@EntktInternal
class UpdatePrivacyEvaluator<Viewer : Any, Client : EntRuleClient, Candidate, RuleInput>(
    entityName: String,
    private val rules: List<BatchPrivacyRule<Viewer, Client, RuleInput>>,
    private val createRules: List<BatchPrivacyRule<Viewer, Client, Candidate>>,
    private val derivesFromCreate: Boolean,
) {
    private val batchEvaluator = PrivacyBatchEvaluator(
        entityName = entityName,
        operation = PrivacyOperation.UPDATE,
    )

    fun <Subject> evaluate(
        viewerContext: ViewerContext<Viewer>,
        client: Client,
        subjects: List<Subject>,
        toRuleInput: (Subject) -> RuleInput,
        toCandidate: (Subject) -> Candidate,
    ): PrivacyEvaluation<Subject> {
        if (derivesFromCreate) {
            return batchEvaluator.evaluate(
                viewerContext = viewerContext,
                client = client,
                subjects = subjects,
                rules = rules,
                toRuleInput = toRuleInput,
                continuationRules = createRules,
                toContinuationInput = toCandidate,
            )
        }
        return batchEvaluator.evaluate(
            viewerContext = viewerContext,
            client = client,
            subjects = subjects,
            rules = rules,
            toRuleInput = toRuleInput,
        )
    }
}
