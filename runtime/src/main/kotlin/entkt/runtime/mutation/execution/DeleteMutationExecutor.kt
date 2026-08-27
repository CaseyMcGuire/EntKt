@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.hook.runBatchHooksForInternalUse
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.toValidationViolation
import java.util.concurrent.CancellationException

/** Executes scalar and phase-major bulk DELETE lifecycles. */
@EntktInternal
class DeleteMutationExecutor<RuleClient>(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val ruleClient: RuleClient,
) {
    private val execution = MutationExecutionSupport(mutationRuntime)

    /** Delete by an entity handle, projecting the affected-row signal to Unit. */
    fun <Entity : EntEntity<*>, Candidate> delete(
        viewerContext: ViewerContext,
        entity: Entity,
        spec: DeleteMutationSpec<Entity, Candidate, RuleClient>,
    ): MutationResult<Unit> = when (
        val result = deleteById(viewerContext, entity.id, spec)
    ) {
        is MutationResult.Success -> MutationResult.Success(Unit)
        is MutationResult.Failed -> result
    }

    /** Reload and idempotently delete one current row. */
    fun <Entity : EntEntity<*>, Candidate> deleteById(
        viewerContext: ViewerContext,
        id: Any,
        spec: DeleteMutationSpec<Entity, Candidate, RuleClient>,
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

    /** Enforce transaction policy before deleteMany chooses or opens its transaction. */
    fun checkDeleteManyTransactionRequirement(entityName: String) {
        mutationRuntime.checkTransactionRequirement(
            operation = "$entityName deleteMany",
            multiWrite = true,
        )
    }

    /** Execute DELETE selection, lifecycle phases, and correlated persistence in an active transaction. */
    fun <Entity : EntEntity<*>, Candidate> deleteMany(
        viewerContext: ViewerContext,
        predicates: List<Predicate<Entity>>,
        spec: DeleteMutationSpec<Entity, Candidate, RuleClient>,
        promoteDriverNotPersisted: Boolean,
    ): MutationResult<Int> = execution.execute { attempt ->
        check(driver.inTransaction) { "deleteMany phases require a transaction-scoped driver" }
        val selection = spec.selectMany.select(viewerContext, predicates)
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

    private fun <Entity : EntEntity<*>, Candidate> deleteLoaded(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        entity: Entity,
        spec: DeleteMutationSpec<Entity, Candidate, RuleClient>,
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

    private fun <Entity : EntEntity<*>, Candidate> evaluatePrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        candidates: List<DeleteRuleCandidate<Entity, Candidate>>,
        spec: DeleteMutationSpec<Entity, Candidate, RuleClient>,
    ) {
        val decisions = spec.privacy.evaluate(viewerContext, ruleClient, candidates)
        check(decisions.size == candidates.size) {
            "DELETE privacy returned ${decisions.size} decisions for ${candidates.size} candidates"
        }
        val deniedIndex = decisions.indexOfFirst { it !is PrivacyDecision.Allow }
        if (deniedIndex < 0) return
        val reason = when (val decision = decisions[deniedIndex]) {
            PrivacyDecision.Allow -> error("allowed DELETE candidate selected as denied")
            is PrivacyDecision.Deny -> decision.reason
            PrivacyDecision.Continue -> "no delete rule allowed access"
        }
        val entity = candidates[deniedIndex].entity
        attempt.reject(
            EntMutationPrivacyDeniedException(
                writeState = MutationWriteState.NotPersisted,
                entityType = spec.entity.entityName,
                operation = EntOperation.DELETE,
                entityKey = EntityKey("id", entity.id),
                reason = reason,
            ),
        )
    }

    private fun <Entity : EntEntity<*>, Candidate> evaluateValidation(
        attempt: MutationAttempt,
        candidates: List<DeleteRuleCandidate<Entity, Candidate>>,
        spec: DeleteMutationSpec<Entity, Candidate, RuleClient>,
    ) {
        val invalidsByCandidate = spec.validation.evaluate(ruleClient, candidates)
        check(invalidsByCandidate.size == candidates.size) {
            "DELETE validation returned ${invalidsByCandidate.size} results for " +
                "${candidates.size} candidates"
        }
        invalidsByCandidate.firstOrNull { it.isNotEmpty() }?.let { invalids ->
            attempt.reject(
                EntValidationException(
                    entityType = spec.entity.entityName,
                    operation = EntOperation.DELETE,
                    violations = invalids.map { it.toValidationViolation() },
                ),
            )
        }
    }

    private fun <Entity : EntEntity<*>, Candidate> classifyDriverFailure(
        exception: Exception,
        spec: DeleteMutationSpec<Entity, Candidate, RuleClient>,
        fallback: MutationWriteState,
    ) = driver.classifyMutationException(
        exception,
        spec.entity.entityName,
        EntOperation.DELETE,
    ) ?: EntUnexpectedMutationException(fallback, exception)
}
