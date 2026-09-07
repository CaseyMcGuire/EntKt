@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionResult
import java.util.concurrent.CancellationException

/** Executes typed operations inside the shared transaction and failure boundary. */
@EntktInternal
class MutationExecutor(
    private val driver: DatabaseDriver,
    private val mutationRuntime: MutationRuntime,
) {
    /**
     * Check application policy, establish the operation's transaction scope, and resolve its
     * completion. Owned-transaction wiring must select the corresponding transaction-bound
     * operation and supply its read client alongside the transaction's driver.
     */
    fun <RuleClient, Input, Result> execute(
        operation: MutationOperation<RuleClient, Input, Result>,
        input: Input,
        ruleClient: RuleClient,
        ownedTransaction: ((
            Input,
            MutationCompletionCapture,
        ) -> TransactionResult<MutationCompletion<Result>>)? = null,
    ): MutationResult<Result> = capture(isOwnedTransaction = false) { execution ->
        val requirements = operation.requirements(input)
        mutationRuntime.checkTransactionRequirement(requirements.operationName, requirements.multiWrite)
        if (requirements.requiresAtomicTransaction && !execution.inTransaction) {
            val runOwnedTransaction = checkNotNull(ownedTransaction) {
                "${requirements.operationName} requires owned-transaction wiring"
            }
            executeOwned(execution, input, runOwnedTransaction)
        } else {
            complete(operation.run(execution, ruleClient, input), execution.writeState)
        }
    }

    /**
     * Run after the outer executor has checked application policy and selected a transaction.
     * Lifecycle failures remain Failed so generated wiring can apply orRollback(). Return
     * failures remain neutral until the enclosing transaction determines the write outcome.
     */
    fun <RuleClient, Input, Result> executeInOwnedTransactionForInternalUse(
        operation: MutationOperation<RuleClient, Input, Result>,
        input: Input,
        ruleClient: RuleClient,
        completionCapture: MutationCompletionCapture,
    ): MutationResult<MutationCompletion<Result>> = capture(isOwnedTransaction = true) { execution ->
        check(execution.inTransaction) { "Owned mutation execution requires a transaction-scoped driver" }
        val completion = operation.run(execution, ruleClient, input)
        completionCapture.record(completion, execution.writeState)
        MutationResult.Success(completion)
    }

    private fun <Input, Result> executeOwned(
        execution: MutationExecution,
        input: Input,
        ownedTransaction: (Input, MutationCompletionCapture) -> TransactionResult<MutationCompletion<Result>>,
    ): MutationResult<Result> {
        val completionCapture = MutationCompletionCapture()
        return when (val transaction = ownedTransaction(input, completionCapture)) {
            is TransactionResult.Success -> {
                execution.writeState = if (completionCapture.writeState == MutationWriteState.NotPersisted) {
                    MutationWriteState.NotPersisted
                } else {
                    MutationWriteState.Committed
                }
                complete(transaction.value, execution.writeState)
            }
            is TransactionResult.Failed -> fail(MutationTransactionFailure.classify(transaction, completionCapture))
        }
    }

    /** Assign a return failure the write state established by its enclosing execution boundary. */
    private fun <Result> complete(
        completion: MutationCompletion<Result>,
        writeState: MutationWriteState,
    ): MutationResult<Result> = when (completion) {
        is MutationCompletion.Ready -> MutationResult.Success(completion.value)
        is MutationCompletion.ReturnDenied -> fail(
            EntMutationPrivacyDeniedException(
                writeState = writeState,
                entityType = completion.denial.entityType,
                operation = EntOperation.LOAD,
                entityKey = completion.denial.entityKey,
                reason = completion.denial.reason,
            ),
        )
        is MutationCompletion.ReturnFailed -> fail(EntUnexpectedMutationException(writeState, completion.cause))
    }

    private fun <Result> capture(
        isOwnedTransaction: Boolean,
        block: (MutationExecution) -> MutationResult<Result>,
    ): MutationResult<Result> {
        var execution: MutationExecution? = null
        return try {
            val inTransaction = driver.inTransaction
            val currentExecution = MutationExecution(
                driver = driver,
                successfulWriteState = if (inTransaction) {
                    MutationWriteState.TransactionPending
                } else {
                    MutationWriteState.Committed
                },
                isOwnedTransaction = isOwnedTransaction,
            )
            execution = currentExecution
            block(currentExecution)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MutationExecution.Rejected) {
            fail(e.exception)
        } catch (e: Exception) {
            fail(
                EntUnexpectedMutationException(
                    execution?.writeState ?: MutationWriteState.NotPersisted,
                    e,
                ),
            )
        }
    }

    private fun fail(exception: EntMutationException): MutationResult<Nothing> {
        mutationRuntime.recordTransactionMutationFailure(exception)
        return MutationResult.failedForInternalUse(exception)
    }
}
