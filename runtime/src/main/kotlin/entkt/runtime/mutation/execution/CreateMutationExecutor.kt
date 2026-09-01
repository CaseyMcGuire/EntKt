@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.HookRunner
import entkt.runtime.hook.MutationHookRunner
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.ValidationViolation
import entkt.runtime.result.toValidationViolation
import entkt.runtime.validation.MutationValidationEvaluator
import java.util.concurrent.CancellationException

/**
 * Runs create lifecycles for generated drafts and entity specifications.
 *
 * A create runs before hooks, validates required inputs, resolves every draft,
 * validates resolved fields, evaluates CREATE privacy, evaluates CREATE
 * validation, persists the rows, runs after hooks, and then applies
 * returned-entity LOAD privacy when the terminal exposes entities.
 */
@EntktInternal
class CreateMutationExecutor<
    Draft : CreateMutationDraft<Entity>,
    Candidate,
    Entity : EntEntity<*>,
    BeforeSaveState,
    BeforeCreateState,
    >(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val privacyEvaluator: MutationPrivacyEvaluator<Candidate>,
    private val validationEvaluator: MutationValidationEvaluator<Candidate>,
    private val hookStateConverter:
        CreateMutationHookStateConverter<Draft, BeforeSaveState, BeforeCreateState>,
    private val beforeSaveHookRunner: MutationHookRunner<BeforeSaveState>,
    private val beforeCreateHookRunner: MutationHookRunner<BeforeCreateState>,
    private val afterCreateHookRunner: HookRunner<Entity>,
) {
    private val execution = MutationExecutionSupport(mutationRuntime)

    /** Bind an entity specification whose generated API exposes only scalar create. */
    fun operationForInternalUse(
        spec: CreateMutationSpec<Draft, Candidate, Entity>,
    ): CreateOperation<Draft, Entity> = buildOperation(
        spec = spec,
        newDraft = null,
        ownedTransaction = null,
    )

    /** Bind one generated entity specification to the reusable create operation. */
    fun operationForInternalUse(
        spec: CreateMutationSpec<Draft, Candidate, Entity>,
        newDraft: () -> Draft,
        ownedTransaction: (
            ViewerContext,
            List<Draft.() -> Unit>,
            CreateManyDisclosureCapture,
        ) -> TransactionResult<CreateManyDisclosure<Entity>>,
    ): CreateOperation<Draft, Entity> = buildOperation(
        spec = spec,
        newDraft = newDraft,
        ownedTransaction = ownedTransaction,
    )

    /** Construct the bound operation after selecting its optional bulk capability. */
    private fun buildOperation(
        spec: CreateMutationSpec<Draft, Candidate, Entity>,
        newDraft: (() -> Draft)?,
        ownedTransaction: ((
            ViewerContext,
            List<Draft.() -> Unit>,
            CreateManyDisclosureCapture,
        ) -> TransactionResult<CreateManyDisclosure<Entity>>)?,
    ): CreateOperation<Draft, Entity> = CreateOperation(
        driver = driver,
        mutationRuntime = mutationRuntime,
        entityName = spec.entity.entityName,
        newDraft = newDraft,
        executeOne = { vc, draft, checkReturnedEntityPrivacy ->
            create(vc, draft, spec, checkReturnedEntityPrivacy)
        },
        executeMany = { vc, drafts, promoteDriverNotPersisted ->
            createMany(vc, drafts, spec, promoteDriverNotPersisted)
        },
        returnedEntityDenial = { vc, entities ->
            returnedEntityDenial(vc, entities, spec.entity)
        },
        ownedTransaction = ownedTransaction,
    )

    /** Execute one create lifecycle and optionally authorize the returned entity. */
    fun create(
        viewerContext: ViewerContext,
        draft: Draft,
        spec: CreateMutationSpec<Draft, Candidate, Entity>,
        checkReturnedEntityPrivacy: Boolean,
    ): MutationResult<Entity> = execution.execute { attempt ->
        val postWriteState = if (driver.inTransaction) {
            MutationWriteState.TransactionPending
        } else {
            MutationWriteState.Committed
        }
        mutationRuntime.checkTransactionRequirement("${spec.entity.entityName} create")

        val created = executeCreate(
            attempt = attempt,
            viewerContext = viewerContext,
            drafts = listOf(draft),
            spec = spec,
            persistence = CreatePersistence.One,
            postWriteState = postWriteState,
            promoteDriverNotPersisted = false,
        )
        if (checkReturnedEntityPrivacy) {
            evaluateReturnedEntityPrivacy(attempt, viewerContext, created, spec.entity)
        }
        created.single()
    }

    /** Execute the phase-major create lifecycle for inputs in an active transaction. */
    fun createMany(
        viewerContext: ViewerContext,
        drafts: List<Draft>,
        spec: CreateMutationSpec<Draft, Candidate, Entity>,
        promoteDriverNotPersisted: Boolean,
    ): MutationResult<List<Entity>> = execution.execute { attempt ->
        require(drafts.isNotEmpty()) { "createMany requires at least one draft" }
        check(driver.inTransaction) { "createMany write phases require a transaction" }

        executeCreate(
            attempt = attempt,
            viewerContext = viewerContext,
            drafts = drafts,
            spec = spec,
            persistence = CreatePersistence.Many,
            postWriteState = MutationWriteState.TransactionPending,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        )
    }

    /** Run every scalar or batch create phase in lifecycle order. */
    private fun executeCreate(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        drafts: List<Draft>,
        spec: CreateMutationSpec<Draft, Candidate, Entity>,
        persistence: CreatePersistence,
        postWriteState: MutationWriteState,
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

        val entityName = spec.entity.entityName
        rejectRequiredInputViolations(
            attempt = attempt,
            drafts = preparationDrafts,
            entityName = entityName,
            requiredInputViolations = spec.requiredInputViolations,
        )
        val resolvedCreates = resolveDrafts(
            drafts = preparationDrafts,
            resolveDraft = spec.resolveDraft,
        )
        rejectFieldViolations(
            attempt = attempt,
            entityName = entityName,
            prepared = resolvedCreates,
            fieldViolations = spec.fieldViolations,
        )
        evaluateCreatePrivacy(
            attempt = attempt,
            entityName = entityName,
            prepared = resolvedCreates,
            viewerContext = viewerContext,
        )
        evaluateCreateValidation(
            attempt = attempt,
            entityName = entityName,
            prepared = resolvedCreates,
        )

        val createdEntities = persistCreates(
            attempt = attempt,
            prepared = resolvedCreates,
            entity = spec.entity,
            persistence = persistence,
            postWriteState = postWriteState,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        )
        afterCreateHookRunner.run(createdEntities)

        return createdEntities
    }

    /** Reject missing required inputs before resolution evaluates defaults. */
    private fun <Draft> rejectRequiredInputViolations(
        attempt: MutationAttempt,
        drafts: List<Draft>,
        entityName: String,
        requiredInputViolations: (Draft) -> List<ValidationViolation>,
    ) {
        drafts.firstNotNullOfOrNull { draft ->
            requiredInputViolations(draft).takeIf { it.isNotEmpty() }
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

    /** Resolve every draft before any field, privacy, or rule validation runs. */
    private fun <Draft> resolveDrafts(
        drafts: List<Draft>,
        resolveDraft: (Draft) -> PreparedCreate<Candidate>,
    ): List<PreparedCreate<Candidate>> = drafts.map(resolveDraft)

    /** Reject schema-field violations before CREATE privacy sees candidates. */
    private fun rejectFieldViolations(
        attempt: MutationAttempt,
        entityName: String,
        prepared: List<PreparedCreate<Candidate>>,
        fieldViolations: (Candidate) -> List<ValidationViolation>,
    ) {
        prepared.firstNotNullOfOrNull { create ->
            fieldViolations(create.candidate).takeIf { it.isNotEmpty() }
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
        attempt: MutationAttempt,
        entityName: String,
        prepared: List<PreparedCreate<Candidate>>,
        viewerContext: ViewerContext,
    ) {
        val denial = privacyEvaluator.evaluate(
            viewerContext = viewerContext,
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
        attempt: MutationAttempt,
        entityName: String,
        prepared: List<PreparedCreate<Candidate>>,
    ) {
        validationEvaluator.evaluate(prepared.map { it.candidate }).firstInvalidOrNull()?.let { invalid ->
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
        attempt: MutationAttempt,
        prepared: List<PreparedCreate<Candidate>>,
        entity: EntityMapping<Entity>,
        persistence: CreatePersistence,
        postWriteState: MutationWriteState,
        promoteDriverNotPersisted: Boolean,
    ): List<Entity> {
        val rows = try {
            when (persistence) {
                CreatePersistence.One -> listOf(
                    driver.insert(entity.table, prepared.single().values),
                )

                CreatePersistence.Many -> {
                    attempt.writeState = MutationWriteState.TransactionPending
                    driver.insertMany(entity.table, prepared.map { it.values })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallback = when (persistence) {
                CreatePersistence.One -> MutationWriteState.PersistenceUnknown
                CreatePersistence.Many -> MutationWriteState.TransactionPending
            }
            val classified = driver.classifyMutationException(
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

        check(rows.size == prepared.size) {
            "DatabaseDriver.${if (persistence == CreatePersistence.One) "insert" else "insertMany"} " +
                "contract violation for ${entity.entityName}: expected " +
                "${prepared.size} persisted rows but received ${rows.size}"
        }
        attempt.writeState = postWriteState
        return rows.map(entity::decode)
    }

    /** Apply returned-entity LOAD privacy under the context used by CREATE privacy. */
    private fun evaluateReturnedEntityPrivacy(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        created: List<Entity>,
        entity: EntityMapping<Entity>,
    ) {
        returnedEntityDenial(viewerContext, created, entity)?.let { denial ->
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = attempt.writeState,
                    entityType = entity.entityName,
                    operation = EntOperation.LOAD,
                    entityKey = denial.entityKey,
                    reason = denial.reason,
                ),
            )
        }
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

private enum class CreatePersistence {
    One,
    Many,
}
