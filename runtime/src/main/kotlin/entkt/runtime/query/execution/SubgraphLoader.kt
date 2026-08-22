@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.PerParentWindow
import entkt.runtime.driver.executeDirectToMany
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EagerWindowStrategy
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EdgeStorage
import entkt.runtime.query.EdgeVisibility
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.QuerySource
import entkt.runtime.query.ToManyEdgeMapping
import entkt.runtime.query.ToOneEdgeMapping
import entkt.runtime.result.EagerEdgeStep
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import kotlin.reflect.KClass

/**
 * Recursively loads the graph selected beneath an already-materialized entity batch.
 *
 * For each selected edge, in schema-declaration order:
 *
 * 1. Collect the relationship keys from the complete source batch.
 * 2. Prepare the target query, including traversal context, interceptors, and the
 *    structural relationship predicate.
 * 3. Fetch related rows as a set. A direct edge uses one target read; a many-to-many
 *    edge reads the junction set and then the discovered target set.
 * 4. Correlate targets back to their source keys and apply each source's configured
 *    relationship window.
 * 5. Evaluate LOAD privacy for the distinct retained target batch, filtering or failing
 *    according to the edge's visibility policy.
 * 6. Recursively run this algorithm for the retained targets' selected edges.
 * 7. Attach the completed target values or groups to their source entities.
 *
 * The unit of batching is one selected edge path: never one query per source entity,
 * but also not one query for the entire graph.
 */
