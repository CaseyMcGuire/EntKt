@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.EdgePredicateScope
import entkt.query.EdgeQuery
import entkt.query.EntktInternal
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.ReadResult

/**
 * Reusable state and execution behavior for generated entity query builders.
 *
 * A query builder is mutable and not thread-safe. Configure and execute one instance from one
 * thread at a time; create separate builders for concurrent operations. Generated subclasses add
 * only schema-specific mappings, selected-edge capture, and traversal members.
 */
abstract class EntityQueryBuilder<
    Entity : EntEntity<*>,
    Self : EntityQueryBuilder<Entity, Self>,
> @EntktInternal protected constructor(
    protected val driver: DatabaseDriver,
    private val executionHost: ReadQueryExecutionHost?,
    private val entityName: String,
) : EdgeQuery<Entity>, EdgePredicateScope<Entity> {
    /** Concrete generated builder returned from fluent configuration methods. */
    protected abstract val self: Self

    /** Caller-authored predicates in declaration order. */
    protected var predicates: List<Predicate<Entity>> = emptyList()
        private set

    /** Caller-authored ordering terms in declaration order. */
    protected var orderFields: List<OrderField<Entity>> = emptyList()
        private set

    /** Caller-authored row limit, or `null` when unbounded. */
    var queryLimit: Int? = null
        private set

    /** Caller-authored row offset, or `null` when absent. */
    var queryOffset: Int? = null
        private set

    private val readQueryExecutor: ReadQueryExecutor<Entity> by lazy(LazyThreadSafetyMode.NONE) {
        val host = requireExecutionHost()
        ReadQueryExecutor(
            driver = driver,
            readExecutionGuard = { host.checkReadExecution() },
            registeredInterceptorsProvider = { host.entityInterceptors },
            loadPrivacyEvaluator = host,
        )
    }

    final override fun where(predicate: Predicate<Entity>): Self {
        predicates = predicates + predicate
        return self
    }

    /** Append one ordering term. */
    fun orderBy(field: OrderField<Entity>): Self {
        orderFields = orderFields + field
        return self
    }

    /** Limit this query to at most [n] rows. */
    fun limit(n: Int): Self {
        require(n >= 0) { "limit must be non-negative; was $n" }
        queryLimit = n
        return self
    }

    /** Skip the first [n] rows. */
    fun offset(n: Int): Self {
        require(n >= 0) { "offset must be non-negative; was $n" }
        queryOffset = n
        return self
    }

    final override fun combinedPredicate(): Predicate<Entity>? =
        predicates.reduceOrNull { accumulated, predicate ->
            Predicate.And(accumulated, predicate)
        }

    /** Capture this builder and any selected edges as an immutable recursive query. */
    @EntktInternal
    abstract fun captureEntityQuery(
        structuralPredicates: List<Predicate<Entity>> = emptyList(),
    ): EntityQuery<Entity>

    /** Execute a framework-owned root read over this builder's captured shape. */
    @EntktInternal
    fun readRootQuery(
        viewerContext: ViewerContext,
        operation: ReadOperation,
        maximumRows: Int?,
        structuralPredicates: List<Predicate<Entity>> = emptyList(),
    ): ReadResult<List<Entity>> = readQueryExecutor.readRootQuery(
        viewerContext = viewerContext,
        captureQuery = { captureEntityQuery(structuralPredicates) },
        operation = operation,
        maximumRows = maximumRows,
    )

    /** Compile this builder's captured shape for a framework-owned storage operation. */
    @EntktInternal
    fun compileEntityQuery(
        viewerContext: ViewerContext,
        operation: ReadOperation,
    ): StorageQuerySpec<Entity> = readQueryExecutor.compileEntityQuery(
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

    private fun requireExecutionHost(): ReadQueryExecutionHost = executionHost
        ?: error("$entityName query requires a client for privacy enforcement")
}
