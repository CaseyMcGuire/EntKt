@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyOutcome
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EdgeVisibility
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.QuerySource
import entkt.runtime.query.QueryLockMode
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ReadResult
import entkt.runtime.result.SelectedEdgeStep
import kotlin.reflect.KClass

/**
 * Loads and evaluates the complete entity graph selected by one root query.
 *
 * The algorithm is:
 *
 * 1. Load the root entities as one batch.
 * 2. Evaluate root LOAD privacy, retaining an outcome for each selected root.
 * 3. Visit each selected relationship in schema-declaration order.
 * 4. Ask [GraphStorage] to load and correlate that relationship for the authorized source
 *    batch. Direct relationships use one target read; many-to-many relationships discover
 *    junction rows before loading the resulting target set.
 * 5. Evaluate LOAD privacy for the retained target batch, filtering or failing according
 *    to the relationship's visibility policy.
 * 6. Recursively evaluate the targets' selected relationships.
 * 7. Attach the completed targets to their source entities.
 *
 * The unit of batching is one selected relationship path: never one query per source
 * entity, but also not one query for the entire graph.
 */
internal class EntityGraphLoader(
    private val storage: GraphStorage,
    private val loadPrivacyDispatcher: LoadPrivacyDispatcher,
) {
    /** Reuse batch graph loading with a one-row cap, unwrapping absence or the selected outcome. */
    fun <Entity : EntEntity<*>> loadOne(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        viewerContext: ViewerContext,
        lockMode: QueryLockMode = QueryLockMode.None,
    ): Entity? {
        val entries = loadMany(
            query = query,
            operation = operation,
            maximumRows = 1,
            viewerContext = viewerContext,
            lockMode = lockMode,
        )
        return entries.firstOrNull()?.getOrThrow()
    }

    /** Load the root batch, retaining each root's outcome while evaluating authorized graphs. */
    fun <Entity : EntEntity<*>> loadMany(
        query: EntityQuery<Entity>,
        viewerContext: ViewerContext,
        operation: ReadOperation = ReadOperation.ALL,
        maximumRows: Int? = null,
        lockMode: QueryLockMode = QueryLockMode.None,
    ): List<ReadResult<Entity>> {
        val rootEntities = storage.loadRoot(
            query = query,
            operation = operation,
            maximumRows = maximumRows,
            viewerContext = viewerContext,
            lockMode = lockMode,
        )
        val entries = evaluateRootPrivacy(query.entity, rootEntities, viewerContext)
        if (query.edges.isEmpty()) {
            return entries
        }
        val authorizedRoots = entries.mapNotNull { entry ->
            when (entry) {
                is ReadResult.Success -> entry.value
                is ReadResult.Failed -> null
            }
        }
        // Denied roots never enter the selected graph. A genuinely empty root still
        // visits selected paths for their existing empty-batch interceptor lifecycle.
        if (entries.isNotEmpty() && authorizedRoots.isEmpty()) {
            return entries
        }
        val loadedRoots = evaluateSelectedRelationships(
            query = query,
            entities = authorizedRoots,
            context = rootContext(query, viewerContext),
        )
        // Relationship attachment preserves source order and cardinality. Correlate
        // by occurrence, not ID, so repeated roots retain their original slots.
        val loaded = loadedRoots.iterator()
        return entries.map { entry ->
            when (entry) {
                is ReadResult.Success -> ReadResult.Success(loaded.next())
                is ReadResult.Failed -> entry
            }
        }
    }

    private fun <Entity : EntEntity<*>> evaluateRootPrivacy(
        entity: EntityMapping<Entity>,
        entities: List<Entity>,
        viewerContext: ViewerContext,
    ): List<ReadResult<Entity>> {
        if (entities.isEmpty() || !loadPrivacyDispatcher.isConfigured(entity)) {
            return entities.map { ReadResult.Success(it) }
        }
        return loadPrivacyDispatcher.evaluate(entity, viewerContext, entities).map { outcome ->
            when (outcome) {
                is PrivacyOutcome.Allowed -> ReadResult.Success(outcome.subject)
                is PrivacyOutcome.Denied -> {
                    val denial = PrivacyDenial(
                        entity.entityName,
                        EntityKey("id", outcome.subject.id),
                        outcome.reason,
                    )
                    ReadResult.failedForInternalUse(EntPrivacyDeniedException(LoadDenialOrigin.Root, listOf(denial)))
                }
            }
        }
    }

    /** Apply LOAD privacy and recursively evaluate every selected child relationship. */
    private fun <Node : EntEntity<*>> evaluateEntityBatch(
        query: EntityQuery<Node>,
        entities: List<Node>,
        denialPolicy: LoadDenialPolicy,
        context: NodeEvaluationContext,
    ): List<Node> {
        val authorizedEntities = evaluateLoadPrivacy(
            entity = query.entity,
            entities = entities,
            denialPolicy = denialPolicy,
            viewerContext = context.read.viewerContext,
        )
        return evaluateSelectedRelationships(
            query = query,
            entities = authorizedEntities,
            context = context,
        )
    }

    /** Evaluate selected relationships in declaration order, carrying updated sources forward. */
    private fun <Node : EntEntity<*>> evaluateSelectedRelationships(
        query: EntityQuery<Node>,
        entities: List<Node>,
        context: NodeEvaluationContext,
    ): List<Node> {
        var entitiesWithRelationships = entities
        for (selection in query.edges) {
            entitiesWithRelationships = evaluateSelection(
                selection,
                entitiesWithRelationships,
                context,
            )
        }
        return entitiesWithRelationships
    }

    /** Recover the target type erased by the heterogeneous selected-edge list. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>> evaluateSelection(
        selection: EdgeSelection<Source, *>,
        sources: List<Source>,
        context: NodeEvaluationContext,
    ): List<Source> = evaluateTypedSelection(
        selection as EdgeSelection<Source, EntEntity<*>>,
        sources,
        context,
    )

    /** Load one relationship, evaluate its target graph, and attach it to the sources. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> evaluateTypedSelection(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        context: NodeEvaluationContext,
    ): List<Source> {
        val childContext = context.child(selection)
        val loadedRelationship = storage.loadRelationship(
            selection = selection,
            sources = sources,
            context = childContext.read,
        )
        val evaluatedTargets = evaluateEntityBatch(
            query = selection.target,
            entities = loadedRelationship.targets,
            denialPolicy = when (selection.visibility) {
                EdgeVisibility.FILTER_INVISIBLE -> LoadDenialPolicy.FilterDeniedTargets
                EdgeVisibility.REQUIRE_VISIBLE -> LoadDenialPolicy.FailEdge(
                    LoadDenialOrigin.SelectedEdgePath(childContext.denialPath),
                )
            },
            context = childContext,
        )
        return loadedRelationship.attach(evaluatedTargets)
    }

    /** Apply selected-edge LOAD privacy to one entity batch. */
    private fun <Node : EntEntity<*>> evaluateLoadPrivacy(
        entity: EntityMapping<Node>,
        entities: List<Node>,
        denialPolicy: LoadDenialPolicy,
        viewerContext: ViewerContext,
    ): List<Node> {
        if (!loadPrivacyDispatcher.isConfigured(entity)) {
            return entities
        }
        val evaluation = loadPrivacyDispatcher.evaluate(entity, viewerContext, entities)

        fun denial(outcome: PrivacyOutcome.Denied<Node>) =
            PrivacyDenial(
                entity.entityName,
                EntityKey("id", outcome.subject.id),
                outcome.reason,
            )

        return when (denialPolicy) {
            is LoadDenialPolicy.FailEdge -> {
                val firstDenied = evaluation.firstDeniedOrNull()
                if (firstDenied != null) {
                    throw EntPrivacyDeniedException(
                        denialPolicy.origin,
                        listOf(denial(firstDenied)),
                    )
                }
                evaluation.allowedSubjects()
            }

            LoadDenialPolicy.FilterDeniedTargets ->
                evaluation.allowedSubjects()
        }
    }
}

