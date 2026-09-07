@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.mutation.BeforeSaveHookState
import entkt.runtime.mutation.BeforeUpdateHookState
import entkt.runtime.mutation.PreparedUpdateState
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.mutation.UpdateConsistency
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdateMutationRequest
import entkt.runtime.mutation.UpdatePendingEdges
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntTargetAbsentException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.toValidationViolation
import entkt.runtime.validation.MutationValidationEvaluator
import entkt.runtime.validation.ValidationRuleContext
import java.util.concurrent.CancellationException

/** Runs the reusable UPDATE lifecycle around generated schema adapters. */
@EntktInternal
class UpdateMutationOperation<
    RuleClient,
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges : UpdatePendingEdges<Entity>,
    PreparedState : PreparedUpdateState<Entity>,
    BeforeSaveState : BeforeSaveHookState<Entity>,
    BeforeUpdateState : BeforeUpdateHookState<Entity>,
    >(
    private val entity: EntityMapping<Entity>,
    private val mutationRuntime: MutationRuntime,
    private val privacyEvaluator: MutationPrivacyEvaluator<RuleClient, PreparedState, *>,
    private val validationEvaluator: MutationValidationEvaluator<RuleClient, PreparedState>,
    private val adapter:
        UpdateMutationAdapter<Draft, Entity, PendingEdges, PreparedState, BeforeUpdateState>,
    private val hooks:
        UpdateMutationHooks<
            Draft,
            Entity,
            PendingEdges,
            BeforeSaveState,
            BeforeUpdateState,
        >,
) : MutationOperation<RuleClient, UpdateMutationInput<Draft>, Entity> {
    override fun requirements(input: UpdateMutationInput<Draft>): MutationRequirements =
        MutationRequirements("${entity.entityName} update")

    override fun run(
        execution: MutationExecution,
        ruleClient: RuleClient,
        input: UpdateMutationInput<Draft>,
    ): MutationCompletion<Entity> {
        val viewerContext = input.viewerContext
        val request = input.request
        val relationshipRequirements = adapter.relationshipRequirements(request.draft)
        validateRequirementsAndAcquireLocks(execution, request, relationshipRequirements)
        val before = loadTarget(execution, request, relationshipRequirements)
        val prepared = prepareUpdate(execution, viewerContext, request, before)

        evaluatePrivacy(execution, PrivacyRuleContext(viewerContext, ruleClient), request.id, prepared.state)
        evaluateValidation(execution, ValidationRuleContext(ruleClient), prepared.state)

        val resultEntity = if (prepared.isNoOp) {
            before
        } else {
            val updated = persistUpdate(
                execution = execution,
                request = request,
                before = before,
                prepared = prepared,
            )
            hooks.runAfter(updated)
            updated
        }
        return if (input.applyLoadPrivacy) {
            evaluateReturnedEntityPrivacy(viewerContext, resultEntity)
        } else {
            MutationCompletion.Ready(resultEntity)
        }
    }

    private fun prepareUpdate(
        execution: MutationExecution,
        viewerContext: ViewerContext,
        request: UpdateMutationRequest<Draft>,
        before: Entity,
    ): PreparedUpdate<PreparedState> {
        val pendingEdges = adapter.capturePendingEdges(request.draft)
        val beforeUpdateState = hooks.runBefore(
            viewerContext = viewerContext,
            draft = request.draft,
            before = before,
            pendingEdges = pendingEdges,
        )
        return when (
            val preparation = adapter.prepare(
                request,
                before,
                pendingEdges,
                beforeUpdateState,
                preparationScope(execution),
            )
        ) {
            is UpdatePreparation.Ready -> preparation.value
            is UpdatePreparation.Invalid -> execution.reject(
                EntValidationException(
                    entityType = entity.entityName,
                    operation = EntOperation.UPDATE,
                    violations = preparation.violations,
                ),
            )
        }
    }

    private fun validateRequirementsAndAcquireLocks(
        execution: MutationExecution,
        request: UpdateMutationRequest<Draft>,
        requirements: UpdateRelationshipRequirements,
    ) {
        checkConsistency(execution, request.consistency)
        checkRelationshipRequirements(
            execution,
            request.relationshipLocking,
            requirements,
        )
        acquireCanonicalRelationshipLocks(execution, request.relationshipLocking, requirements)
    }

    private fun loadTarget(
        execution: MutationExecution,
        request: UpdateMutationRequest<Draft>,
        relationshipRequirements: UpdateRelationshipRequirements,
    ): Entity {
        val row = driverCall(execution, MutationWriteState.NotPersisted) {
            loadOwnerRow(execution, request, relationshipRequirements)
        }
        if (row == null) execution.rejectTargetAbsent(request.id)
        return entity.decode(row)
    }

    private fun checkConsistency(
        execution: MutationExecution,
        consistency: UpdateConsistency,
    ) {
        if (consistency != UpdateConsistency.Pessimistic) return
        if (!execution.driver.inTransaction) {
            throw TransactionRequiredException(
                "${entity.entityName} Pessimistic update requires a transaction-scoped client",
            )
        }
        if (!execution.driver.supportsReadRowForUpdate) {
            throw UnsupportedDriverCapabilityException(
                "${entity.entityName} Pessimistic update requires a driver with " +
                    "supportsReadRowForUpdate = true",
            )
        }
    }

    private fun checkRelationshipRequirements(
        execution: MutationExecution,
        relationshipLocking: RelationshipLocking,
        requirements: UpdateRelationshipRequirements,
    ) {
        if (!requirements.hasPendingWrites) return
        if (!execution.driver.inTransaction) {
            throw TransactionRequiredException(
                "${entity.entityName} link-table M2M update requires a transaction-scoped client",
            )
        }
        if (
            !execution.driver.supportsReadRowForUpdate &&
            !execution.driver.supportsOwnerEdgeSerialization
        ) {
            throw UnsupportedDriverCapabilityException(
                "${entity.entityName} link-table M2M update requires a driver with " +
                    "supportsReadRowForUpdate or supportsOwnerEdgeSerialization",
            )
        }
        if (requirements.requiresInsertIgnore && !execution.driver.supportsInsertIgnore) {
            throw UnsupportedDriverCapabilityException(
                "${entity.entityName} link-table M2M add/set requires a driver with " +
                    "supportsInsertIgnore = true",
            )
        }
        if (
            relationshipLocking == RelationshipLocking.Canonical &&
            !execution.driver.supportsRelationshipSerialization
        ) {
            throw UnsupportedDriverCapabilityException(
                "${entity.entityName} relationshipLocking = Canonical requires a driver with " +
                    "supportsRelationshipSerialization = true",
            )
        }
    }

    private fun acquireCanonicalRelationshipLocks(
        execution: MutationExecution,
        relationshipLocking: RelationshipLocking,
        requirements: UpdateRelationshipRequirements,
    ) {
        if (relationshipLocking != RelationshipLocking.Canonical) return
        requirements.canonicalLockKeys.forEach(execution.driver::serializeRelationship)
    }

    private fun loadOwnerRow(
        execution: MutationExecution,
        request: UpdateMutationRequest<Draft>,
        relationshipRequirements: UpdateRelationshipRequirements,
    ): Map<String, Any?>? = when {
        request.consistency == UpdateConsistency.Pessimistic ->
            execution.driver.readRowForUpdate(entity.table, request.id)
        relationshipRequirements.hasPendingWrites && execution.driver.supportsReadRowForUpdate ->
            execution.driver.readRowForUpdate(entity.table, request.id)
        relationshipRequirements.hasPendingWrites ->
            execution.driver.serializeOwnerEdgeAndRead(entity.table, request.id)
        else -> execution.driver.byId(entity.table, request.id)
    }

    private fun evaluatePrivacy(
        attempt: MutationExecution,
        context: PrivacyRuleContext<RuleClient>,
        id: Any,
        state: PreparedState,
    ) {
        privacyEvaluator.evaluate(context, listOf(state)).firstDeniedOrNull()?.let { denial ->
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = MutationWriteState.NotPersisted,
                    entityType = entity.entityName,
                    operation = EntOperation.UPDATE,
                    entityKey = EntityKey("id", id),
                    reason = denial.reason,
                ),
            )
        }
    }

    private fun evaluateValidation(
        attempt: MutationExecution,
        context: ValidationRuleContext<RuleClient>,
        state: PreparedState,
    ) {
        validationEvaluator.evaluate(context, listOf(state)).firstInvalidOrNull()?.let { invalid ->
            attempt.reject(
                EntValidationException(
                    entityType = entity.entityName,
                    operation = EntOperation.UPDATE,
                    violations = invalid.violations.map { it.toValidationViolation() },
                ),
            )
        }
    }

    private fun persistUpdate(
        execution: MutationExecution,
        request: UpdateMutationRequest<Draft>,
        before: Entity,
        prepared: PreparedUpdate<PreparedState>,
    ): Entity {
        val updated = if (prepared.values.isEmpty()) {
            before
        } else {
            val updatedRow = driverCall(
                execution,
                MutationWriteState.PersistenceUnknown,
            ) {
                execution.driver.update(entity.table, request.id, prepared.values)
            }
            if (updatedRow == null) execution.rejectTargetAbsent(request.id)
            execution.markWriteSucceeded()
            entity.decode(updatedRow)
        }

        persistRelationships(
            execution = execution,
            request = request,
            prepared = prepared,
        )
        return updated
    }

    private fun MutationExecution.rejectTargetAbsent(
        id: Any,
    ): Nothing = reject(
        EntTargetAbsentException(
            entityType = entity.entityName,
            key = EntityKey("id", id),
        ),
    )

    private fun persistRelationships(
        execution: MutationExecution,
        request: UpdateMutationRequest<Draft>,
        prepared: PreparedUpdate<PreparedState>,
    ) {
        val tracker = RelationshipWriteTracker()
        try {
            adapter.persistRelationships(request, prepared.state, tracker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = classifyDriverFailure(
                execution,
                e,
                MutationWriteState.TransactionPending,
            )
            val reported = if (
                (execution.writeState != MutationWriteState.NotPersisted || tracker.wrote) &&
                classified.writeState == MutationWriteState.NotPersisted
            ) {
                EntUnexpectedMutationException(MutationWriteState.TransactionPending, classified)
            } else {
                classified
            }
            execution.reject(reported)
        }
        if (tracker.wrote) execution.markWriteSucceeded()
    }

    private fun evaluateReturnedEntityPrivacy(
        viewerContext: ViewerContext,
        result: Entity,
    ): MutationCompletion<Entity> = try {
        val evaluation = mutationRuntime.evaluate(
            entity = entity,
            viewerContext = viewerContext,
            entities = listOf(result),
        )
        check(evaluation.size == 1) { "LOAD privacy must return exactly one update result" }
        val denied = evaluation.firstDeniedOrNull()
        if (denied == null) {
            MutationCompletion.Ready(result)
        } else {
            MutationCompletion.ReturnDenied(
                PrivacyDenial(
                    entityType = entity.entityName,
                    entityKey = EntityKey("id", denied.subject.id),
                    reason = denied.reason,
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MutationCompletion.ReturnFailed(e)
    }

    private fun preparationScope(
        attempt: MutationExecution,
    ): UpdatePreparationScope = object : UpdatePreparationScope {
        override fun <Result> driverRead(block: () -> Result): Result =
            driverCall(attempt, MutationWriteState.NotPersisted, block)
    }

    private fun <Result> driverCall(
        attempt: MutationExecution,
        fallback: MutationWriteState,
        block: () -> Result,
    ): Result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        attempt.reject(classifyDriverFailure(attempt, e, fallback))
    }

    private fun classifyDriverFailure(
        execution: MutationExecution,
        exception: Exception,
        fallback: MutationWriteState,
    ) = execution.driver.classifyMutationException(
        exception,
        entity.entityName,
        EntOperation.UPDATE,
    ) ?: EntUnexpectedMutationException(fallback, exception)

    private class RelationshipWriteTracker : UpdateWriteTracker {
        var wrote: Boolean = false
            private set

        override fun markWritten() {
            wrote = true
        }
    }
}
