package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.FrozenQuerySpec
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ReadResult
import java.util.concurrent.CancellationException

/** Executes the complete root-and-selected-subgraph read lifecycle. */
@EntktInternal
class GraphLoader<
    Entity : EntEntity<*>,
>(
    driver: DatabaseDriver,
    /**
     * Resolves the privacy context when a read begins. A generated query retains its
     * loader across executions, so capturing a context when the loader is constructed
     * would permanently bind later reads to that earlier viewer. Each read invokes this
     * provider exactly once and carries the resulting context through its full lifecycle.
     */
    private val privacyContextProvider: () -> PrivacyContext,
    private val queryPreparation: EntityQueryPreparation,
    private val loadPrivacyEvaluator: () -> LoadPrivacyEvaluator,
    private val entityLoader: EntityLoader = EntityLoader(driver),
    private val subgraphLoader: SubgraphLoader = SubgraphLoader(
        driver,
        queryPreparation,
        entityLoader,
        loadPrivacyEvaluator,
    ),
) {
    /** Execute the root-query lifecycle and return its loaded entity list. */
    fun readRootQuery(
        captureQuery: () -> EntityQuery<Entity>,
        operation: ReadOperation,
        maximumRows: Int?,
    ): ReadResult<List<Entity>> {
        require(maximumRows == null || maximumRows >= 0) {
            "Root query maximum rows must be non-negative"
        }
        return try {
            executeRootQuery(captureQuery(), operation, maximumRows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReadResult.failedForInternalUse(e)
        }
    }

    private fun executeRootQuery(
        query: EntityQuery<Entity>,
        operation: ReadOperation,
        maximumRows: Int?,
    ): ReadResult<List<Entity>> {
        val privacyContext = privacyContextProvider()
        val queryReadyForStorage = queryPreparation.prepare(
            query,
            operation,
            privacyContext,
        )
        val loadedEntities = loadRootEntities(query, queryReadyForStorage, maximumRows)
        val privacyDenials = evaluateRootLoadPrivacy(
            query.entity,
            privacyContext,
            loadedEntities,
        )

        if (privacyDenials.isNotEmpty()) {
            return ReadResult.failedForInternalUse(
                EntPrivacyDeniedException(LoadDenialOrigin.Root, privacyDenials),
            )
        }

        val entitiesWithSelectedEdges = subgraphLoader.load(
            query,
            loadedEntities,
            privacyContext,
        )
        return ReadResult.Success(entitiesWithSelectedEdges)
    }

    private fun loadRootEntities(
        entityQuery: EntityQuery<Entity>,
        executableQuery: FrozenQuerySpec<Entity>,
        maximumRows: Int?,
    ): List<Entity> {
        val storageLimit = maximumRows?.let { maximum ->
            minOf(maximum, executableQuery.limit ?: maximum)
        } ?: executableQuery.limit
        val loadedEntities = entityLoader.load(
            entity = entityQuery.entity,
            query = executableQuery,
            limit = storageLimit,
            offset = executableQuery.offset,
            maximumEntities = maximumRows,
        )
        return loadedEntities
    }

    private fun evaluateRootLoadPrivacy(
        entity: EntityMapping<Entity>,
        privacyContext: PrivacyContext,
        loadedEntities: List<Entity>,
    ): List<PrivacyDenial> {
        val evaluator = loadPrivacyEvaluator()
        if (loadedEntities.isEmpty() || !evaluator.isConfigured(entity)) {
            return emptyList()
        }

        return evaluator
            .evaluate(entity, privacyContext, loadedEntities)
            .filterNotNull()
    }
}
