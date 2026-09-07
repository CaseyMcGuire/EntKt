@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationWriteState
import entkt.runtime.validation.MutationValidationEvaluator
import java.util.concurrent.CancellationException

/** Reload, authorize, and idempotently delete one current row. */
@EntktInternal
class DeleteMutationOperation<Entity : EntEntity<*>, Candidate>(
    private val spec: DeleteMutationSpec<Entity>,
    private val converter: DeleteMutationConverter<Entity, Candidate>,
    private val privacyEvaluator:
        MutationPrivacyEvaluator<DeleteRuleCandidate<Entity, Candidate>>,
    private val validationEvaluator:
        MutationValidationEvaluator<DeleteRuleCandidate<Entity, Candidate>>,
) : MutationOperation<DeleteMutationInput, Boolean> {
    override fun requirements(input: DeleteMutationInput): MutationRequirements =
        MutationRequirements("${spec.entity.entityName} delete")

    override fun run(
        execution: MutationExecution,
        input: DeleteMutationInput,
    ): MutationCompletion<Boolean> = MutationCompletion.Ready(
        deleteById(execution, input.viewerContext, input.id),
    )

    /** Reload and idempotently delete one current row. */
    private fun deleteById(
        execution: MutationExecution,
        viewerContext: ViewerContext,
        id: Any,
    ): Boolean {
        val row = try {
            execution.driver.byId(spec.entity.table, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            execution.reject(classifyDriverFailure(execution, e, MutationWriteState.NotPersisted))
        }
        if (row == null) return false

        return deleteLoaded(
            execution = execution,
            viewerContext = viewerContext,
            entity = spec.entity.decode(row),
        )
    }

    private fun deleteLoaded(
        execution: MutationExecution,
        viewerContext: ViewerContext,
        entity: Entity,
    ): Boolean {
        val candidate = DeleteRuleCandidate(entity, converter.toCandidate(entity))
        evaluateDeleteRules(
            execution = execution,
            entityName = spec.entity.entityName,
            viewerContext = viewerContext,
            candidates = listOf(candidate),
            privacyEvaluator = privacyEvaluator,
            validationEvaluator = validationEvaluator,
        )
        spec.beforeDelete.run(listOf(entity))

        val deleted = try {
            execution.driver.delete(spec.entity.table, entity.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            execution.reject(
                classifyDriverFailure(execution, e, MutationWriteState.PersistenceUnknown),
            )
        }
        if (deleted) {
            execution.markWriteSucceeded()
            spec.afterDelete.run(listOf(entity))
        }
        return deleted
    }

    private fun classifyDriverFailure(
        execution: MutationExecution,
        exception: Exception,
        fallback: MutationWriteState,
    ) = execution.driver.classifyMutationException(
        exception,
        spec.entity.entityName,
        EntOperation.DELETE,
    ) ?: EntUnexpectedMutationException(fallback, exception)
}
