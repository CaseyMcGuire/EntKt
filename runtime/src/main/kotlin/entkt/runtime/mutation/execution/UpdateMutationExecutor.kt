@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.hook.runBatchHooksForInternalUse
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
class UpdateMutationExecutor<State>(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val privacyEvaluator: MutationPrivacyEvaluator<State>,
    private val validationEvaluator: MutationValidationEvaluator<State>,
) {
    private val execution = MutationExecutionSupport(mutationRuntime)

    fun <Entity : EntEntity<*>, Draft : UpdateMutationDraft<Entity>> update(
        viewerContext: ViewerContext,
        request: UpdateMutationRequest<Draft>,
        applyLoadPrivacy: Boolean,
        spec: UpdateMutationSpec<State, Entity>,
        relationshipRequirements: UpdateRelationshipRequirements = UpdateRelationshipRequirements.None,
    ): MutationResult<Entity> = execution.execute { attempt ->
        val postWriteState = if (driver.inTransaction) {
            MutationWriteState.TransactionPending
        } else {
            MutationWriteState.Committed
        }
        mutationRuntime.checkTransactionRequirement("${spec.entity.entityName} update")
        checkConsistency(request.consistency, spec.entity.entityName)
        checkRelationshipRequirements(
            request.relationshipLocking,
            relationshipRequirements,
            spec.entity.entityName,
        )
        acquireCanonicalRelationshipLocks(request.relationshipLocking, relationshipRequirements)

        val row = driverCall(attempt, spec, MutationWriteState.NotPersisted) {
            loadOwnerRow(request, relationshipRequirements, spec)
        }
            ?: attempt.reject(
                EntTargetAbsentException(
                    entityType = spec.entity.entityName,
                    key = EntityKey("id", request.id),
                ),
            )
        val before = spec.entity.decode(row)

        spec.begin()
        try {
            spec.before(viewerContext, before)
            val prepared = when (val preparation = spec.prepare(before, preparationScope(attempt, spec))) {
                is UpdatePreparation.Ready -> preparation.value
                is UpdatePreparation.Invalid -> attempt.reject(
                    EntValidationException(
                        entityType = spec.entity.entityName,
                        operation = EntOperation.UPDATE,
                        violations = preparation.violations,
                    ),
                )
            }

            evaluatePrivacy(attempt, viewerContext, request.id, prepared.state, spec)
            evaluateValidation(attempt, prepared.state, spec)

            if (prepared.isNoOp) {
                if (applyLoadPrivacy) {
                    evaluateReturnedEntityPrivacy(attempt, viewerContext, before, spec)
                }
                return@execute before
            }

            val updated = if (prepared.values.isEmpty()) {
                before
            } else {
                val updatedRow = driverCall(
                    attempt,
                    spec,
                    MutationWriteState.PersistenceUnknown,
                ) {
                    driver.update(spec.entity.table, request.id, prepared.values)
                } ?: attempt.reject(
                    EntTargetAbsentException(
                        entityType = spec.entity.entityName,
                        key = EntityKey("id", request.id),
                    ),
                )
                attempt.writeState = postWriteState
                spec.entity.decode(updatedRow)
            }

            persistRelationships(
                attempt = attempt,
                prepared = prepared,
                spec = spec,
                postWriteState = postWriteState,
            )
            runBatchHooksForInternalUse(listOf(updated), spec.afterUpdate)
            if (applyLoadPrivacy) {
                evaluateReturnedEntityPrivacy(attempt, viewerContext, updated, spec)
            }
            updated
        } finally {
            spec.end()
        }
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

    private fun <Entity : EntEntity<*>, Draft : UpdateMutationDraft<Entity>> loadOwnerRow(
        request: UpdateMutationRequest<Draft>,
        relationshipRequirements: UpdateRelationshipRequirements,
        spec: UpdateMutationSpec<State, Entity>,
    ): Map<String, Any?>? = when {
        request.consistency == UpdateConsistency.Pessimistic ->
            driver.readRowForUpdate(spec.entity.table, request.id)
        relationshipRequirements.hasPendingWrites && driver.supportsReadRowForUpdate ->
            driver.readRowForUpdate(spec.entity.table, request.id)
        relationshipRequirements.hasPendingWrites ->
            driver.serializeOwnerEdgeAndRead(spec.entity.table, request.id)
        else -> driver.byId(spec.entity.table, request.id)
    }

    private fun <Entity : EntEntity<*>> evaluatePrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        id: Any,
        state: State,
        spec: UpdateMutationSpec<State, Entity>,
    ) {
        privacyEvaluator.evaluate(viewerContext, listOf(state)).firstDeniedOrNull()?.let { denial ->
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = MutationWriteState.NotPersisted,
                    entityType = spec.entity.entityName,
                    operation = EntOperation.UPDATE,
                    entityKey = EntityKey("id", id),
                    reason = denial.reason,
                ),
            )
        }
    }

    private fun <Entity : EntEntity<*>> evaluateValidation(
        attempt: MutationAttempt,
        state: State,
        spec: UpdateMutationSpec<State, Entity>,
    ) {
        validationEvaluator.evaluate(listOf(state)).firstInvalidOrNull()?.let { invalid ->
            attempt.reject(
                EntValidationException(
                    entityType = spec.entity.entityName,
                    operation = EntOperation.UPDATE,
                    violations = invalid.violations.map { it.toValidationViolation() },
                ),
            )
        }
    }

    private fun <Entity : EntEntity<*>> persistRelationships(
        attempt: MutationAttempt,
        prepared: PreparedUpdate<State>,
        spec: UpdateMutationSpec<State, Entity>,
        postWriteState: MutationWriteState,
    ) {
        val tracker = RelationshipWriteTracker()
        try {
            spec.relationships(prepared.state, tracker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = classifyDriverFailure(e, spec, MutationWriteState.TransactionPending)
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

    private fun <Entity : EntEntity<*>> evaluateReturnedEntityPrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        entity: Entity,
        spec: UpdateMutationSpec<State, Entity>,
    ) {
        val evaluation = mutationRuntime.evaluate(
            entity = spec.entity,
            viewerContext = viewerContext,
            entities = listOf(entity),
        )
        check(evaluation.size == 1) { "LOAD privacy must return exactly one update result" }
        evaluation.firstDeniedOrNull()?.let { denied ->
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = attempt.writeState,
                    entityType = spec.entity.entityName,
                    operation = EntOperation.LOAD,
                    entityKey = EntityKey("id", denied.subject.id),
                    reason = denied.reason,
                ),
            )
        }
    }

    private fun <Entity : EntEntity<*>> preparationScope(
        attempt: MutationAttempt,
        spec: UpdateMutationSpec<State, Entity>,
    ): UpdatePreparationScope = object : UpdatePreparationScope {
        override fun <Result> driverRead(block: () -> Result): Result =
            driverCall(attempt, spec, MutationWriteState.NotPersisted, block)
    }

    private fun <Result, Entity : EntEntity<*>> driverCall(
        attempt: MutationAttempt,
        spec: UpdateMutationSpec<State, Entity>,
        fallback: MutationWriteState,
        block: () -> Result,
    ): Result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        attempt.reject(classifyDriverFailure(e, spec, fallback))
    }

    private fun <Entity : EntEntity<*>> classifyDriverFailure(
        exception: Exception,
        spec: UpdateMutationSpec<State, Entity>,
        fallback: MutationWriteState,
    ) = driver.classifyMutationException(
        exception,
        spec.entity.entityName,
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
