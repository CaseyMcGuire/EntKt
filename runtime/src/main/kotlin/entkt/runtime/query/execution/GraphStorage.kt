@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.ReadOperation
import kotlin.reflect.KClass

/** Database operations required by the recursive entity-graph algorithm. */
internal interface GraphStorage {
    /** Prepare, execute, and decode the root entity query. */
    fun <Entity : EntEntity<*>> loadRoot(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        maximumRows: Int?,
        privacyContext: PrivacyContext,
    ): List<Entity>

    /** Load one selected relationship and retain how its targets correlate to the sources. */
    fun <Source : EntEntity<*>, Target : EntEntity<*>> loadRelationship(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        context: RelationshipReadContext,
    ): LoadedRelationship<Source, Target>
}

/** Read state shared by all storage work for one selected relationship. */
internal class RelationshipReadContext(
    /** Viewer context supplied to relationship read interceptors. */
    val privacyContext: PrivacyContext,

    /** Entity type at the root of the complete graph read. */
    val rootEntity: KClass<*>,

    /** Selected-edge path from the root to this relationship target. */
    val interceptorPath: List<EdgeStep>,
) {
    /** Return the read context for a child selected edge. */
    fun child(edge: EdgeMapping<*, *>): RelationshipReadContext = RelationshipReadContext(
        privacyContext = privacyContext,
        rootEntity = rootEntity,
        interceptorPath = interceptorPath + EdgeStep(
            source = edge.source.entityClass,
            edgeName = edge.name,
            target = edge.target.entityClass,
        ),
    )
}

/** A loaded target batch and the correlation needed to attach it to its sources. */
internal class LoadedRelationship<
    Source : EntEntity<*>,
    Target : EntEntity<*>,
>(
    val targets: List<Target>,
    private val attachTargets: (List<Target>) -> List<Source>,
) {
    /** Attach targets after their privacy and selected children have been evaluated. */
    fun attach(evaluatedTargets: List<Target>): List<Source> = attachTargets(evaluatedTargets)
}
