package entkt.runtime.result

/**
 * A framework-classified concurrency conflict, including database conflicts
 * and optimistic-concurrency rejections. Shared by mutation and non-mutation
 * exceptions without erasing their distinct payloads or mutation write states.
 *
 * This marker does not establish the transaction's final outcome or make an
 * operation safe to retry. Inspect [TransactionResult.Failed.transactionState]
 * at the transaction boundary and [EntMutationException.writeState] at a mutation
 * boundary; external side effects may need separate handling.
 */
sealed interface EntConflictFailure
