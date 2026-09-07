@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.hook.HookRunner
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationWriteState
import entkt.runtime.validation.MutationValidationEvaluator
import java.util.concurrent.CancellationException

/** Reload, authorize, and idempotently delete one current row. */
@EntktInternal
class DeleteMutationOperation<RuleClient, Entity : EntEntity<*>, Candidate>(
    private val entity: EntityDescriptor<Entity, *>,
    private val converter: DeleteMutationConverter<Entity, Candidate>,
    private val privacyEvaluator:
        MutationPrivacyEvaluator<RuleClient, DeleteRuleCandidate<Entity, Candidate>>,
    private val validationEvaluator:
        MutationValidationEvaluator<RuleClient, DeleteRuleCandidate<Entity, Candidate>>,
    private val beforeDelete: HookRunner<Entity>,
    private val afterDelete: HookRunner<Entity>,
) : MutationOperation<RuleClient, DeleteMutationInput, Boolean> {
    override fun requirements(input: DeleteMutationInput): MutationRequirements =
        MutationRequirements("${entity.entityName} delete")

    override fun run(
        execution: MutationExecution,
        ruleClient: RuleClient,
        input: DeleteMutationInput,
    ): MutationCompletion<Boolean> = MutationCompletion.Ready(
        deleteById(execution, ruleClient, input.viewerContext, input.id),
    )

    /** Reload and idempotently delete one current row. */
    private fun deleteById(
        execution: MutationExecution,
        ruleClient: RuleClient,
        viewerContext: ViewerContext,
        id: Any,
    ): Boolean {
        val row = try {
            execution.driver.byId(entity.table, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            execution.reject(classifyDriverFailure(execution, e, MutationWriteState.NotPersisted))
        }
        if (row == null) return false

        return deleteLoaded(
            execution = execution,
            ruleClient = ruleClient,
            viewerContext = viewerContext,
            target = entity.decode(row),
        )
    }

    private fun deleteLoaded(
        execution: MutationExecution,
        ruleClient: RuleClient,
        viewerContext: ViewerContext,
        target: Entity,
    ): Boolean {
        val candidate = DeleteRuleCandidate(target, converter.toCandidate(target))
        evaluateDeleteRules(
            execution = execution,
            ruleClient = ruleClient,
            entityName = entity.entityName,
            viewerContext = viewerContext,
            candidates = listOf(candidate),
            privacyEvaluator = privacyEvaluator,
            validationEvaluator = validationEvaluator,
        )
        beforeDelete.run(listOf(target))

        val deleted = try {
            execution.driver.delete(entity.table, target.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            execution.reject(
                classifyDriverFailure(execution, e, MutationWriteState.PersistenceUnknown),
            )
        }
        if (deleted) {
            execution.markWriteSucceeded()
            afterDelete.run(listOf(target))
        }
        return deleted
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
