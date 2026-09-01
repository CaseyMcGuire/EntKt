@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.mutation.UpdateConsistency
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdateMutationRequest
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntTargetAbsentException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.toValidationViolation
import entkt.runtime.validation.MutationValidationEvaluator
import java.util.concurrent.CancellationException

/** Executes the reusable UPDATE lifecycle around generated schema adapters. */
@EntktInternal
class UpdateMutationExecutor<
    Draft : UpdateMutationDraft<Entity>,
    Entity : EntEntity<*>,
    PendingEdges,
    State,
    BeforeSaveState,
    BeforeUpdateState,
    >(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val privacyEvaluator: MutationPrivacyEvaluator<State>,
    private val validationEvaluator: MutationValidationEvaluator<State>,
    private val adapter:
        UpdateMutationAdapter<Draft, Entity, PendingEdges, State, BeforeUpdateState>,
    private val hooks:
        UpdateMutationHooks<
            Draft,
            Entity,
            PendingEdges,
            BeforeSaveState,
            BeforeUpdateState,
        >,
) {
    private val execution = MutationExecutionSupport(mutationRuntime)

    fun update(
        viewerContext: ViewerContext,
        request: UpdateMutationRequest<Draft>,
        entity: EntityMapping<Entity>,
        applyLoadPrivacy: Boolean,
    ): MutationResult<Entity> = execution.execute { attempt ->
        val relationshipRequirements = adapter.relationshipRequirements(request.draft)
        val postWriteState = if (driver.inTransaction) {
            MutationWriteState.TransactionPending
        } else {
            MutationWriteState.Committed
        }
        validateRequirementsAndAcquireLocks(request, relationshipRequirements, entity)
        val before = loadTarget(attempt, request, relationshipRequirements, entity)
        val prepared = prepareUpdate(attempt, viewerContext, request, before, entity)

        evaluatePrivacy(attempt, viewerContext, request.id, prepared.state, entity)
        evaluateValidation(attempt, prepared.state, entity)

        val resultEntity = if (prepared.isNoOp) {
            before
        } else {
            val updated = persistUpdate(
                attempt = attempt,
                request = request,
                before = before,
                prepared = prepared,
                entity = entity,
                postWriteState = postWriteState,
            )
            hooks.runAfter(updated)
            updated
        }
        evaluateReturnedEntityPrivacyIfRequested(
            attempt,
            viewerContext,
            resultEntity,
            entity,
            applyLoadPrivacy,
        )
        resultEntity
    }

    private fun prepareUpdate(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        request: UpdateMutationRequest<Draft>,
        before: Entity,
        entity: EntityMapping<Entity>,
    ): PreparedUpdate<State> {
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
                preparationScope(attempt, entity),
            )
        ) {
            is UpdatePreparation.Ready -> preparation.value
            is UpdatePreparation.Invalid -> attempt.reject(
                EntValidationException(
                    entityType = entity.entityName,
                    operation = EntOperation.UPDATE,
                    violations = preparation.violations,
                ),
            )
        }
    }

    private fun validateRequirementsAndAcquireLocks(
        request: UpdateMutationRequest<Draft>,
        requirements: UpdateRelationshipRequirements,
        entity: EntityMapping<Entity>,
    ) {
        mutationRuntime.checkTransactionRequirement("${entity.entityName} update")
        checkConsistency(request.consistency, entity.entityName)
        checkRelationshipRequirements(
            request.relationshipLocking,
            requirements,
            entity.entityName,
        )
        acquireCanonicalRelationshipLocks(request.relationshipLocking, requirements)
    }

    private fun loadTarget(
        attempt: MutationAttempt,
        request: UpdateMutationRequest<Draft>,
        relationshipRequirements: UpdateRelationshipRequirements,
        entity: EntityMapping<Entity>,
    ): Entity {
        val row = driverCall(attempt, entity, MutationWriteState.NotPersisted) {
            loadOwnerRow(request, relationshipRequirements, entity)
        }
        if (row == null) attempt.rejectTargetAbsent(entity, request.id)
        return entity.decode(row)
    }

    private fun checkConsistency(
        consistency: UpdateConsistency,
        entityName: String,
    ) {
        if (consistency != UpdateConsistency.Pessimistic) return
        if (!driver.inTransaction) {
            throw TransactionRequiredException(
                "$entityName Pessimistic update requires a transaction-scoped client",
            )
        }
        if (!driver.supportsReadRowForUpdate) {
            throw UnsupportedDriverCapabilityException(
                "$entityName Pessimistic update requires a driver with " +
                    "supportsReadRowForUpdate = true",
            )
        }
    }

    private fun checkRelationshipRequirements(
        relationshipLocking: RelationshipLocking,
        requirements: UpdateRelationshipRequirements,
        entityName: String,
    ) {
        if (!requirements.hasPendingWrites) return
        if (!driver.inTransaction) {
            throw TransactionRequiredException(
                "$entityName link-table M2M update requires a transaction-scoped client",
            )
        }
        if (!driver.supportsReadRowForUpdate && !driver.supportsOwnerEdgeSerialization) {
            throw UnsupportedDriverCapabilityException(
                "$entityName link-table M2M update requires a driver with " +
                    "supportsReadRowForUpdate or supportsOwnerEdgeSerialization",
            )
        }
        if (requirements.requiresInsertIgnore && !driver.supportsInsertIgnore) {
            throw UnsupportedDriverCapabilityException(
                "$entityName link-table M2M add/set requires a driver with " +
                    "supportsInsertIgnore = true",
            )
        }
        if (
            relationshipLocking == RelationshipLocking.Canonical &&
            !driver.supportsRelationshipSerialization
        ) {
            throw UnsupportedDriverCapabilityException(
                "$entityName relationshipLocking = Canonical requires a driver with " +
                    "supportsRelationshipSerialization = true",
            )
        }
    }

    private fun acquireCanonicalRelationshipLocks(
        relationshipLocking: RelationshipLocking,
        requirements: UpdateRelationshipRequirements,
    ) {
        if (relationshipLocking != RelationshipLocking.Canonical) return
        requirements.canonicalLockKeys.forEach(driver::serializeRelationship)
    }

    private fun loadOwnerRow(
        request: UpdateMutationRequest<Draft>,
        relationshipRequirements: UpdateRelationshipRequirements,
        entity: EntityMapping<Entity>,
    ): Map<String, Any?>? = when {
        request.consistency == UpdateConsistency.Pessimistic ->
            driver.readRowForUpdate(entity.table, request.id)
        relationshipRequirements.hasPendingWrites && driver.supportsReadRowForUpdate ->
            driver.readRowForUpdate(entity.table, request.id)
        relationshipRequirements.hasPendingWrites ->
            driver.serializeOwnerEdgeAndRead(entity.table, request.id)
        else -> driver.byId(entity.table, request.id)
    }

    private fun evaluatePrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        id: Any,
        state: State,
        entity: EntityMapping<Entity>,
    ) {
        privacyEvaluator.evaluate(viewerContext, listOf(state)).firstDeniedOrNull()?.let { denial ->
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
        attempt: MutationAttempt,
        state: State,
        entity: EntityMapping<Entity>,
    ) {
        validationEvaluator.evaluate(listOf(state)).firstInvalidOrNull()?.let { invalid ->
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
        attempt: MutationAttempt,
        request: UpdateMutationRequest<Draft>,
        before: Entity,
        prepared: PreparedUpdate<State>,
        entity: EntityMapping<Entity>,
        postWriteState: MutationWriteState,
    ): Entity {
        val updated = if (prepared.values.isEmpty()) {
            before
        } else {
            val updatedRow = driverCall(
                attempt,
                entity,
                MutationWriteState.PersistenceUnknown,
            ) {
                driver.update(entity.table, request.id, prepared.values)
            }
            if (updatedRow == null) attempt.rejectTargetAbsent(entity, request.id)
            attempt.writeState = postWriteState
            entity.decode(updatedRow)
        }

        persistRelationships(
            attempt = attempt,
            request = request,
            prepared = prepared,
            entity = entity,
            postWriteState = postWriteState,
        )
        return updated
    }

    private fun MutationAttempt.rejectTargetAbsent(
        entity: EntityMapping<Entity>,
        id: Any,
    ): Nothing = reject(
        EntTargetAbsentException(
            entityType = entity.entityName,
            key = EntityKey("id", id),
        ),
    )

    private fun persistRelationships(
        attempt: MutationAttempt,
        request: UpdateMutationRequest<Draft>,
        prepared: PreparedUpdate<State>,
        entity: EntityMapping<Entity>,
        postWriteState: MutationWriteState,
    ) {
        val tracker = RelationshipWriteTracker()
        try {
            adapter.persistRelationships(request, prepared.state, tracker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = classifyDriverFailure(e, entity, MutationWriteState.TransactionPending)
            val reported = if (
                (attempt.writeState != MutationWriteState.NotPersisted || tracker.wrote) &&
                classified.writeState == MutationWriteState.NotPersisted
            ) {
                EntUnexpectedMutationException(MutationWriteState.TransactionPending, classified)
            } else {
                classified
            }
            attempt.reject(reported)
        }
        if (tracker.wrote) attempt.writeState = postWriteState
    }

    private fun evaluateReturnedEntityPrivacyIfRequested(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        result: Entity,
        entity: EntityMapping<Entity>,
        applyLoadPrivacy: Boolean,
    ) {
        if (applyLoadPrivacy) {
            evaluateReturnedEntityPrivacy(attempt, viewerContext, result, entity)
        }
    }

    private fun evaluateReturnedEntityPrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        result: Entity,
        entity: EntityMapping<Entity>,
    ) {
        val evaluation = mutationRuntime.evaluate(
            entity = entity,
            viewerContext = viewerContext,
            entities = listOf(result),
        )
        check(evaluation.size == 1) { "LOAD privacy must return exactly one update result" }
        evaluation.firstDeniedOrNull()?.let { denied ->
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = attempt.writeState,
                    entityType = entity.entityName,
                    operation = EntOperation.LOAD,
                    entityKey = EntityKey("id", denied.subject.id),
                    reason = denied.reason,
                ),
            )
        }
    }

    private fun preparationScope(
        attempt: MutationAttempt,
        entity: EntityMapping<Entity>,
    ): UpdatePreparationScope = object : UpdatePreparationScope {
        override fun <Result> driverRead(block: () -> Result): Result =
            driverCall(attempt, entity, MutationWriteState.NotPersisted, block)
    }

    private fun <Result> driverCall(
        attempt: MutationAttempt,
        entity: EntityMapping<Entity>,
        fallback: MutationWriteState,
        block: () -> Result,
    ): Result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        attempt.reject(classifyDriverFailure(e, entity, fallback))
    }

    private fun classifyDriverFailure(
        exception: Exception,
        entity: EntityMapping<Entity>,
        fallback: MutationWriteState,
    ) = driver.classifyMutationException(
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
