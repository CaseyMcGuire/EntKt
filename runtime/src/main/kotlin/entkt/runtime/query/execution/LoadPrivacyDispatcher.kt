package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.ViewerContext

/**
 * Dispatches LOAD privacy to the bound evaluator for any generated entity mapping.
 * The evaluator owns deny-by-default and explicit-bypass behavior, including when no rules exist.
 */
@EntktInternal
interface LoadPrivacyDispatcher {
    /** Evaluate LOAD privacy while preserving each entity's correlation with its outcome. */
    fun <Entity : EntEntity<*>> evaluate(
        entity: EntityMapping<Entity>,
        viewerContext: ViewerContext,
        entities: List<Entity>,
    ): PrivacyEvaluation<Entity>
}
