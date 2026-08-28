@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.runBatchHooksForInternalUse
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.ValidationViolation
import entkt.runtime.result.toValidationViolation
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
class CreateMutationExecutor<RuleClient>(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val ruleClient: RuleClient,
) {
    private val execution = MutationExecutionSupport(mutationRuntime)

    /** Bind an entity specification whose generated API exposes only scalar create. */
    fun <Draft, Candidate, Entity : EntEntity<*>> operationForInternalUse(
        spec: CreateMutationSpec<Draft, Candidate, Entity, RuleClient>,
    ): CreateOperation<Draft, Entity> = buildOperation(
        spec = spec,
        newDraft = null,
        ownedTransaction = null,
    )

    /** Bind one generated entity specification to the reusable create operation. */
    fun <Draft, Candidate, Entity : EntEntity<*>> operationForInternalUse(
        spec: CreateMutationSpec<Draft, Candidate, Entity, RuleClient>,
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
    private fun <Draft, Candidate, Entity : EntEntity<*>> buildOperation(
        spec: CreateMutationSpec<Draft, Candidate, Entity, RuleClient>,
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
    fun <
        Draft,
        Candidate,
        Entity : EntEntity<*>,
        > create(
        viewerContext: ViewerContext,
        draft: Draft,
        spec: CreateMutationSpec<
            Draft, Candidate, Entity, RuleClient,
        >,
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
    fun <
        Draft,
        Candidate,
        Entity : EntEntity<*>,
        > createMany(
        viewerContext: ViewerContext,
        drafts: List<Draft>,
        spec: CreateMutationSpec<
            Draft, Candidate, Entity, RuleClient,
        >,
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
    private fun <
        Draft,
        Candidate,
        Entity : EntEntity<*>,
        > executeCreate(
        attempt: MutationAttempt,
        viewerContext: ViewerContext,
        drafts: List<Draft>,
        spec: CreateMutationSpec<
            Draft, Candidate, Entity, RuleClient,
        >,
        persistence: CreatePersistence,
        postWriteState: MutationWriteState,
        promoteDriverNotPersisted: Boolean,
    ): List<Entity> {
        spec.beforeSave.run(viewerContext, drafts)
        spec.beforeCreate.run(viewerContext, drafts)

        val entityName = spec.entity.entityName
        rejectRequiredInputViolations(
            attempt = attempt,
            drafts = drafts,
            entityName = entityName,
            requiredInputViolations = spec.requiredInputViolations,
        )
        val resolvedCreates = resolveDrafts(
            drafts = drafts,
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
            privacy = spec.privacy,
        )
        evaluateCreateValidation(
            attempt = attempt,
            entityName = entityName,
            prepared = resolvedCreates,
            validation = spec.validation,
        )

        val createdEntities = persistCreates(
            attempt = attempt,
            prepared = resolvedCreates,
            entity = spec.entity,
            persistence = persistence,
            postWriteState = postWriteState,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        )
        runBatchHooksForInternalUse(createdEntities, spec.afterCreate)

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
    private fun <Draft, Candidate> resolveDrafts(
        drafts: List<Draft>,
        resolveDraft: (Draft) -> PreparedCreate<Candidate>,
    ): List<PreparedCreate<Candidate>> = drafts.map(resolveDraft)

    /** Reject schema-field violations before CREATE privacy sees candidates. */
    private fun <Candidate> rejectFieldViolations(
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
    private fun <Candidate> evaluateCreatePrivacy(
        attempt: MutationAttempt,
        entityName: String,
        prepared: List<PreparedCreate<Candidate>>,
        viewerContext: ViewerContext,
        privacy: MutationPrivacyPhase<RuleClient, Candidate>,
    ) {
        val decisions = privacy.evaluate(
            viewerContext = viewerContext,
            ruleClient = ruleClient,
            candidates = prepared.map { it.candidate },
        )
        check(decisions.size == prepared.size) {
            "CREATE privacy returned ${decisions.size} decisions for " +
                "${prepared.size} candidates"
        }
        val denialReason = decisions.firstNotNullOfOrNull { decision ->
            when (decision) {
                PrivacyDecision.Allow -> null
                is PrivacyDecision.Deny -> decision.reason
                PrivacyDecision.Continue -> "no create rule allowed access"
            }
        }
        denialReason?.let { reason ->
            attempt.reject(
                EntMutationPrivacyDeniedException(
                    writeState = MutationWriteState.NotPersisted,
                    entityType = entityName,
                    operation = EntOperation.CREATE,
                    entityKey = null,
                    reason = reason,
                ),
            )
        }
    }

    /** Reject the entire create operation when any candidate is invalid. */
    private fun <Candidate> evaluateCreateValidation(
        attempt: MutationAttempt,
        entityName: String,
        prepared: List<PreparedCreate<Candidate>>,
        validation: MutationValidationPhase<RuleClient, Candidate>,
    ) {
        val invalidsByCandidate = validation.evaluate(
            ruleClient = ruleClient,
            candidates = prepared.map { it.candidate },
        )
        check(invalidsByCandidate.size == prepared.size) {
            "CREATE validation returned ${invalidsByCandidate.size} results for " +
                "${prepared.size} candidates"
        }
        invalidsByCandidate.firstOrNull { it.isNotEmpty() }?.let { invalids ->
            attempt.reject(
                EntValidationException(
                    entityType = entityName,
                    operation = EntOperation.CREATE,
                    violations = invalids.map { it.toValidationViolation() },
                ),
            )
        }
    }

    /** Persist prepared rows with the scalar or batch driver primitive. */
    private fun <Candidate, Entity : EntEntity<*>> persistCreates(
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
    private fun <Entity : EntEntity<*>> evaluateReturnedEntityPrivacy(
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
    private fun <Entity : EntEntity<*>> returnedEntityDenial(
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
        return evaluations.firstNotNullOfOrNull { it.denialOrNull() }
    }
}

private enum class CreatePersistence {
    One,
    Many,
}
