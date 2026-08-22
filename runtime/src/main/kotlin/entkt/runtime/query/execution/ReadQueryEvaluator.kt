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
import entkt.runtime.result.ReadResult

/**
 * Runs every terminal over a captured entity query.
 *
 * This is the single runtime entry point generated queries construct. It owns the
 * shared preparation, entity-graph loading, and raw-terminal collaborators so generated
 * code does not assemble or coordinate the read lifecycle itself.
 */
@EntktInternal
class ReadQueryEvaluator<Entity : EntEntity<*>>(
    driver: DatabaseDriver,
    privacyContextProvider: () -> PrivacyContext,
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

    private val queryTerminalExecutor = QueryTerminalExecutor<Entity>(
        driver = driver,
        privacyContextProvider = privacyContextProvider,
        queryPreparation = queryPreparation,
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
    ): ReadResult<Long> = queryTerminalExecutor.rawCount(captureQuery)

    /** Test whether the caller's storage window contains at least one row. */
    fun rawExists(
        captureQuery: () -> EntityQuery<Entity>,
    ): ReadResult<Boolean> = queryTerminalExecutor.rawExists(captureQuery)

    /** Execute one raw aggregate, optionally grouped by one storage column. */
    fun <Value> rawAggregate(
        captureQuery: () -> EntityQuery<Entity>,
        terminal: String,
        function: AggregateFunction,
        column: String?,
        groupBy: String?,
        transform: (List<AggregateResultRow>) -> Value,
    ): ReadResult<Value> = queryTerminalExecutor.rawAggregate(
        captureQuery = captureQuery,
        terminal = terminal,
        function = function,
        column = column,
        groupBy = groupBy,
        transform = transform,
    )
}
