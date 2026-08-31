package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.ViewerContext

/** Dispatches LOAD privacy to the bound evaluator for any generated entity mapping. */
@EntktInternal
interface LoadPrivacyDispatcher {
    /** Whether [entity] has any LOAD-privacy rules. */
    fun isConfigured(entity: EntityMapping<*>): Boolean

    /** Evaluate LOAD privacy while preserving each entity's correlation with its outcome. */
    fun <Entity : EntEntity<*>> evaluate(
        entity: EntityMapping<Entity>,
        viewerContext: ViewerContext,
        entities: List<Entity>,
    ): PrivacyEvaluation<Entity>
}
