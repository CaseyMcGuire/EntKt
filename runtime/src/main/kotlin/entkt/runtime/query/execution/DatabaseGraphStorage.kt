@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.PerParentWindow
import entkt.runtime.driver.executeDirectToMany
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EdgeStorage
import entkt.runtime.query.EagerWindowStrategy
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.StorageQuerySpec
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.ToManyEdgeMapping
import entkt.runtime.query.ToOneEdgeMapping
import kotlin.reflect.KClass

/** Executes and correlates every database operation required to materialize an entity graph. */
internal class DatabaseGraphStorage(
    private val driver: DatabaseDriver,
    private val queryCompiler: ReadQueryCompiler,
) : GraphStorage {
    override fun <Entity : EntEntity<*>> loadRoot(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        maximumRows: Int?,
        privacyContext: PrivacyContext,
    ): List<Entity> {
        val queryForStorage = queryCompiler.compile(query, operation, privacyContext)
        val storageLimit = maximumRows?.let { maximum ->
            minOf(maximum, queryForStorage.limit ?: maximum)
        } ?: queryForStorage.limit
        return loadEntities(
            entity = query.entity,
            query = queryForStorage,
            limit = storageLimit,
            offset = queryForStorage.offset,
            maximumEntities = maximumRows,
        )
    }

    override fun <Source : EntEntity<*>, Target : EntEntity<*>> loadRelationship(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
    ): LoadedRelationship<Source, Target> = when (val storage = selection.edge.storageStrategy) {
        is EdgeStorage.ForeignKeyOnSource<*, *, *> -> loadForeignKeyOnSource(
            selection,
            sources,
            storage,
            privacyContext,
            rootEntity,
            targetPath,
        )

        is EdgeStorage.ForeignKeyOnTarget<*, *, *> -> loadForeignKeyOnTarget(
            selection,
            sources,
            storage,
            privacyContext,
            rootEntity,
            targetPath,
        )

        is EdgeStorage.Junction<*, *, *, *, *> -> loadJunction(
            selection,
            sources,
            storage,
            privacyContext,
            rootEntity,
            targetPath,
        )
    }

    /** Load a to-one relationship whose foreign key is stored on each source row. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadForeignKeyOnSource(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        untypedStorage: EdgeStorage.ForeignKeyOnSource<*, *, *>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
    ): LoadedRelationship<Source, Target> {
        val storage = untypedStorage as EdgeStorage.ForeignKeyOnSource<Source, Target, Any>
        val edge = selection.edge as? ToOneEdgeMapping<Source, Target>
            ?: error("Foreign-key-on-source edge '${selection.edge.name}' must be to-one")
        val targetKeys = sources.mapNotNull(storage.sourceForeignKey).distinct()
        val targetQuery = queryCompiler.compileSelectedEdge(
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
            loadEntities(
                entity = selection.target.entity,
                query = targetQuery,
                limit = null,
                offset = null,
            )
        } else {
            emptyList()
        }

        return LoadedRelationship(targets) { evaluatedTargets ->
            val targetsByKey = evaluatedTargets.associateBy(storage.targetKey)
            sources.map { source ->
                edge.attach(source, storage.sourceForeignKey(source)?.let(targetsByKey::get))
            }
        }
    }

    /** Load an inverse foreign-key relationship according to its declared cardinality. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        untypedStorage: EdgeStorage.ForeignKeyOnTarget<*, *, *>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
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
                privacyContext,
                rootEntity,
                targetPath,
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
            )

            else -> error("Foreign-key-on-target edge '${edge.name}' must be to-one or to-many")
        }
    }

    /** Load inverse to-one targets and apply each source's relationship window. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadToOneForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        edge: ToOneEdgeMapping<Source, Target>,
        storage: EdgeStorage.ForeignKeyOnTarget<Source, Target, Any>,
        sources: List<Source>,
        sourceKeys: List<Any>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
    ): LoadedRelationship<Source, Target> {
        val targetQuery = queryCompiler.compileSelectedEdge(
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
            loadEntities(
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
        val retainedTargets = retainedTargets(groups, orderedTargets)

        return LoadedRelationship(retainedTargets) { evaluatedTargets ->
            val evaluatedGroups = evaluatedGroups(groups, evaluatedTargets)
            sources.map { source ->
                edge.attach(source, evaluatedGroups[storage.sourceKey(source)]?.firstOrNull())
            }
        }
    }

    /** Load inverse to-many targets with native or emulated per-source windows. */
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadToManyForeignKeyOnTarget(
        selection: EdgeSelection<Source, Target>,
        edge: ToManyEdgeMapping<Source, Target>,
        storage: EdgeStorage.ForeignKeyOnTarget<Source, Target, Any>,
        sources: List<Source>,
        sourceKeys: List<Any>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
    ): LoadedRelationship<Source, Target> {
        val capability = driver.directToManyWindowCapability()
        val targetQuery = queryCompiler.compileSelectedEdge(
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
        val retainedTargets = retainedTargets(groups, orderedPairs.map { it.second })

        return LoadedRelationship(retainedTargets) { evaluatedTargets ->
            val evaluatedGroups = evaluatedGroups(groups, evaluatedTargets)
            sources.map { source ->
                edge.attach(source, evaluatedGroups[storage.sourceKey(source)] ?: emptyList())
            }
        }
    }

    /** Load targets through junction memberships and preserve their source correlation. */
    @Suppress("UNCHECKED_CAST")
    private fun <Source : EntEntity<*>, Target : EntEntity<*>> loadJunction(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        untypedStorage: EdgeStorage.Junction<*, *, *, *, *>,
        privacyContext: PrivacyContext,
        rootEntity: KClass<*>,
        targetPath: List<EdgeStep>,
    ): LoadedRelationship<Source, Target> {
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
        val junctionQuery = queryCompiler.compileJunction(
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
        val targetQuery = queryCompiler.compileSelectedEdge(
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
            loadEntities(
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
        val retainedTargets = retainedTargets(groups, membershipTargets)

        return LoadedRelationship(retainedTargets) { evaluatedTargets ->
            val evaluatedGroups = evaluatedGroups(groups, evaluatedTargets)
            sources.map { source ->
                edge.attach(source, evaluatedGroups[storage.sourceKey(source)] ?: emptyList())
            }
        }
    }

    /** Keep one ordered instance of every target retained by at least one source window. */
    private fun <Target : EntEntity<*>> retainedTargets(
        groups: Map<Any?, List<Target>>,
        orderedTargets: List<Target>,
    ): List<Target> {
        val retainedIds = groups.values.flatten().mapTo(linkedSetOf()) { it.id }
        return orderedTargets
            .filter { it.id in retainedIds }
            .distinctBy { it.id }
    }

    /** Replace selected targets with their recursively evaluated counterparts in every group. */
    private fun <Target : EntEntity<*>> evaluatedGroups(
        groups: Map<Any?, List<Target>>,
        evaluatedTargets: List<Target>,
    ): Map<Any?, List<Target>> {
        val evaluatedById = evaluatedTargets.associateBy { it.id }
        return groups.mapValues { (_, targets) ->
            targets.mapNotNull { evaluatedById[it.id] }
        }
    }

    /** Execute one storage query and decode its caller-bounded result rows. */
    private fun <Entity : EntEntity<*>> loadEntities(
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
