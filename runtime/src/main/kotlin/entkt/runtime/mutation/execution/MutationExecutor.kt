@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.BatchHook
import entkt.runtime.mutation.CreatePreparation
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.evaluateBatchPrivacyRulesForInternalUse
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.toValidationViolation
import entkt.runtime.validation.BatchValidationRule
import entkt.runtime.validation.ValidationRuleContext
import entkt.runtime.validation.evaluateBatchValidationRulesForInternalUse
import java.util.concurrent.CancellationException

/**
 * Runs mutation lifecycles for generated drafts and entity specifications.
 *
 * A create runs before hooks, resolves every draft, evaluates CREATE privacy,
 * evaluates CREATE validation, persists the rows, runs after hooks, and then
 * applies returned-entity LOAD privacy when the terminal exposes entities.
 */
@EntktInternal
class MutationExecutor<PrivacyClient, ValidationClient>(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime<PrivacyClient, ValidationClient>,
) {
    /** Execute one create lifecycle and optionally authorize the returned entity. */
    fun <
        Draft,
        BeforeSave,
        BeforeCreate,
        PrivacyItem,
        ValidationItem,
        Entity : EntEntity<*>,
        > create(
        input: CreateMutationInput<Draft, BeforeSave, BeforeCreate>,
        spec: CreateMutationSpec<
            Draft, BeforeSave, BeforeCreate, PrivacyItem, ValidationItem,
            Entity, PrivacyClient, ValidationClient,
        >,
        checkReturnedEntityPrivacy: Boolean,
    ): MutationResult<Entity> = executeMutation { attempt ->
        val postWriteState = if (driver.inTransaction) {
            MutationWriteState.TransactionPending
        } else {
            MutationWriteState.Committed
        }
        mutationRuntime.checkTransactionRequirement("${spec.entity.entityName} create")

        val created = executeCreate(
            attempt = attempt,
            inputs = listOf(input),
            spec = spec,
            persistence = CreatePersistence.One,
            postWriteState = postWriteState,
            promoteDriverNotPersisted = false,
        )
        if (checkReturnedEntityPrivacy) {
            evaluateReturnedEntityPrivacy(attempt, created, spec.entity)
        }
        created.entities.single()
    }

    /** Enforce transaction policy before createMany chooses or opens its transaction. */
    fun checkCreateManyTransactionRequirement(entityName: String, numberOfBuilders: Int) {
        mutationRuntime.checkTransactionRequirement(
            operation = "$entityName createMany",
            multiWrite = numberOfBuilders > 1,
        )
    }

    /** Execute the phase-major create lifecycle for inputs in an active transaction. */
    fun <
        Draft,
        BeforeSave,
        BeforeCreate,
        PrivacyItem,
        ValidationItem,
        Entity : EntEntity<*>,
        > createMany(
        inputs: List<CreateMutationInput<Draft, BeforeSave, BeforeCreate>>,
        spec: CreateMutationSpec<
            Draft, BeforeSave, BeforeCreate, PrivacyItem, ValidationItem,
            Entity, PrivacyClient, ValidationClient,
        >,
        promoteDriverNotPersisted: Boolean,
    ): MutationResult<CreateMutationOutput<Entity>> = executeMutation { attempt ->
        require(inputs.isNotEmpty()) { "createMany requires at least one input" }
        check(driver.inTransaction) { "createMany write phases require a transaction" }

        executeCreate(
            attempt = attempt,
            inputs = inputs,
            spec = spec,
            persistence = CreatePersistence.Many,
            postWriteState = MutationWriteState.TransactionPending,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        )
    }

    /** Run every scalar or batch create phase in lifecycle order. */
    private fun <
        Draft,
        BeforeSave,
        BeforeCreate,
        PrivacyItem,
        ValidationItem,
        Entity : EntEntity<*>,
        > executeCreate(
        attempt: MutationAttempt,
        inputs: List<CreateMutationInput<Draft, BeforeSave, BeforeCreate>>,
        spec: CreateMutationSpec<
            Draft, BeforeSave, BeforeCreate, PrivacyItem, ValidationItem,
            Entity, PrivacyClient, ValidationClient,
        >,
        persistence: CreatePersistence,
        postWriteState: MutationWriteState,
        promoteDriverNotPersisted: Boolean,
    ): CreateMutationOutput<Entity> {
        runHooks(inputs.map { it.beforeSave }, spec.beforeSave)
        runHooks(inputs.map { it.beforeCreate }, spec.beforeCreate)

        val entityName = spec.entity.entityName
        val resolvedCreates = resolveDrafts(
            attempt = attempt,
            drafts = inputs.map { it.draft },
            entityName = entityName,
            resolveDraft = spec.resolveDraft,
        )
        val privacyContext = mutationRuntime.get()

        evaluateCreatePrivacy(
            attempt = attempt,
            entityName = entityName,
            prepared = resolvedCreates,
            privacyContext = privacyContext,
            rules = spec.privacyRules,
        )
        evaluateCreateValidation(
            attempt = attempt,
            entityName = entityName,
            prepared = resolvedCreates,
            rules = spec.validationRules,
        )

        val createdEntities = persistCreates(
            attempt = attempt,
            prepared = resolvedCreates,
            entity = spec.entity,
            persistence = persistence,
            postWriteState = postWriteState,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        )
        runHooks(createdEntities, spec.afterCreate)

        return CreateMutationOutput(
            entities = createdEntities,
            privacyContext = privacyContext,
        )
    }

    /** Run each hook once with the same non-empty ordered batch. */
    private fun <Value> runHooks(
        values: List<Value>,
        hooks: List<BatchHook<Value>>,
    ) {
        if (values.isEmpty()) return

        for (hook in hooks) {
            hook.runBatch(values)
        }
    }

    /** Resolve every draft before any privacy or entity-level validation runs. */
    private fun <Draft, PrivacyItem, ValidationItem> resolveDrafts(
        attempt: MutationAttempt,
        drafts: List<Draft>,
        entityName: String,
        resolveDraft: (Draft) -> CreatePreparation<PrivacyItem, ValidationItem>,
    ): List<PreparedCreate<PrivacyItem, ValidationItem>> = drafts.map { draft ->
        when (val preparation = resolveDraft(draft)) {
            is CreatePreparation.Ready -> preparation.value
            is CreatePreparation.Invalid -> attempt.reject(
                EntValidationException(
                    entityType = entityName,
                    operation = EntOperation.CREATE,
                    violations = preparation.violations,
                ),
            )
        }
    }

    /** Reject the entire create operation when any candidate is denied. */
    private fun <PrivacyItem, ValidationItem> evaluateCreatePrivacy(
        attempt: MutationAttempt,
        entityName: String,
        prepared: List<PreparedCreate<PrivacyItem, ValidationItem>>,
        privacyContext: PrivacyContext,
        rules: List<BatchPrivacyRule<PrivacyClient, PrivacyItem>>,
    ) {
        val decisions = when {
            privacyContext.viewer is Viewer.PrivacyBypass ->
                List(prepared.size) { PrivacyDecision.Allow }

            rules.isEmpty() ->
                List(prepared.size) { PrivacyDecision.Continue }

            else -> evaluateBatchPrivacyRulesForInternalUse(
                lifecycle = "$entityName CREATE privacy",
                items = prepared,
                rules = rules,
                context = PrivacyRuleContext(
                    privacyContext,
                    mutationRuntime.privacyRuleClient(privacyContext),
                ),
                freshItem = PreparedCreate<PrivacyItem, ValidationItem>::freshPrivacyItem,
            )
        }
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
    private fun <PrivacyItem, ValidationItem> evaluateCreateValidation(
        attempt: MutationAttempt,
        entityName: String,
        prepared: List<PreparedCreate<PrivacyItem, ValidationItem>>,
        rules: List<BatchValidationRule<ValidationClient, ValidationItem>>,
    ) {
        if (rules.isEmpty()) return

        val invalidsByCandidate = evaluateBatchValidationRulesForInternalUse(
            lifecycle = "$entityName CREATE validation",
            items = prepared,
            rules = rules,
            context = ValidationRuleContext(mutationRuntime.validationRuleClient()),
            freshItem = PreparedCreate<PrivacyItem, ValidationItem>::freshValidationItem,
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
    private fun <PrivacyItem, ValidationItem, Entity : EntEntity<*>> persistCreates(
        attempt: MutationAttempt,
        prepared: List<PreparedCreate<PrivacyItem, ValidationItem>>,
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
        created: CreateMutationOutput<Entity>,
        entity: EntityMapping<Entity>,
    ) {
        val evaluations = mutationRuntime.evaluate(
            entity = entity,
            privacyContext = created.privacyContext,
            entities = created.entities,
        )
        check(evaluations.size == created.entities.size) {
            "LOAD privacy returned ${evaluations.size} decisions for " +
                "${created.entities.size} created entities"
        }
        evaluations.firstNotNullOfOrNull { it.denialOrNull() }?.let { denial ->
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

    /** Run one mutation under the common cancellation, failure, and write-state boundary. */
    private inline fun <Result> executeMutation(
        block: (MutationAttempt) -> Result,
    ): MutationResult<Result> {
        val attempt = MutationAttempt()
        return try {
            MutationResult.Success(block(attempt))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MutationRejected) {
            fail(e.exception)
        } catch (e: Exception) {
            fail(EntUnexpectedMutationException(attempt.writeState, e))
        }
    }

    /**
     * Mutable state for one mutation execution. Every phase updates the same [writeState], and
     * the outer exception boundary reads its latest value to report whether persistence did not
     * begin, may have taken effect, or remains pending in a transaction. This state is passed
     * explicitly rather than stored on [MutationExecutor] so concurrent mutations executed by
     * the same client cannot overwrite one another's failure classification.
     */
    private inner class MutationAttempt {
        var writeState: MutationWriteState = MutationWriteState.NotPersisted

        fun reject(exception: EntMutationException): Nothing = throw MutationRejected(exception)
    }

    private fun fail(exception: EntMutationException): MutationResult<Nothing> {
        mutationRuntime.recordTransactionMutationFailure(exception)
        return MutationResult.failedForInternalUse(exception)
    }
}

private enum class CreatePersistence {
    One,
    Many,
}

private class MutationRejected(
    val exception: EntMutationException,
) : RuntimeException(exception)
