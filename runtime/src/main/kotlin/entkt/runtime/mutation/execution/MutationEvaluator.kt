@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.hook.BatchHook
import entkt.runtime.mutation.CreatePreparation
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import java.util.concurrent.CancellationException

/** Runs mutation lifecycles for generated drafts and entity specifications. */
@EntktInternal
class MutationEvaluator(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
) {
    /** Execute one create lifecycle and optionally authorize the returned entity. */
    fun <Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> create(
        draft: Draft,
        spec: CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity>,
        checkReturnedEntityPrivacy: Boolean,
    ): MutationResult<Entity> = executeMutation { attempt ->
        val postWriteState = if (driver.inTransaction) {
            MutationWriteState.TransactionPending
        } else {
            MutationWriteState.Committed
        }
        mutationRuntime.checkTransactionRequirement("${spec.entity.entityName} create")

        val created = runCreateLifecycle(
            attempt = attempt,
            drafts = listOf(draft),
            spec = spec,
            persistence = CreatePersistence.One,
            postWriteState = postWriteState,
            promoteDriverNotPersisted = false,
        )
        if (checkReturnedEntityPrivacy) {
            authorizeReturnedEntities(attempt, created, spec)
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
    fun <Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> createMany(
        drafts: List<Draft>,
        spec: CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity>,
        promoteDriverNotPersisted: Boolean,
    ): MutationResult<CreateMutationOutput<Entity>> = executeMutation { attempt ->
        require(drafts.isNotEmpty()) { "createMany requires at least one draft" }
        check(driver.inTransaction) { "createMany write phases require a transaction" }

        runCreateLifecycle(
            attempt = attempt,
            drafts = drafts,
            spec = spec,
            persistence = CreatePersistence.Many,
            postWriteState = MutationWriteState.TransactionPending,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        )
    }

    /** Run the lifecycle phases shared by one-item and multi-item creates. */
    private fun <Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> runCreateLifecycle(
        attempt: MutationAttempt,
        drafts: List<Draft>,
        spec: CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity>,
        persistence: CreatePersistence,
        postWriteState: MutationWriteState,
        promoteDriverNotPersisted: Boolean,
    ): CreateMutationOutput<Entity> {
        runHooks(
            drafts.map(spec.beforeSaveHookValue),
            spec.beforeSaveHooks,
        )

        runHooks(
            drafts.map(spec.beforeCreateHookValue),
            spec.beforeCreateHooks,
        )

        val preparedCreates = prepareCreates(
            attempt = attempt,
            drafts = drafts,
            spec = spec,
        )
        val privacyContext = mutationRuntime.get()

        authorizeCreates(
            attempt = attempt,
            prepared = preparedCreates,
            privacyContext = privacyContext,
            spec = spec,
        )
        validateCreates(
            attempt = attempt,
            prepared = preparedCreates,
            spec = spec,
        )

        val createdEntities = persistCreates(
            attempt = attempt,
            prepared = preparedCreates,
            spec = spec,
            persistence = persistence,
            postWriteState = postWriteState,
            promoteDriverNotPersisted = promoteDriverNotPersisted,
        )

        runHooks(createdEntities, spec.afterCreateHooks)

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

    /** Normalize every input before any privacy or entity-level validation runs. */
    private fun <Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> prepareCreates(
        attempt: MutationAttempt,
        drafts: List<Draft>,
        spec: CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity>,
    ): List<PreparedCreate<Candidate>> = drafts.map { draft ->
        val preparation = spec.resolve(draft)
        when (preparation) {
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

    /** Reject the entire create operation when any candidate is denied. */
    private fun <Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> authorizeCreates(
        attempt: MutationAttempt,
        prepared: List<PreparedCreate<Candidate>>,
        privacyContext: PrivacyContext,
        spec: CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity>,
    ) {
        val denialReasons = spec.createDenialReasons(
            privacyContext,
            prepared.map { it.candidate },
        )
        check(denialReasons.size == prepared.size) {
            "CREATE privacy returned ${denialReasons.size} decisions for " +
                "${prepared.size} candidates"
        }
        denialReasons.firstOrNull { it != null }?.let { reason ->
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

    /** Reject the entire create operation when any candidate is invalid. */
    private fun <Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> validateCreates(
        attempt: MutationAttempt,
        prepared: List<PreparedCreate<Candidate>>,
        spec: CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity>,
    ) {
        val violationsByCandidate = spec.validationViolations(prepared.map { it.candidate })
        check(violationsByCandidate.size == prepared.size) {
            "CREATE validation returned ${violationsByCandidate.size} results for " +
                "${prepared.size} candidates"
        }
        violationsByCandidate.firstOrNull { it.isNotEmpty() }?.let { violations ->
            attempt.reject(
                EntValidationException(
                    entityType = spec.entity.entityName,
                    operation = EntOperation.CREATE,
                    violations = violations,
                ),
            )
        }
    }

    /** Persist prepared rows with the one-item or multi-item driver primitive. */
    private fun <Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> persistCreates(
        attempt: MutationAttempt,
        prepared: List<PreparedCreate<Candidate>>,
        spec: CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity>,
        persistence: CreatePersistence,
        postWriteState: MutationWriteState,
        promoteDriverNotPersisted: Boolean,
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
                EntUnexpectedMutationException(MutationWriteState.TransactionPending, classified)
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

    /** Apply returned-entity LOAD privacy under the context captured before CREATE privacy. */
    private fun <Draft, BeforeSave, BeforeCreate, Candidate, Entity : EntEntity<*>> authorizeReturnedEntities(
        attempt: MutationAttempt,
        created: CreateMutationOutput<Entity>,
        spec: CreateMutationSpec<Draft, BeforeSave, BeforeCreate, Candidate, Entity>,
    ) {
        val denials = spec.loadDenials(
            created.privacyContext,
            created.entities,
        )
        check(denials.size == created.entities.size) {
            "LOAD privacy returned ${denials.size} decisions for " +
                "${created.entities.size} created entities"
        }
        denials.firstOrNull { it != null }?.let { denial ->
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
