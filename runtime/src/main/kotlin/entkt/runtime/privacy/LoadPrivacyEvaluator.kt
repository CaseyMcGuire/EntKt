@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.rule.EntRuleClient

/** Evaluates LOAD privacy over entities, preserving order and duplicate occurrences. */
@EntktInternal
class LoadPrivacyEvaluator<Client : EntRuleClient, Entity>(
    entityName: String,
    private val rules: List<BatchPrivacyRule<Client, Entity>>,
) {
    private val batchEvaluator = PrivacyBatchEvaluator(
        entityName = entityName,
        operation = PrivacyOperation.LOAD,
    )

    fun evaluate(
        context: PrivacyRuleContext<Client>,
        entities: List<Entity>,
    ): PrivacyEvaluation<Entity> = batchEvaluator.evaluate(
        context = context,
        subjects = entities,
        rules = rules,
    )
}
