@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import java.util.concurrent.CancellationException

/**
 * Entity-bound delete execution used by generated repositories.
 *
 * Generated code supplies its schema specification and transaction-repository
 * selection once. This type owns scalar delegation, deleteMany transaction
 * coordination, and transaction failure classification.
 */
@EntktInternal
class DeleteOperation<Entity : EntEntity<*>> internal constructor(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
    private val entityName: String,
    private val executeEntity: (
        ViewerContext,
        Entity,
    ) -> MutationResult<Unit>,
    private val executeId: (
        ViewerContext,
        Any,
    ) -> MutationResult<Boolean>,
    private val executeMany: (
        ViewerContext,
        List<Predicate<Entity>>,
        Boolean,
    ) -> MutationResult<Int>,
    private val ownedTransaction: (
        ViewerContext,
        List<Predicate<Entity>>,
    ) -> TransactionResult<Int>,
) {
    /** Delete by entity handle through the bound entity specification. */
    fun delete(
        vc: ViewerContext,
        entity: Entity,
    ): MutationResult<Unit> = executeEntity(vc, entity)

    /** Reload and idempotently delete one row through the bound specification. */
    fun deleteById(
        vc: ViewerContext,
        id: Any,
    ): MutationResult<Boolean> = executeId(vc, id)

    /** Atomically delete every row matching [predicates]. */
    fun deleteMany(
        vc: ViewerContext,
        predicates: List<Predicate<Entity>>,
    ): MutationResult<Int> = try {
        mutationRuntime.checkTransactionRequirement(
            operation = "$entityName deleteMany",
            multiWrite = true,
        )
        val predicateSnapshot = predicates.toList()
        if (driver.inTransaction) {
            executeMany(
                vc,
                predicateSnapshot,
                true,
            )
        } else {
            when (val txResult = ownedTransaction(vc, predicateSnapshot)) {
                is TransactionResult.Success -> MutationResult.Success(txResult.value)
                is TransactionResult.Failed -> transactionFailure(txResult)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(EntUnexpectedMutationException(MutationWriteState.NotPersisted, e))
    }

    /**
     * Execute deleteMany through the transaction-bound specification.
     * Generated transaction wiring applies `orRollback()` to the result.
     */
    fun deleteManyInOwnedTransactionForInternalUse(
        vc: ViewerContext,
        predicates: List<Predicate<Entity>>,
    ): MutationResult<Int> = executeMany(
        vc,
        predicates,
        false,
    )

    /** Reclassify an owned transaction failure from its completed boundary. */
    private fun transactionFailure(
        txResult: TransactionResult.Failed,
    ): MutationResult<Nothing> {
        val stored = txResult.exception
        val exception = when {
            txResult.transactionState == TransactionFailureState.OutcomeUnknown ->
                EntUnexpectedMutationException(
                    MutationWriteState.PersistenceUnknown,
                    stored,
                )

            stored is EntMutationException &&
                stored.writeState == MutationWriteState.NotPersisted -> stored

            else -> EntUnexpectedMutationException(
                MutationWriteState.NotPersisted,
                ((stored as? EntUnexpectedMutationException)?.cause as? Exception) ?: stored,
            )
        }
        return fail(exception)
    }

    private fun fail(exception: EntMutationException): MutationResult<Nothing> {
        mutationRuntime.recordTransactionMutationFailure(exception)
        return MutationResult.failedForInternalUse(exception)
    }
}
