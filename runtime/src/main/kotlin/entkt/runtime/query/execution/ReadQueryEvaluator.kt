@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.query.EntInterceptorsConfig
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.FrozenQuerySpec
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireNoSelectedEdges
import entkt.runtime.result.ReadResult
import java.util.concurrent.CancellationException

/**
 * Runs every terminal over a captured entity query.
 *
 * This is the single runtime entry point generated queries construct. It owns the
 * shared preparation, entity-graph loading, and raw-terminal lifecycles so generated code
 * does not assemble or coordinate read execution itself.
 */
@EntktInternal
class ReadQueryEvaluator<Entity : EntEntity<*>>(
    private val driver: DatabaseDriver,
    private val privacyContextProvider: () -> PrivacyContext,
    registeredInterceptorsProvider: () -> EntInterceptorsConfig,
    loadPrivacyEvaluatorProvider: () -> LoadPrivacyEvaluator,
) {
    private val queryPreparation = EntityQueryPreparation(
        driver = driver,
        registeredInterceptors = registeredInterceptorsProvider,
    )

    private val graphLoader = GraphLoader<Entity>(
        driver = driver,
        privacyContextProvider = privacyContextProvider,
        queryPreparation = queryPreparation,
        loadPrivacyEvaluator = loadPrivacyEvaluatorProvider,
    )

    /** Load root entities, authorize them, and recursively load their selected edges. */
    fun readRootQuery(
        captureQuery: () -> EntityQuery<Entity>,
        operation: ReadOperation,
        maximumRows: Int?,
    ): ReadResult<List<Entity>> = graphLoader.readRootQuery(
        captureQuery = captureQuery,
        operation = operation,
        maximumRows = maximumRows,
    )

    /** Prepare a captured entity query for a framework-owned storage operation. */
    fun prepareEntityQuery(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        privacyContext: PrivacyContext,
    ): FrozenQuerySpec<Entity> = queryPreparation.prepare(
        query,
        operation,
        privacyContext,
    )

    /** Count matching storage rows without evaluating LOAD privacy. */
    fun rawCount(
        captureQuery: () -> EntityQuery<Entity>,
    ): ReadResult<Long> = captureFailure {
        val query = captureQuery()
        query.requireNoSelectedEdges("rawCount()", NON_ENTITY_TERMINAL_EDGE_REASON)
        val preparedQuery = prepareRawQuery(query, ReadOperation.RAW_COUNT)
        driver.count(preparedQuery.table, preparedQuery.predicates)
    }

    /** Test whether the caller's storage window contains at least one row. */
    fun rawExists(
        captureQuery: () -> EntityQuery<Entity>,
    ): ReadResult<Boolean> = captureFailure {
        val query = captureQuery()
        query.requireNoSelectedEdges("rawExists()", NON_ENTITY_TERMINAL_EDGE_REASON)
        val preparedQuery = prepareRawQuery(query, ReadOperation.RAW_EXISTS)
        val limit = minOf(1, preparedQuery.limit ?: 1)
        driver.query(
            preparedQuery.table,
            preparedQuery.predicates,
            emptyList(),
            limit,
            preparedQuery.offset,
        ).isNotEmpty()
    }

    /** Execute one raw aggregate, optionally grouped by one storage column. */
    fun <Value> rawAggregate(
        captureQuery: () -> EntityQuery<Entity>,
        terminal: String,
        function: AggregateFunction,
        column: String?,
        groupBy: String?,
        transform: (List<AggregateResultRow>) -> Value,
    ): ReadResult<Value> = captureFailure {
        val query = captureQuery()
        query.requireNoSelectedEdges("$terminal()", NON_ENTITY_TERMINAL_EDGE_REASON)
        val preparedQuery = prepareRawQuery(query, ReadOperation.RAW_AGGREGATE)
        transform(
            driver.aggregate(
                preparedQuery.table,
                function,
                column,
                preparedQuery.predicates,
                groupBy,
            ),
        )
    }

    private fun prepareRawQuery(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
    ): FrozenQuerySpec<Entity> = queryPreparation.prepare(
        query,
        operation,
        privacyContextProvider(),
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
