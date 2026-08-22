@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EdgeVisibility
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.QuerySource
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.EagerEdgeStep
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import kotlin.reflect.KClass

/**
 * Loads and evaluates the complete entity graph selected by one root query.
 *
 * The algorithm is:
 *
 * 1. Load the root entities as one batch.
 * 2. Evaluate root LOAD privacy; any denial fails the root read.
 * 3. Visit each selected relationship in schema-declaration order.
 * 4. Ask [GraphStorage] to load and correlate that relationship for the complete source
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
    private val loadPrivacyEvaluatorProvider: () -> LoadPrivacyEvaluator,
) {
    /** Load the root batch, then evaluate its recursively selected graph. */
    fun <Entity : EntEntity<*>> loadRoot(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        maximumRows: Int?,
        privacyContext: PrivacyContext,
    ): List<Entity> {
        val rootEntities = storage.loadRoot(
            query = query,
            operation = operation,
            maximumRows = maximumRows,
            privacyContext = privacyContext,
        )
        return evaluateEntityBatch(
            query = query,
            entities = rootEntities,
            privacyDisposition = LoadPrivacyDisposition.Root,
            privacyContext = privacyContext,
            context = rootContext(query),
        )
    }

    /** Apply LOAD privacy and recursively evaluate every selected child relationship. */
    private fun <Node : EntEntity<*>> evaluateEntityBatch(
        query: EntityQuery<Node>,
        entities: List<Node>,
        privacyDisposition: LoadPrivacyDisposition,
        privacyContext: PrivacyContext,
        context: NodeEvaluationContext,
    ): List<Node> {
        val authorizedEntities = evaluateLoadPrivacy(
            entity = query.entity,
            entities = entities,
            disposition = privacyDisposition,
            privacyContext = privacyContext,
        )
        return evaluateSelectedRelationships(
            query = query,
            entities = authorizedEntities,
            privacyContext = privacyContext,
            context = context,
        )
    }

    /** Evaluate selected relationships in declaration order, carrying updated sources forward. */
    private fun <Node : EntEntity<*>> evaluateSelectedRelationships(
        query: EntityQuery<Node>,
        entities: List<Node>,
        privacyContext: PrivacyContext,
        context: NodeEvaluationContext,
    ): List<Node> {
        var entitiesWithRelationships = entities
        for (selection in query.edges) {
            entitiesWithRelationships = evaluateSelection(
                selection,
                entitiesWithRelationships,
                privacyContext,
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
        privacyContext: PrivacyContext,
        context: NodeEvaluationContext,
    ): List<Source> = evaluateTypedSelection(
        selection as EdgeSelection<Source, EntEntity<*>>,
        sources,
        privacyContext,
        context,
    )

    /** Load one relationship, evaluate its target graph, and attach it to the sources. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> evaluateTypedSelection(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        privacyContext: PrivacyContext,
        context: NodeEvaluationContext,
    ): List<Source> {
        val childContext = context.child(selection)
        val loadedRelationship = storage.loadRelationship(
            selection = selection,
            sources = sources,
            privacyContext = privacyContext,
            rootEntity = childContext.rootEntity,
            targetPath = childContext.interceptorPath,
        )
        val evaluatedTargets = evaluateEntityBatch(
            query = selection.target,
            entities = loadedRelationship.targets,
            privacyDisposition = LoadPrivacyDisposition.Edge(
                visibility = selection.visibility,
                denialPath = childContext.denialPath,
            ),
            privacyContext = privacyContext,
            context = childContext,
        )
        return loadedRelationship.attach(evaluatedTargets)
    }

    /** Apply the root or selected-edge LOAD-privacy outcome to one entity batch. */
    private fun <Node : EntEntity<*>> evaluateLoadPrivacy(
        entity: EntityMapping<Node>,
        entities: List<Node>,
        disposition: LoadPrivacyDisposition,
        privacyContext: PrivacyContext,
    ): List<Node> {
        if (disposition is LoadPrivacyDisposition.Root && entities.isEmpty()) {
            return entities
        }

        val evaluator = loadPrivacyEvaluatorProvider()
        if (!evaluator.isConfigured(entity)) {
            return entities
        }
        val denials = evaluator.evaluate(entity, privacyContext, entities)

        return when (disposition) {
            is LoadPrivacyDisposition.Root -> {
                val rejected = denials.filterNotNull()
                if (rejected.isNotEmpty()) {
                    throw EntPrivacyDeniedException(LoadDenialOrigin.Root, rejected)
                }
                entities
            }

            is LoadPrivacyDisposition.Edge -> when (disposition.visibility) {
                EdgeVisibility.FILTER_INVISIBLE -> entities.zip(denials)
                    .filter { (_, denial) -> denial == null }
                    .map { (entityValue, _) -> entityValue }

                EdgeVisibility.REQUIRE_VISIBLE -> {
                    val denial = denials.firstOrNull { it != null }
                    if (denial != null) {
                        throw EntPrivacyDeniedException(
                            LoadDenialOrigin.EagerEdge(disposition.denialPath),
                            listOf(denial),
                        )
                    }
                    entities
                }
            }
        }
    }
}

/** LOAD-privacy outcome required by a root or selected-edge query node. */
private sealed interface LoadPrivacyDisposition {
    data object Root : LoadPrivacyDisposition

    data class Edge(
        val visibility: EdgeVisibility,
        val denialPath: List<EagerEdgeStep>,
    ) : LoadPrivacyDisposition
}

/** Root identity and traversal paths carried through recursive node evaluation. */
private data class NodeEvaluationContext(
    val rootEntity: KClass<*>,
    val interceptorPath: List<EdgeStep>,
    val denialPath: List<EagerEdgeStep>,
) {
    fun <Source : EntEntity<*>, Target : EntEntity<*>> child(
        selection: EdgeSelection<Source, Target>,
    ): NodeEvaluationContext {
        val edge = selection.edge
        return copy(
            interceptorPath = interceptorPath + EdgeStep(
                source = edge.source.entityClass,
                edgeName = edge.name,
                target = edge.target.entityClass,
            ),
            denialPath = denialPath + EagerEdgeStep(
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

private fun rootContext(query: EntityQuery<*>): NodeEvaluationContext = NodeEvaluationContext(
    rootEntity = rootEntity(query),
    interceptorPath = traversalPath(query),
    denialPath = emptyList(),
)
