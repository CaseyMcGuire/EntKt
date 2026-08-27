package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.result.PrivacyDenial

/** The final LOAD-privacy outcome for one entity. */
@EntktInternal
sealed interface LoadPrivacyEvaluation<out Entity : EntEntity<*>> {
    /** Return this outcome's denial details, or `null` when the entity was allowed. */
    fun denialOrNull(): PrivacyDenial? = when (this) {
        is Allowed -> null
        is Denied -> denial
    }

    /** Return the allowed entity, or `null` when LOAD privacy denied it. */
    fun allowedEntityOrNull(): Entity? = when (this) {
        is Allowed -> entity
        is Denied -> null
    }

    /** Entity permitted by its LOAD-privacy rules. */
    class Allowed<Entity : EntEntity<*>> internal constructor(
        override val entity: Entity,
    ) : LoadPrivacyEvaluation<Entity>

    /** Entity rejected by its LOAD-privacy rules, with the corresponding denial details. */
    class Denied<Entity : EntEntity<*>> internal constructor(
        override val entity: Entity,
        val denial: PrivacyDenial,
    ) : LoadPrivacyEvaluation<Entity>

    /** Entity whose LOAD-privacy evaluation produced this outcome. */
    val entity: Entity
}

/** Evaluates LOAD privacy for any generated entity mapping in one read runtime. */
@EntktInternal
interface LoadPrivacyEvaluator {
    /** Whether [entity] has any LOAD-privacy rules. */
    fun isConfigured(entity: EntityMapping<*>): Boolean

    /** Evaluate LOAD privacy while preserving each entity's correlation with its denial. */
    fun <Entity : EntEntity<*>> evaluate(
        entity: EntityMapping<Entity>,
        viewerContext: ViewerContext,
        entities: List<Entity>,
    ): List<LoadPrivacyEvaluation<Entity>>
}

/**
 * Correlates a generated positional denial result with the entities it evaluated.
 *
 * Generated repositories already validate each batch rule's decisions. This boundary check also
 * prevents a malformed read-runtime adapter from silently dropping or inventing entity results.
 */
@EntktInternal
fun <Entity : EntEntity<*>> correlateLoadPrivacyEvaluationsForInternalUse(
    lifecycle: String,
    entities: List<Entity>,
    denials: List<PrivacyDenial?>,
): List<LoadPrivacyEvaluation<Entity>> {
    if (denials.size != entities.size) {
        throw EntBatchRuleContractException(lifecycle, entities.size, denials.size)
    }
    return entities.mapIndexed { index, entity ->
        val denial = denials[index]
        if (denial == null) {
            LoadPrivacyEvaluation.Allowed(entity)
        } else {
            LoadPrivacyEvaluation.Denied(entity, denial)
        }
    }
}
