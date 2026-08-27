@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.PerParentWindow
import entkt.runtime.driver.executeDirectToMany
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EdgeStorage
import entkt.runtime.query.EagerWindowStrategy
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.StorageQuerySpec
import entkt.runtime.query.ToManyEdgeMapping
import entkt.runtime.query.ToOneEdgeMapping

/**
 * Reads root entities and selected relationships from the database for entity-graph evaluation.
 *
 * This class owns the database-facing portion of graph loading. It compiles each captured
 * [EntityQuery], executes set-based reads through [driver], decodes rows, and preserves the
 * correlation between every source entity and its loaded targets. It does not evaluate LOAD
 * privacy or recursively load target subgraphs; instead, relationship reads return a
 * [LoadedRelationship] that lets the graph evaluator process one deduplicated target batch before
 * attaching those evaluated targets back to their sources.
 *
 * Direct foreign-key relationships are handled here. Junction-backed relationships delegate to
 * [JunctionRelationshipReader] because junction discovery is a distinct two-read algorithm with
 * its own interceptor lifecycle.
 *
 * @property driver database driver used to execute compiled storage queries.
 * @property queryCompiler compiler that applies traversal and interceptor behavior to each read.
 */
internal class DatabaseGraphStorage(
    private val driver: DatabaseDriver,
    private val queryCompiler: ReadQueryCompiler,
) : GraphStorage {
    private val junctionRelationshipReader = JunctionRelationshipReader(driver, queryCompiler)

    /**
     * Compiles and executes the root node of an entity query.
     *
     * [maximumRows] is an internal terminal bound, such as the single-row bound used by
     * `firstOrNull`. It can only tighten the limit captured in [query]. The returned
     * entities have been decoded from database rows but have not yet undergone LOAD privacy or
     * selected-relationship evaluation.
     *
     * @param query recursively captured query whose root rows should be read.
     * @param operation terminal operation exposed to read interceptors.
     * @param maximumRows optional internal upper bound on returned entities.
     * @param viewerContext viewer context supplied to every interceptor in this read.
     * @return decoded root entities in storage-query order.
     */
    override fun <Entity : EntEntity<*>> loadRoot(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        maximumRows: Int?,
        viewerContext: ViewerContext,
    ): List<Entity> {
        val queryForStorage = queryCompiler.compile(query, operation, viewerContext)
        val storageLimit = maximumRows?.let { maximum ->
            minOf(maximum, queryForStorage.limit ?: maximum)
        } ?: queryForStorage.limit
        return readEntities(
            entity = query.entity,
            query = queryForStorage,
            limit = storageLimit,
            offset = queryForStorage.offset,
            maximumEntities = maximumRows,
        )
    }

    /**
     * Loads one selected relationship for a batch of source entities.
     *
     * The edge's declared [EdgeStorage] strategy determines how target rows are discovered and
     * correlated. The returned [LoadedRelationship.targets] list contains one ordered instance of
     * every target retained by at least one source window. Its attachment function accepts those
     * targets after privacy and recursive graph evaluation, then restores the original per-source
     * grouping and relationship order.
     *
     * Query compilation still occurs for an empty [sources] batch so interceptor behavior does not
     * depend on whether a preceding database read happened to return rows.
     *
     * @param selection selected edge and recursively captured target query.
     * @param sources source entities whose relationship targets should be loaded together.
     * @param context viewer and traversal state for this selected relationship.
     * @return the deduplicated target batch and its deferred source-attachment operation.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <Source : EntEntity<*>, Target : EntEntity<*>> loadRelationship(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        context: RelationshipReadContext,
    ): LoadedRelationship<Source, Target> = when (val storage = selection.edge.storageStrategy) {
        is EdgeStorage.ForeignKeyOnSource<*, *, *> -> loadForeignKeyOnSource(
            selection,
            sources,
            storage,
            context,
        )

        is EdgeStorage.ForeignKeyOnTarget<*, *, *> -> loadForeignKeyOnTarget(
            selection,
            sources,
            storage,
            context,
        )

        is EdgeStorage.Junction<*, *, *, *, *> -> junctionRelationshipReader.loadRelationship(
            selection = selection,
            sources = sources,
            storage = storage as EdgeStorage.Junction<Source, Target, *, *, *>,
            context = context,
        )
    }

    /** Load a nullable target whose foreign key is stored on each source entity. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadForeignKeyOnSource(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        untypedStorage: EdgeStorage.ForeignKeyOnSource<*, *, *>,
        context: RelationshipReadContext,
    ): LoadedRelationship<Source, Target> {
        val storage = untypedStorage as EdgeStorage.ForeignKeyOnSource<Source, Target, Any>
        val edge = selection.edge as? ToOneEdgeMapping<Source, Target>
            ?: error("Foreign-key-on-source edge '${selection.edge.name}' must be to-one")
        val targetKeys = sources.mapNotNull(storage.sourceForeignKey).distinct()
        val targetQuery = queryCompiler.compileRelationshipTargetQuery(
            query = selection.target,
            targetColumn = storage.targetColumn,
            targetKeys = targetKeys,
            context = context,
        )
        val targets = readToOneRelationshipTargets(
            entity = selection.target.entity,
            query = targetQuery,
            targetKeys = targetKeys,
        )

        return LoadedRelationship(targets) { evaluatedTargets ->
            val targetsByKey = evaluatedTargets.associateBy(storage.targetKey)
            sources.map { source ->
                edge.attach(source, storage.sourceForeignKey(source)?.let(targetsByKey::get))
            }
        }
    }

    /** Dispatch an inverse foreign-key relationship according to its declared cardinality. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        untypedStorage: EdgeStorage.ForeignKeyOnTarget<*, *, *>,
        context: RelationshipReadContext,
    ): LoadedRelationship<Source, Target> {
        val storage = untypedStorage as EdgeStorage.ForeignKeyOnTarget<Source, Target, Any>
        val sourceKeys = sources.map(storage.sourceKey)
        return when (val edge = selection.edge) {
            is ToOneEdgeMapping<Source, Target> -> loadToOneForeignKeyOnTarget(
                selection,
                edge,
                storage,
                sources,
                sourceKeys,
                context,
            )

            is ToManyEdgeMapping<Source, Target> -> loadToManyForeignKeyOnTarget(
                selection,
                edge,
                storage,
                sources,
                sourceKeys,
                context,
            )

            else -> error("Foreign-key-on-target edge '${edge.name}' must be to-one or to-many")
        }
    }

    /** Load inverse to-one targets and preserve their correlation with each source entity. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadToOneForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        edge: ToOneEdgeMapping<Source, Target>,
        storage: EdgeStorage.ForeignKeyOnTarget<Source, Target, Any>,
        sources: List<Source>,
        sourceKeys: List<Any>,
        context: RelationshipReadContext,
    ): LoadedRelationship<Source, Target> {
        val targetQuery = queryCompiler.compileRelationshipTargetQuery(
            query = selection.target,
            targetColumn = storage.targetColumn,
            targetKeys = sourceKeys,
            context = context,
        )
        val offset = targetQuery.offset ?: 0
        val limit = targetQuery.limit ?: Int.MAX_VALUE
        val orderedTargets = readToOneRelationshipTargets(
            entity = selection.target.entity,
            query = targetQuery,
            targetKeys = sourceKeys,
        )
        val groups = orderedTargets
            .groupBy(storage.targetForeignKey)
            .mapValues { (_, targets) -> targets.drop(offset).take(limit) }
        val retainedTargets = retainTargets(groups, orderedTargets)

        return LoadedRelationship(retainedTargets) { evaluatedTargets ->
            val evaluatedGroups = replaceWithEvaluatedTargets(groups, evaluatedTargets)
            sources.map { source ->
                edge.attach(source, evaluatedGroups[storage.sourceKey(source)]?.firstOrNull())
            }
        }
    }

    /** Load inverse to-many targets using native or emulated per-source windows. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadToManyForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        edge: ToManyEdgeMapping<Source, Target>,
        storage: EdgeStorage.ForeignKeyOnTarget<Source, Target, Any>,
        sources: List<Source>,
        sourceKeys: List<Any>,
        context: RelationshipReadContext,
    ): LoadedRelationship<Source, Target> {
        val capability = driver.directToManyWindowCapability()
        val targetQuery = queryCompiler.compileRelationshipTargetQuery(
            query = selection.target,
            targetColumn = storage.targetColumn,
            targetKeys = sourceKeys,
            context = context,
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
            related.sourceKey to selection.target.entity.decode(related.targetRow)
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
        val retainedTargets = retainTargets(groups, orderedPairs.map { it.second })

        return LoadedRelationship(retainedTargets) { evaluatedTargets ->
            val evaluatedGroups = replaceWithEvaluatedTargets(groups, evaluatedTargets)
            sources.map { source ->
                edge.attach(source, evaluatedGroups[storage.sourceKey(source)] ?: emptyList())
            }
        }
    }

    /** Read to-one targets only when the keys and per-source window can produce a value. */
    private fun <Target : EntEntity<*>> readToOneRelationshipTargets(
        entity: EntityMapping<Target>,
        query: StorageQuerySpec<Target>,
        targetKeys: Collection<*>,
    ): List<Target> {
        val windowCanContainTarget =
            (query.offset ?: 0) == 0 && (query.limit ?: Int.MAX_VALUE) > 0
        if (targetKeys.isEmpty() || !windowCanContainTarget) {
            return emptyList()
        }

        return readEntities(
            entity = entity,
            query = query,
            limit = null,
            offset = null,
        )
    }

    /** Execute a compiled storage query and decode its bounded result rows. */
    private fun <Entity : EntEntity<*>> readEntities(
        entity: EntityMapping<Entity>,
        query: StorageQuerySpec<Entity>,
        limit: Int?,
        offset: Int?,
        maximumEntities: Int? = null,
    ): List<Entity> {
        val rows = driver.query(
            query.table,
            query.predicates,
            query.orderBy,
            limit,
            offset,
        )
        val boundedRows = maximumEntities?.let(rows::take) ?: rows
        return boundedRows.map(entity::decode)
    }
}
