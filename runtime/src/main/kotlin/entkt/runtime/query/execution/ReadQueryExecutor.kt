@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.QueryLockMode
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.StorageQuerySpec
import entkt.runtime.result.ReadCollectionResult
import entkt.runtime.result.ReadResult
import java.util.concurrent.CancellationException

/**
 * Runs every terminal over a captured entity query.
 *
 * This is the single runtime entry point generated queries construct. It owns the
 * shared query compilation and entity-graph loading so generated code
 * does not assemble or coordinate read execution itself.
 */
@EntktInternal
class ReadQueryExecutor<Entity : EntEntity<*>>(
    private val driver: DatabaseDriver,
    private val executionHost: ReadQueryExecutionHost,
) {
    private val queryCompiler = ReadQueryCompiler(
        driver = driver,
        registeredInterceptors = executionHost.entityInterceptors,
    )

    private val entityGraphLoader = EntityGraphLoader(
        storage = DatabaseGraphStorage(driver, queryCompiler),
        loadPrivacyDispatcher = executionHost,
    )

    /** Load at most one authorized root and its selected graph, preserving absence as null. */
    fun readOne(
        viewerContext: ViewerContext,
        captureQuery: () -> EntityQuery<Entity>,
        operation: ReadOperation = ReadOperation.FIRST,
        lockMode: QueryLockMode = QueryLockMode.None,
    ): ReadResult<Entity?> = try {
        val query = prepareQuery(captureQuery, operation, lockMode)
        ReadResult.Success(
            entityGraphLoader.loadOne(
                query = query,
                operation = operation,
                viewerContext = viewerContext,
                lockMode = lockMode,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReadResult.failedForInternalUse(e)
    }

    /** Load a root collection, retaining denials and loading only authorized roots' graphs. */
    fun readMany(
        viewerContext: ViewerContext,
        captureQuery: () -> EntityQuery<Entity>,
        lockMode: QueryLockMode = QueryLockMode.None,
    ): ReadCollectionResult<Entity> = try {
        val query = prepareQuery(captureQuery, ReadOperation.ALL, lockMode)
        ReadCollectionResult.Completed(
            entityGraphLoader.loadMany(query, viewerContext, lockMode = lockMode),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReadCollectionResult.failedForInternalUse(e)
    }

    private fun prepareQuery(
        captureQuery: () -> EntityQuery<Entity>,
        operation: ReadOperation,
        lockMode: QueryLockMode,
    ): EntityQuery<Entity> {
        val query = captureQuery()
        executionHost.checkReadExecution()
        checkLockRequirements(query, operation, lockMode)
        return query
    }

    private fun checkLockRequirements(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        lockMode: QueryLockMode,
    ) {
        if (lockMode == QueryLockMode.None) return
        require(operation == ReadOperation.ALL || operation == ReadOperation.FIRST) {
            "Query locking supports only all and firstOrNull"
        }
        if (!driver.inTransaction) {
            throw TransactionRequiredException(
                "${query.entity.entityName} forUpdate requires a transaction-scoped client",
            )
        }
        if (!driver.supportsQueryForUpdate) {
            throw UnsupportedDriverCapabilityException(
                "${query.entity.entityName} forUpdate requires a driver with supportsQueryForUpdate = true",
            )
        }
        if (lockMode == QueryLockMode.ForUpdateSkipLocked && !driver.supportsQuerySkipLocked) {
            throw UnsupportedDriverCapabilityException(
                "${query.entity.entityName} skipLocked requires a driver with supportsQuerySkipLocked = true",
            )
        }
    }

    /** Compile a captured entity query for a framework-owned storage operation. */
    fun compileEntityQuery(
        viewerContext: ViewerContext,
        query: EntityQuery<Entity>,
        operation: ReadOperation,
    ): StorageQuerySpec<Entity> {
        executionHost.checkReadExecution()
        return queryCompiler.compile(query, operation, viewerContext)
    }
}