/** Determines how an entity batch handles LOAD-privacy denials during graph evaluation. */
private sealed interface LoadDenialPolicy {
    /** Any denied target fails the query and identifies the selected edge that caused it. */
    data class FailEdge(val origin: LoadDenialOrigin.SelectedEdgePath) : LoadDenialPolicy

    /** Denied targets are omitted while visible targets remain attached to the selected edge. */
    data object FilterDeniedTargets : LoadDenialPolicy
}

/** Relationship read state and privacy-denial path for one graph node. */
private class NodeEvaluationContext(
    val read: RelationshipReadContext,
    val denialPath: List<SelectedEdgeStep>,
) {
    fun <Source : EntEntity<*>, Target : EntEntity<*>> child(
        selection: EdgeSelection<Source, Target>,
    ): NodeEvaluationContext {
        val edge = selection.edge
        return NodeEvaluationContext(
            read = read.child(edge),
            denialPath = denialPath + SelectedEdgeStep(
                sourceEntityType = edge.source.entityName,
                edgeName = edge.name,
                targetEntityType = edge.target.entityName,
            ),
        )
    }
}

/** Resolve the entity at the beginning of a possibly traversed query. */
private fun rootEntity(query: EntityQuery<*>): KClass<*> = when (val source = query.source) {
    is QuerySource.Root -> query.entity.entityClass
    is QuerySource.Traversal<*, *> -> rootEntity(source.source)
}

/** Reconstruct the traversal steps that precede this selected graph. */
private fun traversalPath(query: EntityQuery<*>): List<EdgeStep> = when (val source = query.source) {
    is QuerySource.Root -> emptyList()
    is QuerySource.Traversal<*, *> -> traversalPath(source.source) + EdgeStep(
        source = source.edge.source.entityClass,
        edgeName = source.edge.name,
        target = source.edge.target.entityClass,
    )
}

private fun rootContext(
    query: EntityQuery<*>,
    viewerContext: ViewerContext,
): NodeEvaluationContext = NodeEvaluationContext(
    read = RelationshipReadContext(
        viewerContext = viewerContext,
        rootEntity = rootEntity(query),
        interceptorPath = traversalPath(query),
    ),
    denialPath = emptyList(),
)
