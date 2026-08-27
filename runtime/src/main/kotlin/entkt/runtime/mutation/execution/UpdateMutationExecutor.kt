@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.hook.runBatchHooksForInternalUse
import entkt.runtime.privacy.PrivacyDecision
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
import java.util.concurrent.CancellationException

/** Executes the reusable UPDATE lifecycle around generated schema adapters. */
@EntktInternal
class UpdateMutationExecutor<RuleClient>(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val ruleClient: RuleClient,
) {
    private val execution = MutationExecutionSupport(mutationRuntime)

    fun <State, Entity : EntEntity<*>> update(
        viewerContext: ViewerContext,
        applyLoadPrivacy: Boolean,
        spec: UpdateMutationSpec<State, Entity, RuleClient>,
    ): MutationResult<Entity> = execution.execute { attempt ->
        val postWriteState = if (driver.inTransaction) {
            MutationWriteState.TransactionPending
        } else {
            MutationWriteState.Committed
        }
        mutationRuntime.checkTransactionRequirement("${spec.entity.entityName} update")
        spec.preflight()

        val row = driverCall(attempt, spec, MutationWriteState.NotPersisted, spec.loadRow)
            ?: attempt.reject(
                EntTargetAbsentException(
                    entityType = spec.entity.entityName,
                    key = EntityKey("id", spec.id),
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

            evaluatePrivacy(attempt, viewerContext, prepared.state, spec)
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
                    driver.update(spec.entity.table, spec.id, prepared.values)
                } ?: attempt.reject(
                    EntTargetAbsentException(
                        entityType = spec.entity.entityName,
                        key = EntityKey("id", spec.id),
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

    private fun <State, Entity : EntEntity<*>> evaluatePrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        state: State,
        spec: UpdateMutationSpec<State, Entity, RuleClient>,
    ) {
        val decision = spec.privacy.evaluate(viewerContext, ruleClient, listOf(state)).singleOrNull()
            ?: error("UPDATE privacy must return exactly one decision")
        val reason = when (decision) {
            PrivacyDecision.Allow -> null
            is PrivacyDecision.Deny -> decision.reason
            PrivacyDecision.Continue -> "no update rule allowed access"
        }
        reason?.let {
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = MutationWriteState.NotPersisted,
                    entityType = spec.entity.entityName,
                    operation = EntOperation.UPDATE,
                    entityKey = EntityKey("id", spec.id),
                    reason = it,
                ),
            )
        }
    }

    private fun <State, Entity : EntEntity<*>> evaluateValidation(
        attempt: MutationAttempt,
        state: State,
        spec: UpdateMutationSpec<State, Entity, RuleClient>,
    ) {
        val invalids = spec.validation.evaluate(ruleClient, listOf(state)).singleOrNull()
            ?: error("UPDATE validation must return exactly one result")
        if (invalids.isNotEmpty()) {
            attempt.reject(
                EntValidationException(
                    entityType = spec.entity.entityName,
                    operation = EntOperation.UPDATE,
                    violations = invalids.map { it.toValidationViolation() },
                ),
            )
        }
    }

    private fun <State, Entity : EntEntity<*>> persistRelationships(
        attempt: MutationAttempt,
        prepared: PreparedUpdate<State>,
        spec: UpdateMutationSpec<State, Entity, RuleClient>,
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

    private fun <State, Entity : EntEntity<*>> evaluateReturnedEntityPrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        entity: Entity,
        spec: UpdateMutationSpec<State, Entity, RuleClient>,
    ) {
        val evaluation = mutationRuntime.evaluate(
            entity = spec.entity,
            viewerContext = viewerContext,
            entities = listOf(entity),
        ).singleOrNull() ?: error("LOAD privacy must return exactly one update result")
        evaluation.denialOrNull()?.let { denial ->
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = attempt.writeState,
                    entityType = spec.entity.entityName,
                    operation = EntOperation.LOAD,
                    entityKey = denial.entityKey,
                    reason = denial.reason,
                ),
            )
        }
    }

    private fun <State, Entity : EntEntity<*>> preparationScope(
        attempt: MutationAttempt,
        spec: UpdateMutationSpec<State, Entity, RuleClient>,
    ): UpdatePreparationScope = object : UpdatePreparationScope {
        override fun <Result> driverRead(block: () -> Result): Result =
            driverCall(attempt, spec, MutationWriteState.NotPersisted, block)
    }

    private fun <Result, State, Entity : EntEntity<*>> driverCall(
        attempt: MutationAttempt,
        spec: UpdateMutationSpec<State, Entity, RuleClient>,
        fallback: MutationWriteState,
        block: () -> Result,
    ): Result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        attempt.reject(classifyDriverFailure(e, spec, fallback))
    }

    private fun <State, Entity : EntEntity<*>> classifyDriverFailure(
        exception: Exception,
        spec: UpdateMutationSpec<State, Entity, RuleClient>,
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