@entkt.query.EntktInternal
class SubgraphLoader(
    private val driver: DatabaseDriver,
    private val queryPreparation: EntityQueryPreparation,
    private val entityLoader: EntityLoader,
    private val loadPrivacyEvaluator: () -> LoadPrivacyEvaluator,
) {
    /** Load every selected edge in schema-declaration order. */
    fun <Entity : EntEntity<*>> load(
        query: EntityQuery<Entity>,
        entities: List<Entity>,
        privacyContext: PrivacyContext,
    ): List<Entity> = loadEdges(
        query = query,
        entities = entities,
        privacyContext = privacyContext,
        rootEntity = rootEntity(query),
        interceptorPath = traversalPath(query),
        denialPath = emptyList(),
    )

    /** Recursively attach every edge selected directly beneath this query node. */
    private fun <Entity : EntEntity<*>> loadEdges(
        query: EntityQuery<Entity>,
        entities: List<Entity>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        interceptorPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): List<Entity> {
        var entitiesWithEdges = entities
        for (selection in query.edges) {
            entitiesWithEdges = loadSelection(
                selection,
                entitiesWithEdges,
                privacyContext,
                rootEntity,
                interceptorPath,
                denialPath,
            )
        }
        return entitiesWithEdges
    }

    /** Recover the target type erased by the heterogeneous selected-edge list. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>> loadSelection(
        selection: EdgeSelection<Source, *>,
        sources: List<Source>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        interceptorPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): List<Source> = loadTypedSelection(
        selection as EdgeSelection<Source, EntEntity<*>>,
        sources,
        privacyContext,
        rootEntity,
        interceptorPath,
        denialPath,
    )

    /** Extend traversal paths and dispatch the edge to its relationship storage strategy. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadTypedSelection(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        interceptorPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): List<Source> {
        val edge = selection.edge
        val queryStep = EdgeStep(
            source = edge.source.entityClass,
            edgeName = edge.name,
            target = edge.target.entityClass,
        )
        val denialStep = EagerEdgeStep(
            sourceEntityType = edge.source.entityName,
            edgeName = edge.name,
            targetEntityType = edge.target.entityName,
        )
        val targetPath = interceptorPath + queryStep
        val targetDenialPath = denialPath + denialStep

        return when (val storage = edge.storageStrategy) {
            is EdgeStorage.ForeignKeyOnSource<*, *, *> -> loadForeignKeyOnSource(
                selection,
                sources,
                storage,
                privacyContext,
                rootEntity,
                targetPath,
                targetDenialPath,
            )

            is EdgeStorage.ForeignKeyOnTarget<*, *, *> -> loadForeignKeyOnTarget(
                selection,
                sources,
                storage,
                privacyContext,
                rootEntity,
                targetPath,
                targetDenialPath,
            )

            is EdgeStorage.Junction<*, *, *, *, *> -> loadJunction(
                selection,
                sources,
                storage,
                privacyContext,
                rootEntity,
                targetPath,
                targetDenialPath,
            )
        }
    }

    /** Batch-load a to-one edge whose foreign key is stored on each source row. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadForeignKeyOnSource(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        untypedStorage: EdgeStorage.ForeignKeyOnSource<*, *, *>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): List<Source> {
        val storage = untypedStorage as EdgeStorage.ForeignKeyOnSource<Source, Target, Any>
        val edge = selection.edge as? ToOneEdgeMapping<Source, Target>
            ?: error("Foreign-key-on-source edge '${selection.edge.name}' must be to-one")
        val targetKeys = sources.mapNotNull(storage.sourceForeignKey).distinct()
        val targetQuery = queryPreparation.prepareSelectedEdge(
            query = selection.target,
            privacyContext = privacyContext,
            rootEntity = rootEntity,
            path = targetPath,
            structuralPredicates = listOf(
                Predicate.Leaf(storage.targetColumn, Op.IN, targetKeys),
            ),
        )
        val targetInWindow = (targetQuery.offset ?: 0) == 0 &&
            (targetQuery.limit ?: Int.MAX_VALUE) > 0
        val targets = if (targetInWindow && targetKeys.isNotEmpty()) {
            entityLoader.load(
                entity = selection.target.entity,
                query = targetQuery,
                limit = null,
                offset = null,
            )
        } else {
            emptyList()
        }
        val visibleTargets = applyPrivacy(
            selection,
            targets,
            privacyContext,
            denialPath,
        )
        val nestedTargets = loadEdges(
            selection.target,
            visibleTargets,
            privacyContext,
            rootEntity,
            targetPath,
            denialPath,
        )
        val targetsByKey = nestedTargets.associateBy(storage.targetKey)
        return sources.map { source ->
            edge.attach(source, storage.sourceForeignKey(source)?.let(targetsByKey::get))
        }
    }

    /** Dispatch an inverse foreign-key edge according to its declared cardinality. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        untypedStorage: EdgeStorage.ForeignKeyOnTarget<*, *, *>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): List<Source> {
        val storage = untypedStorage as EdgeStorage.ForeignKeyOnTarget<Source, Target, Any>
        val sourceKeys = sources.map(storage.sourceKey)
        return when (val edge = selection.edge) {
            is ToOneEdgeMapping<Source, Target> -> loadToOneForeignKeyOnTarget(
                selection,
                edge,
                storage,
                sources,
                sourceKeys,
                privacyContext,
                rootEntity,
                targetPath,
                denialPath,
            )

            is ToManyEdgeMapping<Source, Target> -> loadToManyForeignKeyOnTarget(
                selection,
                edge,
                storage,
                sources,
                sourceKeys,
                privacyContext,
                rootEntity,
                targetPath,
                denialPath,
            )

            else -> error("Foreign-key-on-target edge '${edge.name}' must be to-one or to-many")
        }
    }

    /** Batch-load inverse to-one targets, apply each source window, then attach one target. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadToOneForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        edge: ToOneEdgeMapping<Source, Target>,
        storage: EdgeStorage.ForeignKeyOnTarget<Source, Target, Any>,
        sources: List<Source>,
        sourceKeys: List<Any>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): List<Source> {
        val targetQuery = queryPreparation.prepareSelectedEdge(
            query = selection.target,
            privacyContext = privacyContext,
            rootEntity = rootEntity,
            path = targetPath,
            structuralPredicates = listOf(
                Predicate.Leaf(storage.targetColumn, Op.IN, sourceKeys),
            ),
        )
        val offset = targetQuery.offset ?: 0
        val limit = targetQuery.limit ?: Int.MAX_VALUE
        val targetInWindow = offset == 0 && limit > 0
        val orderedTargets = if (targetInWindow && sourceKeys.isNotEmpty()) {
            entityLoader.load(
                entity = selection.target.entity,
                query = targetQuery,
                limit = null,
                offset = null,
            )
        } else {
            emptyList()
        }
        val groups = orderedTargets
            .groupBy(storage.targetForeignKey)
            .mapValues { (_, targets) -> targets.drop(offset).take(limit) }
        val loadedGroups = loadVisibleNestedGroups(
            selection,
            groups,
            orderedTargets,
            privacyContext,
            rootEntity,
            targetPath,
            denialPath,
        )
        return sources.map { source ->
            edge.attach(source, loadedGroups[storage.sourceKey(source)]?.firstOrNull())
        }
    }

    /** Batch-load inverse to-many targets with native or emulated per-source windows. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadToManyForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        edge: ToManyEdgeMapping<Source, Target>,
        storage: EdgeStorage.ForeignKeyOnTarget<Source, Target, Any>,
        sources: List<Source>,
        sourceKeys: List<Any>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): List<Source> {
        val capability = driver.directToManyWindowCapability()
        val targetQuery = queryPreparation.prepareSelectedEdge(
            query = selection.target,
            privacyContext = privacyContext,
            rootEntity = rootEntity,
            path = targetPath,
            structuralPredicates = listOf(
                Predicate.Leaf(storage.targetColumn, Op.IN, sourceKeys),
            ),
            structuralSingleBindTransport = capability == DirectToManyWindowCapability.NATIVE,
        )
        val relatedRows = executeDirectToMany(
            driver = driver,
            query = DirectToManyQuery(
                targetTable = targetQuery.table,
                sourceKeys = sourceKeys,
                targetForeignKey = storage.targetColumn,
                targetPredicates = targetQuery.nonStructuralPredicates,
                effectiveOrder = targetQuery.orderBy,
                window = PerParentWindow(
                    offset = targetQuery.offset ?: 0,
                    limit = targetQuery.limit,
                ),
            ),
            emulationPredicates = targetQuery.predicates,
            capability = capability,
        )
        val orderedPairs = relatedRows.rows.map { related ->
            related.sourceKey to entityLoader.decode(
                selection.target.entity,
                related.targetRow,
            )
        }
        val groupedPairs = orderedPairs.groupBy { it.first }
        val groups = if (relatedRows.strategy == EagerWindowStrategy.STORAGE_NATIVE) {
            groupedPairs.mapValues { (_, pairs) -> pairs.map { it.second } }
        } else {
            val offset = targetQuery.offset ?: 0
            val limit = targetQuery.limit ?: Int.MAX_VALUE
            groupedPairs.mapValues { (_, pairs) ->
                pairs.drop(offset).take(limit).map { it.second }
            }
        }
        val loadedGroups = loadVisibleNestedGroups(
            selection,
            groups,
            orderedPairs.map { it.second },
            privacyContext,
            rootEntity,
            targetPath,
            denialPath,
        )
        return sources.map { source ->
            edge.attach(source, loadedGroups[storage.sourceKey(source)] ?: emptyList())
        }
    }

    /** Load junction memberships, fetch their distinct targets, and regroup them by source. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadJunction(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        untypedStorage: EdgeStorage.Junction<*, *, *, *, *>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): List<Source> {
        val storage = untypedStorage as EdgeStorage.Junction<
            Source,
            Target,
            EntEntity<*>,
            Any,
            Any
            >
        val edge = selection.edge as? ToManyEdgeMapping<Source, Target>
            ?: error("Junction edge '${selection.edge.name}' must be to-many")
        val sourceKeys = sources.map(storage.sourceKey)
        val junctionQuery = queryPreparation.prepareJunction(
            entity = storage.junctionEntity,
            privacyContext = privacyContext,
            rootEntity = rootEntity,
            path = targetPath,
            structuralPredicates = listOf(
                Predicate.Leaf(storage.sourceColumn, Op.IN, sourceKeys),
            ),
        )
        val junctionRows = if (sourceKeys.isNotEmpty()) {
            driver.query(
                junctionQuery.table,
                junctionQuery.predicates,
                junctionQuery.orderBy,
                null,
                null,
            )
        } else {
            emptyList()
        }
        val targetKeys = junctionRows.map { it[storage.targetColumn] }.distinct()
        val targetQuery = queryPreparation.prepareSelectedEdge(
            query = selection.target,
            privacyContext = privacyContext,
            rootEntity = rootEntity,
            path = targetPath,
            structuralPredicates = listOf(
                Predicate.Leaf("id", Op.IN, targetKeys),
            ),
        )
        val limit = targetQuery.limit ?: Int.MAX_VALUE
        val orderedTargets = if (limit > 0 && targetKeys.isNotEmpty()) {
            entityLoader.load(
                entity = selection.target.entity,
                query = targetQuery,
                limit = null,
                offset = null,
            )
        } else {
            emptyList()
        }
        val sourceKeysByTarget = linkedMapOf<Any?, MutableSet<Any?>>()
        for (row in junctionRows) {
            sourceKeysByTarget
                .getOrPut(row[storage.targetColumn]) { linkedSetOf() }
                .add(row[storage.sourceColumn])
        }
        val mutableGroups = linkedMapOf<Any?, MutableList<Target>>()
        val membershipTargets = mutableListOf<Target>()
        for (target in orderedTargets) {
            val relatedSources = sourceKeysByTarget[storage.targetKey(target)] ?: continue
            membershipTargets += target
            for (sourceKey in relatedSources) {
                mutableGroups.getOrPut(sourceKey) { mutableListOf() } += target
            }
        }
        val offset = targetQuery.offset ?: 0
        val groups = mutableGroups.mapValues { (_, targets) ->
            targets.drop(offset).take(limit)
        }
        val loadedGroups = loadVisibleNestedGroups(
            selection,
            groups,
            membershipTargets,
            privacyContext,
            rootEntity,
            targetPath,
            denialPath,
        )
        return sources.map { source ->
            edge.attach(source, loadedGroups[storage.sourceKey(source)] ?: emptyList())
        }
    }

    /** Authorize retained targets once, load their subgraphs, and restore source groupings. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadVisibleNestedGroups(
        selection: EdgeSelection<Source, Target>,
        groups: Map<Any?, List<Target>>,
        orderedTargets: List<Target>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
        denialPath: List<EagerEdgeStep>,
    ): Map<Any?, List<Target>> {
        val retainedIds = groups.values.flatten().mapTo(linkedSetOf()) { it.id }
        val retainedTargets = orderedTargets.filter { it.id in retainedIds }.distinctBy { it.id }
        val visibleTargets = applyPrivacy(
            selection,
            retainedTargets,
            privacyContext,
            denialPath,
        )
        val visibleIds = visibleTargets.mapTo(hashSetOf()) { it.id }
        val visibleGroups = groups.mapValues { (_, targets) ->
            targets.filter { it.id in visibleIds }
        }
        val nestedTargets = loadEdges(
            selection.target,
            visibleTargets,
            privacyContext,
            rootEntity,
            targetPath,
            denialPath,
        )
        val nestedById = nestedTargets.associateBy { it.id }
        return visibleGroups.mapValues { (_, targets) ->
            targets.map { nestedById.getValue(it.id) }
        }
    }

    /** Apply the selected edge's filter-or-fail LOAD-privacy policy. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> applyPrivacy(
        selection: EdgeSelection<Source, Target>,
        targets: List<Target>,
        privacyContext: PrivacyContext,
        denialPath: List<EagerEdgeStep>,
    ): List<Target> {
        val evaluator = loadPrivacyEvaluator()
        if (!evaluator.isConfigured(selection.target.entity)) {
            return targets
        }
        val denials = evaluator.evaluate(
            selection.target.entity,
            privacyContext,
            targets,
        )
        return when (selection.visibility) {
            EdgeVisibility.FILTER_INVISIBLE -> targets.zip(denials)
                .filter { (_, denial) -> denial == null }
                .map { (target, _) -> target }

            EdgeVisibility.REQUIRE_VISIBLE -> {
                val denial = denials.firstOrNull { it != null }
                if (denial != null) {
                    throw EntPrivacyDeniedException(
                        LoadDenialOrigin.EagerEdge(denialPath),
                        listOf(denial),
                    )
                }
                targets
            }
        }
    }

    /** Resolve the entity at the beginning of a possibly traversed query. */
    private fun rootEntity(query: EntityQuery<*>): KClass<*> = when (val source = query.source) {
        is QuerySource.Root -> query.entity.entityClass
        is QuerySource.Traversal<*, *> -> rootEntity(source.source)
    }

    /** Reconstruct the traversal steps that precede this selected subgraph. */
    private fun traversalPath(query: EntityQuery<*>): List<EdgeStep> = when (val source = query.source) {
        is QuerySource.Root -> emptyList()
        is QuerySource.Traversal<*, *> -> traversalPath(source.source) + EdgeStep(
            source = source.edge.source.entityClass,
            edgeName = source.edge.name,
            target = source.edge.target.entityClass,
        )
    }
}
