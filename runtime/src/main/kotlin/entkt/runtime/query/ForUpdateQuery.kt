@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.ReadResult
import entkt.runtime.result.ReadCollectionResult

/**
 * A completed query whose terminals lock root rows until transaction commit or rollback.
 *
 * Construction performs no I/O and opens no transaction. Terminals require an active
 * transaction-scoped client and execute fresh reads on that original binding. LOAD privacy
 * and selected-edge reads follow the normal read pipeline. A denial
 * does not release acquired locks. Native database pagination and locking semantics apply.
 */
class ForUpdateQuery<Entity : EntEntity<*>> private constructor(
    private val query: EntityQuery<Entity>,
    private val executor: ReadQueryExecutor<Entity>?,
    private val lockMode: QueryLockMode,
) {
    @EntktInternal
    constructor(query: EntityQuery<Entity>, executor: ReadQueryExecutor<Entity>?) :
        this(query, executor, QueryLockMode.ForUpdate)

    /**
     * Return a new query that skips rows whose locks cannot be acquired immediately.
     *
     * Performs no I/O and leaves this query unchanged. Terminals still require an active
     * transaction and driver support. Empty results can mean all matching rows are locked;
     * LOAD privacy is unchanged. This does not prevent waits on table-level locks.
     */
    fun skipLocked(): ForUpdateQuery<Entity> =
        ForUpdateQuery(query, executor, QueryLockMode.ForUpdateSkipLocked)

    fun all(viewerContext: ViewerContext): ReadCollectionResult<Entity> {
        val boundExecutor = executor
            ?: return ReadCollectionResult.failedForInternalUse(missingExecutionHostException())
        return boundExecutor.readMany(
            viewerContext = viewerContext,
            captureQuery = { query },
            lockMode = lockMode,
        )
    }

    fun firstOrNull(viewerContext: ViewerContext): ReadResult<Entity?> {
        val boundExecutor = executor
            ?: return ReadResult.failedForInternalUse(missingExecutionHostException())
        return boundExecutor.readOne(
            viewerContext = viewerContext,
            captureQuery = { query },
            lockMode = lockMode,
        )
    }

    private fun missingExecutionHostException(): IllegalStateException =
        IllegalStateException("${query.entity.entityName} query requires a client for privacy enforcement")
}
