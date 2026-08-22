@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireNoSelectedEdges
import entkt.runtime.result.ReadResult
import java.util.concurrent.CancellationException

/** Executes root-query terminals that do not materialize an entity graph. */
@EntktInternal
class QueryTerminalExecutor<Entity : EntEntity<*>>(
    private val driver: DatabaseDriver,
    /**
     * Resolves the privacy context for each terminal execution. The executor is retained
     * by its generated query, so storing a context directly would freeze the viewer from
     * construction time instead of using the viewer current when the terminal runs.
     */
    private val privacyContextProvider: () -> PrivacyContext,
    private val queryPreparation: EntityQueryPreparation,
) {
    /** Count matching storage rows without evaluating LOAD privacy. */
    fun rawCount(
        captureQuery: () -> EntityQuery<Entity>,
    ): ReadResult<Long> = captureFailure {
        val query = captureQuery()
        query.requireNoSelectedEdges("rawCount()", NON_ENTITY_TERMINAL_EDGE_REASON)
        val prepared = prepare(query, ReadOperation.RAW_COUNT)
        driver.count(prepared.table, prepared.predicates)
    }

    /** Test whether the caller's storage window contains at least one row. */
    fun rawExists(
        captureQuery: () -> EntityQuery<Entity>,
    ): ReadResult<Boolean> = captureFailure {
        val query = captureQuery()
        query.requireNoSelectedEdges("rawExists()", NON_ENTITY_TERMINAL_EDGE_REASON)
        val prepared = prepare(query, ReadOperation.RAW_EXISTS)
        val limit = minOf(1, prepared.limit ?: 1)
        driver.query(
            prepared.table,
            prepared.predicates,
            emptyList(),
            limit,
            prepared.offset,
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
        val prepared = prepare(query, ReadOperation.RAW_AGGREGATE)
        transform(
            driver.aggregate(
                prepared.table,
                function,
                column,
                prepared.predicates,
                groupBy,
            ),
        )
    }

    private fun prepare(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
    ) = queryPreparation.prepare(
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
