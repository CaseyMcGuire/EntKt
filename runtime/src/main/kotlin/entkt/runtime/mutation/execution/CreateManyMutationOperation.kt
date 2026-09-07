@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.HookRunner
import entkt.runtime.hook.MutationHookRunner
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.toValidationViolation
import entkt.runtime.validation.MutationValidationEvaluator
import entkt.runtime.validation.ValidationRuleContext
import java.util.concurrent.CancellationException

/**
 * Executes the ordered-batch CREATE algorithm using schema-specific converters.
 *
 * A create runs before hooks, validates required inputs, resolves every draft,
 * validates resolved fields, evaluates CREATE privacy, evaluates CREATE
 * validation, persists the rows, runs after hooks, and then applies
 * returned-entity LOAD privacy when the terminal exposes entities.
 */
@EntktInternal
class CreateManyMutationOperation<
    RuleClient,
    Draft : CreateMutationDraft<Entity>,
    Candidate,
    Entity : EntEntity<*>,
    BeforeSaveState,
    BeforeCreateState,
    >(
    private val mutationRuntime: MutationRuntime,
    private val entity: EntityMapping<Entity>,
    private val converter: CreateMutationConverter<Draft, Candidate, Entity>,
    private val privacyEvaluator: MutationPrivacyEvaluator<RuleClient, Candidate>,
    private val validationEvaluator: MutationValidationEvaluator<RuleClient, Candidate>,
    private val hookStateConverter:
        CreateMutationHookStateConverter<Draft, BeforeSaveState, BeforeCreateState>,
    private val beforeSaveHookRunner: MutationHookRunner<BeforeSaveState>,
    private val beforeCreateHookRunner: MutationHookRunner<BeforeCreateState>,
    private val afterCreateHookRunner: HookRunner<Entity>,
) : MutationOperation<RuleClient, CreateManyMutationInput<Draft>, List<Entity>> {
    internal val entityName: String get() = entity.entityName

    override fun requirements(input: CreateManyMutationInput<Draft>): MutationRequirements =
        MutationRequirements(
            operationName = "${entity.entityName} createMany",
            multiWrite = input.blocks.size > 1,
            requiresAtomicTransaction = input.blocks.isNotEmpty(),
        )

    override fun run(
        execution: MutationExecution,
        ruleClient: RuleClient,
        input: CreateManyMutationInput<Draft>,
    ): MutationCompletion<List<Entity>> {
        if (input.blocks.isEmpty()) return MutationCompletion.Ready(emptyList())
        val drafts = input.blocks.map { block -> input.newDraft().apply(block) }
        return createBatch(
            execution = execution,
            ruleClient = ruleClient,
            viewerContext = input.viewerContext,
            drafts = drafts,
            persistence = CreatePersistence.Many,
            checkReturnedEntityPrivacy = true,
        )
    }

    /** Run the same ordered phases for scalar and bulk terminals. */
    internal fun createBatch(
        execution: MutationExecution,
        ruleClient: RuleClient,
        viewerContext: ViewerContext,
        drafts: List<Draft>,
        persistence: CreatePersistence,
        checkReturnedEntityPrivacy: Boolean,
    ): MutationCompletion<List<Entity>> {
        val created = runCreateLifecycle(
            attempt = execution,
            ruleClient = ruleClient,
            viewerContext = viewerContext,
            drafts = drafts,
            persistence = persistence,
            promoteDriverNotPersisted = persistence == CreatePersistence.Many && !execution.isOwnedTransaction,
        )
        return if (checkReturnedEntityPrivacy) {
            evaluateReturnedEntityPrivacy(viewerContext, created, entity)
        } else {
            MutationCompletion.Ready(created)
        }
    }

    /** Run every scalar or batch create phase in lifecycle order. */
    private fun runCreateLifecycle(
        attempt: MutationExecution,
        ruleClient: RuleClient,
        viewerContext: ViewerContext,
        drafts: List<Draft>,
        persistence: CreatePersistence,
        promoteDriverNotPersisted: Boolean,
    ): List<Entity> {
        val beforeSaveStates = beforeSaveHookRunner.runBatch(
            drafts.map(hookStateConverter::toBeforeSaveState),
        )
        val beforeCreateStates = beforeSaveStates.mapStatesIndexed { index, beforeSaveState ->
            hookStateConverter.toBeforeCreateState(
                viewerContext = viewerContext,
                draft = drafts[index],
                beforeSaveState = beforeSaveState,
            )
        }
        val finalHookStates = beforeCreateHookRunner.runBatch(beforeCreateStates)
        val preparationDrafts = finalHookStates.mapIndexed { index, state ->
            hookStateConverter.toPreparationDraft(drafts[index], state)
        }

        rejectRequiredInputViolations(
            attempt = attempt,
            drafts = preparationDrafts,
            entityName = entityName,
        )
        val resolvedCreates = preparationDrafts.map(converter::resolve)
        rejectFieldViolations(
            attempt = attempt,
            entityName = entityName,
            prepared = resolvedCreates,
        )
        evaluateCreatePrivacy(
            attempt = attempt,
            entityName = entityName,
            prepared = resolvedCreates,
            context = PrivacyRuleContext(viewerContext, ruleClient),
        )
        evaluateCreateValidation(
            attempt = attempt,
            context = ValidationRuleContext(ruleClient),
            entityName = entityName,
            prepared = resolvedCreates,
        )

        val createdEntities = persistCreates(
            attempt = attempt,
            prepared = resolvedCreates,
            entity = entity,
            persistence = persistence,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        )
        afterCreateHookRunner.run(createdEntities)

        return createdEntities
    }

    /** Reject missing required inputs before resolution evaluates defaults. */
    private fun rejectRequiredInputViolations(
        attempt: MutationExecution,
        drafts: List<Draft>,
        entityName: String,
    ) {
        drafts.firstNotNullOfOrNull { draft ->
            converter.requiredInputViolations(draft).takeIf { it.isNotEmpty() }
        }?.let { violations ->
            attempt.reject(
                EntValidationException(
                    entityType = entityName,
                    operation = EntOperation.CREATE,
                    violations = violations,
                ),
            )
        }
    }

    /** Reject schema-field violations before CREATE privacy sees candidates. */
    private fun rejectFieldViolations(
        attempt: MutationExecution,
        entityName: String,
        prepared: List<PreparedCreate<Candidate>>,
    ) {
        prepared.firstNotNullOfOrNull { create ->
            converter.fieldViolations(create.candidate).takeIf { it.isNotEmpty() }
        }?.let { violations ->
            attempt.reject(
                EntValidationException(
                    entityType = entityName,
                    operation = EntOperation.CREATE,
                    violations = violations,
                ),
            )
        }
    }

    /** Reject the entire create operation when any candidate is denied. */
    private fun evaluateCreatePrivacy(
        attempt: MutationExecution,
        entityName: String,
        prepared: List<PreparedCreate<Candidate>>,
        context: PrivacyRuleContext<RuleClient>,
    ) {
        val denial = privacyEvaluator.evaluate(
            context = context,
            states = prepared.map { it.candidate },
        ).firstDeniedOrNull()
        denial?.let {
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = MutationWriteState.NotPersisted,
                    entityType = entityName,
                    operation = EntOperation.CREATE,
                    entityKey = null,
                    reason = it.reason,
                ),
            )
        }
    }

    /** Reject the entire create operation when any candidate is invalid. */
    private fun evaluateCreateValidation(
        attempt: MutationExecution,
        context: ValidationRuleContext<RuleClient>,
        entityName: String,
        prepared: List<PreparedCreate<Candidate>>,
    ) {
        validationEvaluator.evaluate(context, prepared.map { it.candidate }).firstInvalidOrNull()?.let { invalid ->
            attempt.reject(
                EntValidationException(
                    entityType = entityName,
                    operation = EntOperation.CREATE,
                    violations = invalid.violations.map { it.toValidationViolation() },
                ),
            )
        }
    }

    /** Persist prepared rows with the scalar or batch driver primitive. */
    private fun persistCreates(
        attempt: MutationExecution,
        prepared: List<PreparedCreate<Candidate>>,
        entity: EntityMapping<Entity>,
        persistence: CreatePersistence,
        promoteDriverNotPersisted: Boolean,
    ): List<Entity> {
        val rows = try {
            when (persistence) {
                CreatePersistence.One -> listOf(
                    attempt.driver.insert(entity.table, prepared.single().values),
                )

                CreatePersistence.Many -> {
                    attempt.markWritePending()
                    attempt.driver.insertMany(entity.table, prepared.map { it.values })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallback = when (persistence) {
                CreatePersistence.One -> MutationWriteState.PersistenceUnknown
                CreatePersistence.Many -> MutationWriteState.TransactionPending
            }
            val classified = attempt.driver.classifyMutationException(
                e,
                entity.entityName,
                EntOperation.CREATE,
            ) ?: EntUnexpectedMutationException(fallback, e)
            val reported = if (
                persistence == CreatePersistence.Many &&
                promoteDriverNotPersisted &&
                prepared.size > 1 &&
                classified.writeState == MutationWriteState.NotPersisted
            ) {
                EntUnexpectedMutationException(
                    MutationWriteState.TransactionPending,
                    classified,
                )
            } else {
                classified
            }
            attempt.reject(reported)
        }

        attempt.markWriteSucceeded()
        check(rows.size == prepared.size) {
            "DatabaseDriver.${if (persistence == CreatePersistence.One) "insert" else "insertMany"} " +
                "contract violation for ${entity.entityName}: expected " +
                "${prepared.size} persisted rows but received ${rows.size}"
        }
        return rows.map(entity::decode)
    }

    /** Evaluate only returned LOAD privacy; lifecycle failures must still reject execution. */
    private fun evaluateReturnedEntityPrivacy(
        viewerContext: ViewerContext,
        created: List<Entity>,
        entity: EntityMapping<Entity>,
    ): MutationCompletion<List<Entity>> = try {
        val denial = returnedEntityDenial(viewerContext, created, entity)
        if (denial == null) {
            MutationCompletion.Ready(created)
        } else {
            MutationCompletion.ReturnDenied(denial)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MutationCompletion.ReturnFailed(e)
    }

    /** Return the first LOAD denial without assigning a mutation write state. */
    private fun returnedEntityDenial(
        viewerContext: ViewerContext,
        created: List<Entity>,
        entity: EntityMapping<Entity>,
    ): PrivacyDenial? {
        val evaluations = mutationRuntime.evaluate(
            entity = entity,
            viewerContext = viewerContext,
            entities = created,
        )
        check(evaluations.size == created.size) {
            "LOAD privacy returned ${evaluations.size} decisions for " +
                "${created.size} created entities"
        }
        val denied = evaluations.firstDeniedOrNull() ?: return null
        return PrivacyDenial(
            entityType = entity.entityName,
            entityKey = EntityKey("id", denied.subject.id),
            reason = denied.reason,
        )
    }
}
