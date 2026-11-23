@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.rule.EntRuleClient

/** Evaluates LOAD privacy over entities, preserving order and duplicate occurrences. */
@EntktInternal
class LoadPrivacyEvaluator<Viewer : Any, Client : EntRuleClient, Entity>(
    entityName: String,
    private val rules: List<BatchPrivacyRule<Viewer, Client, Entity>>,
) {
    private val batchEvaluator = PrivacyBatchEvaluator(
        entityName = entityName,
        operation = PrivacyOperation.LOAD,
    )

    fun evaluate(
        viewerContext: ViewerContext<Viewer>,
        client: Client,
        entities: List<Entity>,
    ): PrivacyEvaluation<Entity> = batchEvaluator.evaluate(
        viewerContext = viewerContext,
        client = client,
        subjects = entities,
        rules = rules,
    )
}
