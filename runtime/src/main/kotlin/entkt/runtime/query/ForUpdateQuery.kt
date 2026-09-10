@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.ReadResult

/**
 * A completed query whose terminals lock root rows until transaction commit or rollback.
 *
 * Construction performs no I/O and opens no transaction. Terminals require an active
 * transaction-scoped client and execute fresh reads on that original binding. LOAD privacy
 * and selected-edge reads follow the normal read pipeline. A denial
 * does not release acquired locks. Native database pagination and locking semantics apply.
 */
class ForUpdateQuery<Entity : EntEntity<*>> @EntktInternal constructor(
    private val query: EntityQuery<Entity>,
    private val executor: ReadQueryExecutor<Entity>?,
) {
    fun all(viewerContext: ViewerContext): ReadResult<List<Entity>> =
        read(viewerContext, ReadOperation.ALL, maximumRows = null)

    fun firstOrNull(viewerContext: ViewerContext): ReadResult<Entity?> =
        when (val result = read(viewerContext, ReadOperation.FIRST, maximumRows = 1)) {
            is ReadResult.Success -> ReadResult.Success(result.value.firstOrNull())
            is ReadResult.Failed -> result
        }

    private fun read(
        viewerContext: ViewerContext,
        operation: ReadOperation,
        maximumRows: Int?,
    ): ReadResult<List<Entity>> {
        val boundExecutor = executor ?: return ReadResult.failedForInternalUse(
            IllegalStateException("${query.entity.entityName} query requires a client for privacy enforcement"),
        )
        return boundExecutor.readRootQuery(
            viewerContext = viewerContext,
            captureQuery = { query },
            operation = operation,
            maximumRows = maximumRows,
            lockMode = QueryLockMode.ForUpdate,
        )
    }
}
