@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.StorageQuerySpec
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
        loadPrivacyEvaluator = executionHost,
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
            executionHost.checkReadExecution()
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
        executionHost.checkReadExecution()
        return queryCompiler.compile(query, operation, viewerContext)
    }

    private inline fun <Value> captureFailure(block: () -> Value): ReadResult<Value> = try {
        ReadResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReadResult.failedForInternalUse(e)
    }
}
