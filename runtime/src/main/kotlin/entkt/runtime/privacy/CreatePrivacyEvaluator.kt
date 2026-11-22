@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.rule.EntRuleClient

/** Evaluates CREATE privacy over prepared write candidates. */
@EntktInternal
class CreatePrivacyEvaluator<Client : EntRuleClient, Candidate>(
    entityName: String,
    private val rules: List<BatchPrivacyRule<Client, Candidate>>,
) {
    private val batchEvaluator = PrivacyBatchEvaluator(
        entityName = entityName,
        operation = PrivacyOperation.CREATE,
    )

    fun evaluate(
        context: PrivacyRuleContext<Client>,
        candidates: List<Candidate>,
    ): PrivacyEvaluation<Candidate> = batchEvaluator.evaluate(
        context = context,
        subjects = candidates,
        rules = rules,
    )
}
