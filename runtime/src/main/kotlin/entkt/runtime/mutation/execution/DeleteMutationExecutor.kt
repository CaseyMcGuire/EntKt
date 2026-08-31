@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.hook.runBatchHooksForInternalUse
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.toValidationViolation
import entkt.runtime.validation.MutationValidationEvaluator
import java.util.concurrent.CancellationException

/** Executes scalar and phase-major bulk DELETE lifecycles. */
@EntktInternal
class DeleteMutationExecutor<Entity : EntEntity<*>, Candidate>(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val privacyEvaluator:
        MutationPrivacyEvaluator<DeleteRuleCandidate<Entity, Candidate>>,
    private val validationEvaluator:
        MutationValidationEvaluator<DeleteRuleCandidate<Entity, Candidate>>,
) {
    private val execution = MutationExecutionSupport(mutationRuntime)

    /** Bind one generated entity specification to the reusable delete operation. */
    fun operationForInternalUse(
        spec: DeleteMutationSpec<Entity, Candidate>,
        ownedTransaction: (
            ViewerContext,
            List<Predicate<Entity>>,
        ) -> TransactionResult<Int>,
    ): DeleteOperation<Entity> = DeleteOperation(
        driver = driver,
        mutationRuntime = mutationRuntime,
        entityName = spec.entity.entityName,
        executeEntity = { vc, entity -> delete(vc, entity, spec) },
        executeId = { vc, id -> deleteById(vc, id, spec) },
        executeMany = { vc, predicates, promoteDriverNotPersisted ->
            deleteMany(vc, predicates, spec, promoteDriverNotPersisted)
        },
        ownedTransaction = ownedTransaction,
    )

    /** Delete by an entity handle, projecting the affected-row signal to Unit. */
    fun delete(
        viewerContext: ViewerContext,
        entity: Entity,
        spec: DeleteMutationSpec<Entity, Candidate>,
    ): MutationResult<Unit> = when (
        val result = deleteById(viewerContext, entity.id, spec)
    ) {
        is MutationResult.Success -> MutationResult.Success(Unit)
        is MutationResult.Failed -> result
    }

    /** Reload and idempotently delete one current row. */
    fun deleteById(
        viewerContext: ViewerContext,
        id: Any,
        spec: DeleteMutationSpec<Entity, Candidate>,
    ): MutationResult<Boolean> = execution.execute { attempt ->
        mutationRuntime.checkTransactionRequirement("${spec.entity.entityName} delete")
        val row = try {
            driver.byId(spec.entity.table, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attempt.reject(classifyDriverFailure(e, spec, MutationWriteState.NotPersisted))
        }
        if (row == null) return@execute false

        deleteLoaded(
            attempt = attempt,
            viewerContext = viewerContext,
            entity = spec.entity.decode(row),
            spec = spec,
        )
    }

    /** Execute DELETE selection, lifecycle phases, and correlated persistence in an active transaction. */
    fun deleteMany(
        viewerContext: ViewerContext,
        predicates: List<Predicate<Entity>>,
        spec: DeleteMutationSpec<Entity, Candidate>,
        promoteDriverNotPersisted: Boolean,
    ): MutationResult<Int> = execution.execute { attempt ->
        check(driver.inTransaction) { "deleteMany phases require a transaction-scoped driver" }
        val selection = selectMany(viewerContext, predicates, spec)
        val entities = selection.entities.toList()
        if (entities.isEmpty()) return@execute 0

        val candidates = entities.map { entity ->
            DeleteRuleCandidate(entity, spec.candidate(entity))
        }
        evaluatePrivacy(attempt, viewerContext, candidates, spec)
        evaluateValidation(attempt, candidates, spec)
        runBatchHooksForInternalUse(entities, spec.beforeDelete)

        val approvedIds = entities.map { it.id }
        attempt.writeState = MutationWriteState.TransactionPending
        val deletedIds = try {
            driver.deleteManyByIds(
                table = spec.entity.table,
                idColumn = spec.idColumn,
                ids = approvedIds,
                predicates = selection.effectivePredicates,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = classifyDriverFailure(
                e,
                spec,
                MutationWriteState.TransactionPending,
            )
            val reported = if (
                promoteDriverNotPersisted &&
                approvedIds.size > 1 &&
                classified.writeState == MutationWriteState.NotPersisted
            ) {
                EntUnexpectedMutationException(MutationWriteState.TransactionPending, classified)
            } else {
                classified
            }
            attempt.reject(reported)
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
        runBatchHooksForInternalUse(deletedEntities, spec.afterDelete)
        deletedIdSnapshot.size
    }

    /** Compile and execute one raw DELETE_CANDIDATES query without applying LOAD privacy. */
    private fun selectMany(
        viewerContext: ViewerContext,
        predicates: List<Predicate<Entity>>,
        spec: DeleteMutationSpec<Entity, Candidate>,
    ): DeleteSelection<Entity> {
        val query = spec.newQuery()
        for (predicate in predicates) query.where(predicate)
        val querySpec = query.compileEntityQuery(
            viewerContext,
            ReadOperation.DELETE_CANDIDATES,
        )
        val effectivePredicates = querySpec.predicates.toList()
        val rows = driver.query(
            spec.entity.table,
            effectivePredicates,
            emptyList(),
            null,
            null,
        )
        return DeleteSelection(
            entities = rows.map(spec.entity::decode),
            effectivePredicates = effectivePredicates,
        )
    }

    private fun deleteLoaded(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        entity: Entity,
        spec: DeleteMutationSpec<Entity, Candidate>,
    ): Boolean {
        val postWriteState = if (driver.inTransaction) {
            MutationWriteState.TransactionPending
        } else {
            MutationWriteState.Committed
        }
        val candidate = DeleteRuleCandidate(entity, spec.candidate(entity))
        evaluatePrivacy(attempt, viewerContext, listOf(candidate), spec)
        evaluateValidation(attempt, listOf(candidate), spec)
        runBatchHooksForInternalUse(listOf(entity), spec.beforeDelete)

        val deleted = try {
            driver.delete(spec.entity.table, entity.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attempt.reject(classifyDriverFailure(e, spec, MutationWriteState.PersistenceUnknown))
        }
        if (deleted) {
            attempt.writeState = postWriteState
            runBatchHooksForInternalUse(listOf(entity), spec.afterDelete)
        }
        return deleted
    }

    private fun evaluatePrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        candidates: List<DeleteRuleCandidate<Entity, Candidate>>,
        spec: DeleteMutationSpec<Entity, Candidate>,
    ) {
        val denial = privacyEvaluator.evaluate(viewerContext, candidates).firstDeniedOrNull() ?: return
        val entity = denial.subject.entity
        attempt.reject(
            EntMutationPrivacyDeniedException(
                writeState = MutationWriteState.NotPersisted,
                entityType = spec.entity.entityName,
                operation = EntOperation.DELETE,
                entityKey = EntityKey("id", entity.id),
                reason = denial.reason,
            ),
        )
    }

    private fun evaluateValidation(
        attempt: MutationAttempt,
        candidates: List<DeleteRuleCandidate<Entity, Candidate>>,
        spec: DeleteMutationSpec<Entity, Candidate>,
    ) {
        validationEvaluator.evaluate(candidates).firstInvalidOrNull()?.let { invalid ->
            attempt.reject(
                EntValidationException(
                    entityType = spec.entity.entityName,
                    operation = EntOperation.DELETE,
                    violations = invalid.violations.map { it.toValidationViolation() },
                ),
            )
        }
    }

    private fun classifyDriverFailure(
        exception: Exception,
        spec: DeleteMutationSpec<Entity, Candidate>,
        fallback: MutationWriteState,
    ) = driver.classifyMutationException(
        exception,
        spec.entity.entityName,
        EntOperation.DELETE,
    ) ?: EntUnexpectedMutationException(fallback, exception)
}
