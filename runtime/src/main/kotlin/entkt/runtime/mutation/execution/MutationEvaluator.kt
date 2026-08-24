@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.hook.BatchHook
import entkt.runtime.mutation.CreatePreparation
import entkt.runtime.mutation.PreparedCreate
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
class MutationEvaluator<PrivacyClient, ValidationClient>(
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

        val lifecycle = CreateLifecycle(
            attempt = attempt,
            inputs = listOf(input),
            spec = spec,
            persistence = CreatePersistence.One,
            postWriteState = postWriteState,
            promoteDriverNotPersisted = false,
        )
        val created = lifecycle.run()
        if (checkReturnedEntityPrivacy) {
            lifecycle.evaluateReturnedEntityPrivacy(created)
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

        CreateLifecycle(
            attempt = attempt,
            inputs = inputs,
            spec = spec,
            persistence = CreatePersistence.Many,
            postWriteState = MutationWriteState.TransactionPending,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        ).run()
    }

    /** State and phases for one scalar or batch create evaluation. */
    private inner class CreateLifecycle<
        Draft,
        BeforeSave,
        BeforeCreate,
        PrivacyItem,
        ValidationItem,
        Entity : EntEntity<*>,
        >(
        private val attempt: MutationAttempt,
        private val inputs: List<CreateMutationInput<Draft, BeforeSave, BeforeCreate>>,
        private val spec: CreateMutationSpec<
            Draft, BeforeSave, BeforeCreate, PrivacyItem, ValidationItem,
            Entity, PrivacyClient, ValidationClient,
        >,
        private val persistence: CreatePersistence,
        private val postWriteState: MutationWriteState,
        private val promoteDriverNotPersisted: Boolean,
    ) {
        /** Run every create phase in lifecycle order. */
        fun run(): CreateMutationOutput<Entity> {
            runHooks(inputs.map { it.beforeSave }, spec.beforeSave)
            runHooks(inputs.map { it.beforeCreate }, spec.beforeCreate)

            val resolvedCreates = resolveDrafts()
            val privacyContext = mutationRuntime.get()

            evaluateCreatePrivacy(resolvedCreates, privacyContext)
            evaluateCreateValidation(resolvedCreates)

            val createdEntities = persistCreates(resolvedCreates)
            runHooks(createdEntities, spec.afterCreate)

            return CreateMutationOutput(
                entities = createdEntities,
                privacyContext = privacyContext,
            )
        }

        /** Apply returned-entity LOAD privacy under the context used by CREATE privacy. */
        fun evaluateReturnedEntityPrivacy(created: CreateMutationOutput<Entity>) {
            val evaluations = mutationRuntime.evaluate(
                entity = spec.entity,
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
                        entityType = spec.entity.entityName,
                        operation = EntOperation.LOAD,
                        entityKey = denial.entityKey,
                        reason = denial.reason,
                    ),
                )
            }
        }

        private fun <Value> runHooks(
            values: List<Value>,
            hooks: List<BatchHook<Value>>,
        ) {
            if (values.isEmpty()) return

            for (hook in hooks) {
                hook.runBatch(values)
            }
        }

        private fun resolveDrafts(): List<PreparedCreate<PrivacyItem, ValidationItem>> =
            inputs.map { input ->
                when (val preparation = spec.resolveDraft(input.draft)) {
                    is CreatePreparation.Ready -> preparation.value
                    is CreatePreparation.Invalid -> attempt.reject(
                        EntValidationException(
                            entityType = spec.entity.entityName,
                            operation = EntOperation.CREATE,
                            violations = preparation.violations,
                        ),
                    )
                }
            }

        private fun evaluateCreatePrivacy(
            prepared: List<PreparedCreate<PrivacyItem, ValidationItem>>,
            privacyContext: PrivacyContext,
        ) {
            val decisions = when {
                privacyContext.viewer is Viewer.PrivacyBypass ->
                    List(prepared.size) { PrivacyDecision.Allow }

                spec.privacyRules.isEmpty() ->
                    List(prepared.size) { PrivacyDecision.Continue }

                else -> evaluateBatchPrivacyRulesForInternalUse(
                    lifecycle = "${spec.entity.entityName} CREATE privacy",
                    items = prepared,
                    rules = spec.privacyRules,
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
                        entityType = spec.entity.entityName,
                        operation = EntOperation.CREATE,
                        entityKey = null,
                        reason = reason,
                    ),
                )
            }
        }

        private fun evaluateCreateValidation(
            prepared: List<PreparedCreate<PrivacyItem, ValidationItem>>,
        ) {
            if (spec.validationRules.isEmpty()) return

            val invalidsByCandidate = evaluateBatchValidationRulesForInternalUse(
                lifecycle = "${spec.entity.entityName} CREATE validation",
                items = prepared,
                rules = spec.validationRules,
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
                        entityType = spec.entity.entityName,
                        operation = EntOperation.CREATE,
                        violations = invalids.map { it.toValidationViolation() },
                    ),
                )
            }
        }

        private fun persistCreates(
            prepared: List<PreparedCreate<PrivacyItem, ValidationItem>>,
        ): List<Entity> {
            val rows = try {
                when (persistence) {
                    CreatePersistence.One -> listOf(
                        driver.insert(spec.entity.table, prepared.single().values),
                    )

                    CreatePersistence.Many -> {
                        attempt.writeState = MutationWriteState.TransactionPending
                        driver.insertMany(spec.entity.table, prepared.map { it.values })
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
                    spec.entity.entityName,
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
                    "contract violation for ${spec.entity.entityName}: expected " +
                    "${prepared.size} persisted rows but received ${rows.size}"
            }
            attempt.writeState = postWriteState
            return rows.map(spec.entity::decode)
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
     * Mutable state for one mutation evaluation. Every phase updates the same [writeState], and
     * the outer exception boundary reads its latest value to report whether persistence did not
     * begin, may have taken effect, or remains pending in a transaction. This state is passed
     * explicitly rather than stored on [MutationEvaluator] so concurrent mutations evaluated by
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
