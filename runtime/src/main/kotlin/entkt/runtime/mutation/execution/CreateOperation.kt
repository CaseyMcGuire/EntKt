@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import java.util.concurrent.CancellationException

/**
 * Entity-bound create execution used by generated repositories.
 *
 * The generated adapter supplies schema-specific draft construction, lifecycle
 * specification, and transaction-repository selection once. This type owns the
 * scalar/bulk terminal boundary, createMany transaction coordination, returned
 * LOAD disclosure, and mutation failure classification.
 */
@EntktInternal
class CreateOperation<Draft, Entity : EntEntity<*>> internal constructor(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val entityName: String,
    private val newDraft: (() -> Draft)?,
    private val executeOne: (
        ViewerContext,
        Draft,
        Boolean,
    ) -> MutationResult<Entity>,
    private val executeMany: (
        ViewerContext,
        List<Draft>,
        Boolean,
    ) -> MutationResult<List<Entity>>,
    private val returnedEntityDenial: (
        ViewerContext,
        List<Entity>,
    ) -> PrivacyDenial?,
    private val ownedTransaction: ((
        ViewerContext,
        List<Draft.() -> Unit>,
        CreateManyDisclosureCapture,
    ) -> TransactionResult<CreateManyDisclosure<Entity>>)?,
) {
    /** Execute one create lifecycle through the bound entity specification. */
    fun create(
        vc: ViewerContext,
        draft: Draft,
        checkReturnedEntityPrivacy: Boolean,
    ): MutationResult<Entity> = executeOne(vc, draft, checkReturnedEntityPrivacy)

    /**
     * Atomically create one row per block and authorize the returned batch.
     *
     * An existing caller transaction is reused. Otherwise [ownedTransaction]
     * selects the corresponding transaction-bound operation and establishes an
     * EntKt-managed transaction. Returned LOAD denial/failure remains neutral
     * until that owned boundary determines whether the writes committed.
     */
    fun createMany(
        vc: ViewerContext,
        blocks: List<Draft.() -> Unit>,
    ): MutationResult<List<Entity>> {
        var writeState = MutationWriteState.NotPersisted
        return try {
            mutationRuntime.checkTransactionRequirement(
                operation = "$entityName createMany",
                multiWrite = blocks.size > 1,
            )
            if (blocks.isEmpty()) return MutationResult.Success(emptyList())
            val blockSnapshot = blocks.toList()

            if (driver.inTransaction) {
                val completion = when (
                    val result = executeCreateManyWritePhases(
                        vc = vc,
                        blocks = blockSnapshot,
                        promoteDriverNotPersisted = true,
                    )
                ) {
                    is MutationResult.Success -> result.value
                    is MutationResult.Failed -> return result
                }
                writeState = MutationWriteState.TransactionPending
                return disclosureToMutationResult(
                    disclosure = evaluateDisclosure(vc, completion),
                    writeState = MutationWriteState.TransactionPending,
                )
            }

            val disclosureCapture = CreateManyDisclosureCapture()
            val runOwnedTransaction = checkNotNull(ownedTransaction) {
                "$entityName createMany is not configured"
            }
            when (
                val txResult = runOwnedTransaction(
                    vc,
                    blockSnapshot,
                    disclosureCapture,
                )
            ) {
                is TransactionResult.Success -> disclosureToMutationResult(
                    disclosure = txResult.value,
                    writeState = MutationWriteState.Committed,
                )

                is TransactionResult.Failed -> ownedTransactionFailure(
                    txResult,
                    disclosureCapture,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(EntUnexpectedMutationException(writeState, e))
        }
    }

    /**
     * Execute an owned transaction's write and disclosure phases.
     *
     * Write failures remain [MutationResult.Failed] so generated transaction
     * wiring can call `orRollback()`. A LOAD denial or ordinary LOAD exception
     * is deliberately returned inside [MutationResult.Success]: commit must be
     * attempted before the outer operation can assign a write state.
     */
    fun createManyInOwnedTransactionForInternalUse(
        vc: ViewerContext,
        blocks: List<Draft.() -> Unit>,
        disclosureCapture: CreateManyDisclosureCapture,
    ): MutationResult<CreateManyDisclosure<Entity>> {
        val completion = when (
            val result = executeCreateManyWritePhases(
                vc = vc,
                blocks = blocks,
                promoteDriverNotPersisted = false,
            )
        ) {
            is MutationResult.Success -> result.value
            is MutationResult.Failed -> return result
        }
        val disclosure = evaluateDisclosure(vc, completion)
        disclosureCapture.record(disclosure)
        return MutationResult.Success(disclosure)
    }

    /** Construct every draft before delegating to the shared phase-major lifecycle. */
    private fun executeCreateManyWritePhases(
        vc: ViewerContext,
        blocks: List<Draft.() -> Unit>,
        promoteDriverNotPersisted: Boolean,
    ): MutationResult<List<Entity>> {
        val drafts = try {
            check(driver.inTransaction) {
                "createMany write phases require a transaction-scoped driver"
            }
            val draftFactory = checkNotNull(newDraft) {
                "$entityName createMany is not configured"
            }
            ArrayList<Draft>(blocks.size).apply {
                for (block in blocks) add(draftFactory().apply(block))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(
                EntUnexpectedMutationException(
                    MutationWriteState.NotPersisted,
                    e,
                ),
            )
        }
        return executeMany(vc, drafts, promoteDriverNotPersisted)
    }

    /** Evaluate returned LOAD privacy without assigning a persistence outcome. */
    private fun evaluateDisclosure(
        vc: ViewerContext,
        entities: List<Entity>,
    ): CreateManyDisclosure<Entity> = try {
        returnedEntityDenial(vc, entities)?.let { CreateManyDisclosure.Denied(it) }
            ?: CreateManyDisclosure.Allowed(entities)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CreateManyDisclosure.Failed(e)
    }

    /** Project a neutral disclosure after its enclosing write state is known. */
    private fun disclosureToMutationResult(
        disclosure: CreateManyDisclosure<Entity>,
        writeState: MutationWriteState,
    ): MutationResult<List<Entity>> = when (disclosure) {
        is CreateManyDisclosure.Allowed -> MutationResult.Success(disclosure.entities)
        is CreateManyDisclosure.Denied -> fail(
            EntMutationPrivacyDeniedException(
                writeState = writeState,
                entityType = entityName,
                operation = EntOperation.LOAD,
                entityKey = disclosure.denial.entityKey,
                reason = disclosure.denial.reason,
            ),
        )

        is CreateManyDisclosure.Failed -> fail(
            EntUnexpectedMutationException(writeState, disclosure.exception),
        )
    }

    /** Apply createMany's failure precedence after an owned transaction fails. */
    private fun ownedTransactionFailure(
        txResult: TransactionResult.Failed,
        disclosureCapture: CreateManyDisclosureCapture,
    ): MutationResult<Nothing> {
        val stored = txResult.exception
        val disclosure = disclosureCapture.failure
        val denial = disclosureCapture.denial
        val exception = when {
            txResult.transactionState == TransactionFailureState.OutcomeUnknown ->
                EntUnexpectedMutationException(
                    MutationWriteState.PersistenceUnknown,
                    stored,
                ).also { unknown ->
                    denial?.let {
                        unknown.addSuppressed(
                            loadDenialException(
                                writeState = MutationWriteState.PersistenceUnknown,
                                denial = it,
                            ),
                        )
                    }
                    if (disclosure != null && disclosure !== stored) {
                        unknown.addSuppressed(disclosure)
                    }
                }

            stored is EntMutationException -> {
                val primary = if (stored.writeState == MutationWriteState.NotPersisted) {
                    stored
                } else {
                    EntUnexpectedMutationException(
                        MutationWriteState.NotPersisted,
                        unexpectedCauseOrSelf(stored),
                    )
                }
                if (primary !== stored) {
                    stored.suppressed.forEach { suppressed ->
                        if (suppressed !== primary && primary.suppressed.none { it === suppressed }) {
                            primary.addSuppressed(suppressed)
                        }
                    }
                }
                denial?.let {
                    primary.addSuppressed(
                        loadDenialException(MutationWriteState.NotPersisted, it),
                    )
                }
                if (
                    disclosure != null &&
                    disclosure !== primary &&
                    primary.suppressed.none { it === disclosure }
                ) {
                    primary.addSuppressed(disclosure)
                }
                primary
            }

            denial != null -> loadDenialException(
                MutationWriteState.NotPersisted,
                denial,
            ).also { rolledBack ->
                if (stored !== rolledBack) rolledBack.addSuppressed(stored)
            }

            disclosure != null -> EntUnexpectedMutationException(
                MutationWriteState.NotPersisted,
                disclosure,
            ).also { rolledBack ->
                if (stored !== disclosure) rolledBack.addSuppressed(stored)
            }

            else -> EntUnexpectedMutationException(
                MutationWriteState.NotPersisted,
                unexpectedCauseOrSelf(stored),
            )
        }
        return fail(exception)
    }

    private fun loadDenialException(
        writeState: MutationWriteState,
        denial: PrivacyDenial,
    ): EntMutationPrivacyDeniedException = EntMutationPrivacyDeniedException(
        writeState = writeState,
        entityType = entityName,
        operation = EntOperation.LOAD,
        entityKey = denial.entityKey,
        reason = denial.reason,
    )

    private fun unexpectedCauseOrSelf(exception: Exception): Exception =
        ((exception as? EntUnexpectedMutationException)?.cause as? Exception) ?: exception

    private fun fail(exception: EntMutationException): MutationResult<Nothing> {
        mutationRuntime.recordTransactionMutationFailure(exception)
        return MutationResult.failedForInternalUse(exception)
    }
}

/** Returned LOAD outcome held neutral until an owned transaction finishes. */
@EntktInternal
sealed interface CreateManyDisclosure<out Entity> {
    data class Allowed<Entity>(
        val entities: List<Entity>,
    ) : CreateManyDisclosure<Entity>

    data class Denied(
        val denial: PrivacyDenial,
    ) : CreateManyDisclosure<Nothing>

    data class Failed(
        val exception: Exception,
    ) : CreateManyDisclosure<Nothing>
}

/**
 * Side channel retaining a disclosure failure when the transaction boundary
 * cannot return its block value, such as when commit discovers an aborted SQL
 * transaction. Generated wiring creates one fresh capture per owned operation.
 */
@EntktInternal
class CreateManyDisclosureCapture {
    internal var denial: PrivacyDenial? = null
        private set

    internal var failure: Exception? = null
        private set

    internal fun record(disclosure: CreateManyDisclosure<*>) {
        denial = (disclosure as? CreateManyDisclosure.Denied)?.denial
        failure = (disclosure as? CreateManyDisclosure.Failed)?.exception
    }
}
