package entkt.runtime.driver

import entkt.runtime.result.TransactionFailureState

/**
 * Structured outcome of [DatabaseDriver.withTransaction]. Deliberately about
 * execution certainty, not application error policy: drivers construct
 * this but never `ReadResult`, `MutationResult`, or
 * `TransactionResult`.
 *
 * Both variants keep public constructors — the explicit exception to
 * the restricted-failure rule — because third-party driver
 * implementations are responsible for reporting authoritative
 * transaction outcomes to EntKt.
 *
 * Contract:
 * - [Success] only after commit is confirmed.
 * - Block failure with confirmed rollback →
 *   `Failed(exception, NotCommitted)`.
 * - Rollback failure → `Failed(exception, OutcomeUnknown)`.
 * - Commit failure → `Failed(commitException, OutcomeUnknown)`, even
 *   if a later rollback call appears to succeed — the failed commit
 *   may already have reached the database.
 * - Cleanup failures after a confirmed commit must never demote
 *   [Success] to a failed or unknown outcome.
 * - A `CancellationException` before commit is rethrown only after
 *   rollback is confirmed. Commit-time cancellation, or cancellation
 *   followed by an unconfirmed rollback, is
 *   `Failed(cancellation, OutcomeUnknown)`.
 * - JVM `Error`s are rolled back and rethrown because [Failed] stores
 *   only [Exception] values.
 */
sealed interface DriverTransactionResult<out T> {
    /** Commit confirmed; [value] is the block's return value. */
    data class Success<T>(
        val value: T,
    ) : DriverTransactionResult<T>

    /**
     * The transaction did not commit ([TransactionFailureState.NotCommitted],
     * rollback confirmed) or its outcome could not be established
     * ([TransactionFailureState.OutcomeUnknown]). [exception] is the
     * ordinary exception that stopped the block or boundary, with any
     * rollback/cleanup failures attached as suppressed.
     */
    data class Failed(
        val exception: Exception,
        val transactionState: TransactionFailureState,
    ) : DriverTransactionResult<Nothing>
}
