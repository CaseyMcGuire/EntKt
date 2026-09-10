@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.minimumBindParameters
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.ReadResult

/**
 * Immutable configuration and reusable execution behavior for generated entity queries.
 *
 * Fluent configuration returns a new query. Statement-style DSL blocks use [EntityQueryScope]
 * instead. Query values may share their owned configuration, but their bound execution host
 * retains its transaction-lifetime and concurrency restrictions.
 */
abstract class EntityQueryBuilder<
    Entity : EntEntity<*>,
    Self : EntityQueryBuilder<Entity, Self>,
> @EntktInternal protected constructor(
    protected val driver: DatabaseDriver,
    private val executionHost: ReadQueryExecutionHost?,
    protected val entityQuery: EntityQuery<Entity>,
) {
    /** Construct the concrete generated query over an already-owned description. */
    protected abstract fun newQuery(query: EntityQuery<Entity>): Self

    /** Caller-authored row limit, or `null` when unbounded. */
    val queryLimit: Int? get() = entityQuery.limit

    /** Caller-authored row offset, or `null` when absent. */
    val queryOffset: Int? get() = entityQuery.offset

    protected val readQueryExecutor: ReadQueryExecutor<Entity>? by lazy(LazyThreadSafetyMode.NONE) {
        executionHost?.let { host ->
            ReadQueryExecutor(
                driver = driver,
                executionHost = host,
            )
        }
    }

    fun where(predicate: Predicate<Entity>): Self = whereAllForInternalUse(listOf(predicate))

    /** Append framework-supplied predicates, checking capacity before detaching their operands. */
    @EntktInternal
    fun whereAllForInternalUse(predicates: List<Predicate<Entity>>): Self {
        checkBindCapacity(predicates)
        return newQuery(entityQuery.configured(addedPredicates = predicates))
    }

    /** Append one ordering term. */
    fun orderBy(field: OrderField<Entity>): Self {
        return newQuery(entityQuery.configured(addedOrder = listOf(field)))
    }

    /** Limit this query to at most [n] rows. */
    fun limit(n: Int): Self {
        require(n >= 0) { "limit must be non-negative; was $n" }
        return newQuery(entityQuery.configured(limit = n))
    }

    /** Skip the first [n] rows. */
    fun offset(n: Int): Self {
        require(n >= 0) { "offset must be non-negative; was $n" }
        return newQuery(entityQuery.configured(offset = n))
    }

    /** Return the owned description, adding detached framework constraints when required. */
    @EntktInternal
    fun captureEntityQuery(
        structuralPredicates: List<Predicate<Entity>> = emptyList(),
    ): EntityQuery<Entity> {
        checkBindCapacity(structuralPredicates)
        return entityQuery.withStructuralPredicates(structuralPredicates)
    }

    protected fun <Scope : EntityQueryScope<Entity, Scope>> configureQuery(
        scope: Scope,
        block: Scope.() -> Unit,
    ): Self = newQuery(scope.apply(block).buildForInternalUse())

    /** Traversal changes the root but keeps the immutable source description. */
    protected fun <Target : EntEntity<*>> traversalQuery(
        edge: EdgeMapping<Entity, Target>,
        operation: String,
    ): EntityQuery<Target> {
        entityQuery.requireNoSelectedEdges(
            operation,
            "traversal changes the result root and cannot carry the source query's selected " +
                "graph; traverse first and select edge loads on the target query, or " +
                "materialize the source graph with an entity terminal",
        )
        return EntityQuery(edge.target, QuerySource.Traversal(entityQuery, edge))
    }

    private fun checkBindCapacity(additionalPredicates: List<Predicate<Entity>>) {
        driver.requireBindCapacity(
            minimumBindParameters(entityQuery.predicates) +
                minimumBindParameters(entityQuery.structuralPredicates) +
                minimumBindParameters(additionalPredicates),
            entityQuery.entity.table,
        )
    }

    /** Execute a framework-owned root read over this builder's captured shape. */
    @EntktInternal
    fun readRootQuery(
        viewerContext: ViewerContext,
        operation: ReadOperation,
        maximumRows: Int?,
        structuralPredicates: List<Predicate<Entity>> = emptyList(),
    ): ReadResult<List<Entity>> {
        val executor = readQueryExecutor
            ?: return ReadResult.failedForInternalUse(missingExecutionHostException())
        return executor.readRootQuery(
            viewerContext = viewerContext,
            captureQuery = { captureEntityQuery(structuralPredicates) },
            operation = operation,
            maximumRows = maximumRows,
        )
    }

    /** Compile this builder's captured shape for a framework-owned storage operation. */
    @EntktInternal
    fun compileEntityQuery(
        viewerContext: ViewerContext,
        operation: ReadOperation,
    ): StorageQuerySpec<Entity> = requireReadQueryExecutor().compileEntityQuery(
        viewerContext = viewerContext,
        query = captureEntityQuery(),
        operation = operation,
    )

    /** Execute this query and return every selected root row. */
    fun all(viewerContext: ViewerContext): ReadResult<List<Entity>> =
        readRootQuery(viewerContext, ReadOperation.ALL, maximumRows = null)

    /** Execute at most one root row, preserving absence as a successful `null`. */
    fun firstOrNull(viewerContext: ViewerContext): ReadResult<Entity?> =
        when (
            val result = readRootQuery(
                viewerContext,
                ReadOperation.FIRST,
                maximumRows = 1,
            )
        ) {
            is ReadResult.Success -> ReadResult.Success(result.value.firstOrNull())
            is ReadResult.Failed -> result
        }

    private fun requireReadQueryExecutor(): ReadQueryExecutor<Entity> = readQueryExecutor
        ?: throw missingExecutionHostException()

    private fun missingExecutionHostException(): IllegalStateException =
        IllegalStateException("${entityQuery.entity.entityName} query requires a client for privacy enforcement")
}
