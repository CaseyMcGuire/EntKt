@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.query.EntInterceptorsConfig
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.StorageQuerySpec
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireNoSelectedEdges
import entkt.runtime.result.ReadResult
import java.util.concurrent.CancellationException

/**
 * Runs every terminal over a captured entity query.
 *
 * This is the single runtime entry point generated queries construct. It owns the
 * shared query compilation, entity-graph loading, and raw-terminal lifecycles so generated code
 * does not assemble or coordinate read execution itself.
 */
@EntktInternal
class ReadQueryExecutor<Entity : EntEntity<*>>(
    private val driver: DatabaseDriver,
    private val readExecutionGuard: () -> Unit,
    registeredInterceptorsProvider: () -> EntInterceptorsConfig,
    loadPrivacyEvaluatorProvider: () -> LoadPrivacyEvaluator,
) {
    private val queryCompiler = ReadQueryCompiler(
        driver = driver,
        registeredInterceptorsProvider = registeredInterceptorsProvider,
    )

    private val entityGraphLoader = EntityGraphLoader(
        storage = DatabaseGraphStorage(driver, queryCompiler),
        loadPrivacyEvaluatorProvider = loadPrivacyEvaluatorProvider,
    )

    /** Load root entities, authorize them, and recursively load their selected edges. */
    fun readRootQuery(
        viewerContext: ViewerContext,
        captureQuery: () -> EntityQuery<Entity>,
        operation: ReadOperation,
        maximumRows: Int?,
    ): ReadResult<List<Entity>> {
        require(maximumRows == null || maximumRows >= 0) {
            "Root query maximum rows must be non-negative"
        }
        return captureFailure {
            val query = captureQuery()
            readExecutionGuard()
            entityGraphLoader.load(
                query = query,
                operation = operation,
                maximumRows = maximumRows,
                viewerContext = viewerContext,
            )
        }
    }

    /** Compile a captured entity query for a framework-owned storage operation. */
    fun compileEntityQuery(
        viewerContext: ViewerContext,
        query: EntityQuery<Entity>,
        operation: ReadOperation,
    ): StorageQuerySpec<Entity> {
        readExecutionGuard()
        return queryCompiler.compile(query, operation, viewerContext)
    }

    /** Count matching storage rows without evaluating LOAD privacy. */
    fun rawCount(
        viewerContext: ViewerContext,
        captureQuery: () -> EntityQuery<Entity>,
    ): ReadResult<Long> = captureFailure {
        val query = captureQuery()
        query.requireNoSelectedEdges("rawCount()", NON_ENTITY_TERMINAL_EDGE_REASON)
        readExecutionGuard()
        val compiledQuery = compileRawQuery(viewerContext, query, ReadOperation.RAW_COUNT)
        driver.count(compiledQuery.table, compiledQuery.predicates)
    }

    /** Test whether the caller's storage window contains at least one row. */
    fun rawExists(
        viewerContext: ViewerContext,
        captureQuery: () -> EntityQuery<Entity>,
    ): ReadResult<Boolean> = captureFailure {
        val query = captureQuery()
        query.requireNoSelectedEdges("rawExists()", NON_ENTITY_TERMINAL_EDGE_REASON)
        readExecutionGuard()
        val compiledQuery = compileRawQuery(viewerContext, query, ReadOperation.RAW_EXISTS)
        val limit = minOf(1, compiledQuery.limit ?: 1)
        driver.query(
            compiledQuery.table,
            compiledQuery.predicates,
            emptyList(),
            limit,
            compiledQuery.offset,
        ).isNotEmpty()
    }

    /** Execute one raw aggregate, optionally grouped by one storage column. */
    fun <Value> rawAggregate(
        viewerContext: ViewerContext,
        captureQuery: () -> EntityQuery<Entity>,
        terminal: String,
        function: AggregateFunction,
        column: String?,
        groupBy: String?,
        transform: (List<AggregateResultRow>) -> Value,
    ): ReadResult<Value> = captureFailure {
        val query = captureQuery()
        query.requireNoSelectedEdges("$terminal()", NON_ENTITY_TERMINAL_EDGE_REASON)
        readExecutionGuard()
        val compiledQuery = compileRawQuery(viewerContext, query, ReadOperation.RAW_AGGREGATE)
        transform(
            driver.aggregate(
                compiledQuery.table,
                function,
                column,
                compiledQuery.predicates,
                groupBy,
            ),
        )
    }

    private fun compileRawQuery(
        viewerContext: ViewerContext,
        query: EntityQuery<Entity>,
        operation: ReadOperation,
    ): StorageQuerySpec<Entity> = queryCompiler.compile(
        query,
        operation,
        viewerContext,
    )

    private inline fun <Value> captureFailure(block: () -> Value): ReadResult<Value> = try {
        ReadResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReadResult.failedForInternalUse(e)
    }

    private companion object {
        const val NON_ENTITY_TERMINAL_EDGE_REASON =
            "this terminal does not return entities and cannot expose loaded edges; " +
                "use an entity terminal such as all() or firstOrNull(), or remove the load calls"
    }
}
