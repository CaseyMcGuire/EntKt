@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EdgeStorage
import entkt.runtime.query.StorageQuerySpec
import entkt.runtime.query.ToManyEdgeMapping

/**
 * Loads one many-to-many relationship through its junction entity.
 *
 * The reader compiles and reads junction memberships, discovers and loads the
 * selected targets, correlates targets to every source in target-query order,
 * applies each source's relationship window, and retains the correlation until
 * graph evaluation returns the privacy-checked target batch for attachment.
 *
 * Junction rows run the `EAGER_JUNCTION` interceptor lifecycle but intentionally do not undergo
 * junction-entity LOAD privacy. The discovered target entities run the normal selected-edge
 * compilation and are returned to the graph evaluator for target LOAD privacy and recursion.
 *
 * @property driver database driver used for junction discovery and target reads.
 * @property queryCompiler compiler that applies the appropriate interceptor context to each read.
 */
internal class JunctionRelationshipReader(
    private val driver: DatabaseDriver,
    private val queryCompiler: ReadQueryCompiler,
) {
    /**
     * Loads and correlates one junction-backed selected relationship.
     *
     * Junction memberships are read for all [sources] at once. Their distinct target keys constrain
     * one target query, whose ordering determines the ordering inside every source group. The edge
     * query's offset and limit are then applied independently to each group. Targets retained by at
     * least one group are deduplicated for graph evaluation, while the returned attachment function
     * preserves enough correlation to rebuild every source relationship afterward.
     *
     * @param selection selected many-to-many edge and recursively captured target query.
     * @param sources source entities whose junction memberships should be loaded together.
     * @param storage typed junction metadata for the selected edge.
     * @param context viewer and traversal state for this selected relationship.
     * @return the deduplicated target batch and its deferred source-attachment operation.
     */
    fun <
        Source : EntEntity<*>,
        Target : EntEntity<*>,
        JunctionEntity : EntEntity<*>,
        SourceKey : Any,
        TargetKey : Any,
        > loadRelationship(
        selection: EdgeSelection<Source, Target>,
        sources: List<Source>,
        storage: EdgeStorage.Junction<Source, Target, JunctionEntity, SourceKey, TargetKey>,
        context: RelationshipReadContext,
    ): LoadedRelationship<Source, Target> {
        // Validate the generated edge shape and collect every source key for one batched read.
        val edge = selection.edge as? ToManyEdgeMapping<Source, Target>
            ?: error("Junction edge '${selection.edge.name}' must be to-many")
        val sourceKeys = sources.map(storage.sourceKey)

        // Discover target keys and valid source-to-target memberships through the junction.
        val junctionRead = readJunction(
            storage = storage,
            sourceKeys = sourceKeys,
            context = context,
        )

        // Compile and read all discovered targets in the selected target query's order.
        val targetQuery = queryCompiler.compileRelationshipTargetQuery(
            query = selection.target,
            targetColumn = "id",
            targetKeys = junctionRead.targetKeys,
            context = context,
        )
        val targetsInQueryOrder = readTargets(
            entity = selection.target.entity,
            query = targetQuery,
            targetKeys = junctionRead.targetKeys,
        )

        // Restore source correlation, then apply the target window independently to each source.
        val targetsBySource = groupTargetsBySource(
            memberships = junctionRead.memberships,
            targetsInQueryOrder = targetsInQueryOrder,
            targetKey = storage.targetKey,
        )
        val selectedTargetsBySource = applyPerSourceWindow(
            targetsBySource = targetsBySource,
            query = targetQuery,
        )

        // Evaluate each retained target only once, even when several sources share it.
        val targetsToEvaluate = retainTargets(
            groups = selectedTargetsBySource,
            orderedTargets = targetsInQueryOrder,
        )

        // Attach privacy-checked, recursively loaded targets after graph evaluation completes.
        return LoadedRelationship(targetsToEvaluate) { evaluatedTargets ->
            attachEvaluatedTargets(
                sources = sources,
                targetsBySource = selectedTargetsBySource,
                evaluatedTargets = evaluatedTargets,
                sourceKey = storage.sourceKey,
                edge = edge,
            )
        }
    }

    private fun <TargetKey : Any, Target : EntEntity<*>> readTargets(
        entity: EntityMapping<Target>,
        query: StorageQuerySpec<Target>,
        targetKeys: List<TargetKey?>,
    ): List<Target> {
        val targetQueryCanReturnRows = (query.limit ?: Int.MAX_VALUE) > 0
        if (targetKeys.isEmpty() || !targetQueryCanReturnRows) {
            return emptyList()
        }

        return driver.query(
            query.table,
            query.predicates,
            query.orderBy,
            null,
            null,
        ).map(entity::decode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <
        Source : EntEntity<*>,
        Target : EntEntity<*>,
        JunctionEntity : EntEntity<*>,
        SourceKey : Any,
        TargetKey : Any,
        > readJunction(
        storage: EdgeStorage.Junction<Source, Target, JunctionEntity, SourceKey, TargetKey>,
        sourceKeys: List<SourceKey>,
        context: RelationshipReadContext,
    ): JunctionRead<SourceKey, TargetKey> {
        val junctionQuery = queryCompiler.compileJunction(
            entity = storage.junctionEntity,
            context = context,
            structuralPredicates = listOf(
                Predicate.Leaf(storage.sourceColumn, Op.IN, sourceKeys),
            ),
        )
        if (sourceKeys.isEmpty()) {
            return JunctionRead(emptyList(), emptyList())
        }
        val rows = driver.query(
            junctionQuery.table,
            junctionQuery.predicates,
            junctionQuery.orderBy,
            null,
            null,
        )
        val targetKeys = rows.map { row ->
            row[storage.targetColumn]?.let { targetKey -> targetKey as TargetKey }
        }.distinct()
        val memberships = rows.mapNotNull { row ->
            val sourceKey = row[storage.sourceColumn] ?: return@mapNotNull null
            val targetKey = row[storage.targetColumn] ?: return@mapNotNull null
            JunctionMembership(sourceKey as SourceKey, targetKey as TargetKey)
        }.distinct()
        return JunctionRead(targetKeys, memberships)
    }

    private fun <SourceKey : Any, TargetKey : Any, Target : EntEntity<*>> groupTargetsBySource(
        memberships: List<JunctionMembership<SourceKey, TargetKey>>,
        targetsInQueryOrder: List<Target>,
        targetKey: (Target) -> TargetKey,
    ): Map<SourceKey, List<Target>> {
        val sourceKeysByTarget = linkedMapOf<TargetKey, MutableSet<SourceKey>>()
        for (membership in memberships) {
            sourceKeysByTarget
                .getOrPut(membership.targetKey) { linkedSetOf() }
                .add(membership.sourceKey)
        }

        val targetsBySource = linkedMapOf<SourceKey, MutableList<Target>>()
        for (target in targetsInQueryOrder) {
            val sourceKeys = sourceKeysByTarget[targetKey(target)] ?: continue
            for (sourceKey in sourceKeys) {
                targetsBySource.getOrPut(sourceKey) { mutableListOf() } += target
            }
        }
        return targetsBySource
    }

    private fun <SourceKey : Any, Target : EntEntity<*>> applyPerSourceWindow(
        targetsBySource: Map<SourceKey, List<Target>>,
        query: StorageQuerySpec<Target>,
    ): Map<SourceKey, List<Target>> {
        val offset = query.offset ?: 0
        val limit = query.limit ?: Int.MAX_VALUE
        return targetsBySource.mapValues { (_, targets) ->
            targets.drop(offset).take(limit)
        }
    }

    private fun <SourceKey : Any, Source : EntEntity<*>, Target : EntEntity<*>> attachEvaluatedTargets(
        sources: List<Source>,
        targetsBySource: Map<SourceKey, List<Target>>,
        evaluatedTargets: List<Target>,
        sourceKey: (Source) -> SourceKey,
        edge: ToManyEdgeMapping<Source, Target>,
    ): List<Source> {
        val evaluatedTargetsBySource = replaceWithEvaluatedTargets(
            groups = targetsBySource,
            evaluatedTargets = evaluatedTargets,
        )
        return sources.map { source ->
            edge.attach(source, evaluatedTargetsBySource[sourceKey(source)] ?: emptyList())
        }
    }
}

/** The target keys and source-to-target associations discovered by one batched junction read. */
private data class JunctionRead<SourceKey : Any, TargetKey : Any>(
    /** Distinct target keys used to load the related target entities. */
    val targetKeys: List<TargetKey?>,

    /** Valid junction associations used to group loaded targets by source. */
    val memberships: List<JunctionMembership<SourceKey, TargetKey>>,
)

/** One non-null source-to-target association read from the junction table. */
private data class JunctionMembership<SourceKey : Any, TargetKey : Any>(
    /** Key of the source entity that owns the relationship. */
    val sourceKey: SourceKey,

    /** Key of the target entity related to that source. */
    val targetKey: TargetKey,
)
