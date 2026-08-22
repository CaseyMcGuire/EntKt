package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.result.PrivacyDenial

/** Evaluates LOAD privacy for any generated entity mapping in one read runtime. */
@EntktInternal
interface LoadPrivacyEvaluator {
    /** Whether [entity] has any LOAD-privacy rules. */
    fun isConfigured(entity: EntityMapping<*>): Boolean

    /** Evaluate LOAD privacy positionally for [entities]. */
    fun <Entity : EntEntity<*>> evaluate(
        entity: EntityMapping<Entity>,
        privacyContext: PrivacyContext,
        entities: List<Entity>,
    ): List<PrivacyDenial?>
}
