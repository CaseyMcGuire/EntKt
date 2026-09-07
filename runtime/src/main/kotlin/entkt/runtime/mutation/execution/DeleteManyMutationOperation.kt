@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.query.Predicate
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.hook.BatchActionHook
import entkt.runtime.hook.runActionHooks
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.QuerySource
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationWriteState
import entkt.runtime.validation.MutationValidationEvaluator
import java.util.concurrent.CancellationException

/** Select, authorize, and persist one atomic DELETE batch with correlated acknowledgements. */
@EntktInternal
class DeleteManyMutationOperation<RuleClient, Entity : EntEntity<*>, Candidate>(
    private val entity: EntityDescriptor<Entity, *>,
    private val converter: DeleteMutationConverter<Entity, Candidate>,
    private val privacyEvaluator:
        MutationPrivacyEvaluator<RuleClient, DeleteRuleCandidate<Entity, Candidate>>,
    private val validationEvaluator:
        MutationValidationEvaluator<RuleClient, DeleteRuleCandidate<Entity, Candidate>>,
    private val readQueryExecutor: ReadQueryExecutor<Entity>,
    private val beforeDelete: List<BatchActionHook<Entity>>,
    private val afterDelete: List<BatchActionHook<Entity>>,
) : MutationOperation<RuleClient, DeleteManyMutationInput<Entity>, Int> {
    override fun requirements(input: DeleteManyMutationInput<Entity>): MutationRequirements =
        MutationRequirements(
            operationName = "${entity.entityName} deleteMany",
            multiWrite = true,
            requiresAtomicTransaction = true,
        )

    override fun run(
        execution: MutationExecution,
        ruleClient: RuleClient,
        input: DeleteManyMutationInput<Entity>,
    ): MutationCompletion<Int> = MutationCompletion.Ready(
        deleteMany(execution, ruleClient, input.viewerContext, input.predicates),
    )

    /** Execute DELETE selection, lifecycle phases, and correlated persistence in an active transaction. */
    private fun deleteMany(
        execution: MutationExecution,
        ruleClient: RuleClient,
        viewerContext: ViewerContext,
        predicates: List<Predicate<Entity>>,
    ): Int {
        val selection = selectMany(execution, viewerContext, predicates)
        val entities = selection.entities.toList()
        if (entities.isEmpty()) return 0

        val candidates = entities.map { entity ->
            DeleteRuleCandidate(entity, converter.toCandidate(entity))
        }
        evaluateDeleteRules(
            execution = execution,
            ruleClient = ruleClient,
            entityName = entity.entityName,
            viewerContext = viewerContext,
            candidates = candidates,
            privacyEvaluator = privacyEvaluator,
            validationEvaluator = validationEvaluator,
        )
        runActionHooks(entities, beforeDelete)

        val approvedIds = entities.map { it.id }
        execution.markWritePending()
        val deletedIds = try {
            execution.driver.deleteManyByIds(
                table = entity.table,
                idColumn = entity.idColumn,
                ids = approvedIds,
                predicates = selection.effectivePredicates,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = classifyDriverFailure(
                execution,
                e,
                MutationWriteState.TransactionPending,
            )
            val reported = if (
                !execution.isOwnedTransaction &&
                approvedIds.size > 1 &&
                classified.writeState == MutationWriteState.NotPersisted
            ) {
                EntUnexpectedMutationException(MutationWriteState.TransactionPending, classified)
            } else {
                classified
            }
            execution.reject(reported)
        }

        val deletedIdSnapshot = deletedIds.toList()
        val approvedIdSet = approvedIds.toSet()
        val deletedIdSet = deletedIdSnapshot.toSet()
        check(
            deletedIdSnapshot.size == deletedIdSet.size &&
                deletedIdSet.all { it in approvedIdSet },
        ) {
            "DatabaseDriver.deleteManyByIds returned duplicate or unapproved IDs"
        }
        val deletedEntities = entities.filter { it.id in deletedIdSet }
        check(deletedEntities.size == deletedIdSnapshot.size) {
            "DatabaseDriver.deleteManyByIds acknowledgement could not be correlated to candidates"
        }
        runActionHooks(deletedEntities, afterDelete)
        return deletedIdSnapshot.size
    }

    /** Compile and execute one raw DELETE_CANDIDATES query without applying LOAD privacy. */
    private fun selectMany(
        execution: MutationExecution,
        viewerContext: ViewerContext,
        predicates: List<Predicate<Entity>>,
    ): DeleteSelection<Entity> {
        val query = EntityQuery(
            entity = entity,
            source = QuerySource.Root(),
            predicates = predicates,
            orderBy = emptyList(),
            limit = null,
            offset = null,
            edges = emptyList(),
        )
        val querySpec = readQueryExecutor.compileEntityQuery(
            viewerContext = viewerContext,
            query = query,
            operation = ReadOperation.DELETE_CANDIDATES,
        )
        val effectivePredicates = querySpec.predicates.toList()
        val rows = execution.driver.query(
            entity.table,
            effectivePredicates,
            emptyList(),
            null,
            null,
        )
        return DeleteSelection(
            entities = rows.map(entity::decode),
            effectivePredicates = effectivePredicates,
        )
    }

    private fun classifyDriverFailure(
        execution: MutationExecution,
        exception: Exception,
        fallback: MutationWriteState,
    ) = execution.driver.classifyMutationException(
        exception,
        entity.entityName,
        EntOperation.DELETE,
    ) ?: EntUnexpectedMutationException(fallback, exception)
}
