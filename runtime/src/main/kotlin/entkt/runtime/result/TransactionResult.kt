@file:OptIn(EntktInternal::class)

package entkt.runtime.result

import entkt.query.EntktInternal
import entkt.runtime.driver.Driver
import entkt.runtime.driver.DriverTransactionResult

/**
 * The canonical exhaustive result of the client transaction boundary.
 * Transactions use a deliberately small algebra because a block may
 * combine reads, every mutation kind, and arbitrary application code.
 *
 * `Success(value)` is returned only after commit is confirmed.
 * [Failed] deliberately does not return the block's value; its
 * [Failed.transactionState] is structurally limited to
 * [TransactionFailureState.NotCommitted] (rollback confirmed) and
 * [TransactionFailureState.OutcomeUnknown] (neither commit nor
 * rollback could be confirmed).
 */
sealed interface TransactionResult<out T> {
    /** The transaction committed; [value] is the block's return value. */
    data class Success<T>(
        val value: T,
    ) : TransactionResult<T>

    /**
     * The transaction did not commit, or its outcome is unknown.
     * [exception] is the failure that stopped the block or boundary;
     * only EntKt constructs this variant.
     */
    @ConsistentCopyVisibility
    data class Failed internal constructor(
        val exception: Exception,
        val transactionState: TransactionFailureState,
    ) : TransactionResult<Nothing>

    companion object {
        /**
         * Constructs [Failed] for generated code, which compiles in
         * the application module and cannot reach the internal
         * constructor. Guarded by the error-level [EntktInternal]
         * opt-in; also the initial test-fixture path.
         */
        @EntktInternal
        fun failedForInternalUse(
            exception: Exception,
            transactionState: TransactionFailureState,
        ): TransactionResult<Nothing> = Failed(exception, transactionState)
    }
}

/**
 * Thrown by [TransactionResult.getOrThrow]. The wrapper is required —
 * the projection asymmetry is intentional: mutation failures already
 * store a state-bearing framework exception, while a transaction
 * block's stored exception is arbitrary and cannot itself carry
 * [transactionState]; rethrowing it alone would discard whether
 * rollback was confirmed or the outcome is unknown. The stored failure
 * is exposed through the non-null [exception] property and also used
 * as the standard exception cause.
 */
class EntTransactionFailedException(
    val transactionState: TransactionFailureState,
    val exception: Exception,
) : EntException("Transaction failed with state $transactionState", exception)

/**
 * Thrown when `withTransaction()` is called on a transaction-scoped
 * client or driver, before the nested block or any nested transaction
 * I/O runs. Savepoint, reuse, and nested-rejection options belong to
 * a separate transaction-client design.
 */
class NestedTransactionUnsupportedException : IllegalStateException(
    "Nested withTransaction() is not supported",
)

/**
 * Return the committed block value or throw
 * [EntTransactionFailedException] preserving [TransactionResult.Failed.transactionState]
 * with the stored exception as its non-null `exception` property and
 * cause. Performs no I/O and no additional transaction or driver work.
 * There is no transaction `orNull()` projection.
 *
 * **This is a propagation convenience, not a retry policy.** Blanket
 * `retry { withTransaction { ... }.getOrThrow() }` is unsafe:
 * [TransactionFailureState.OutcomeUnknown] means the managed database
 * transaction may have committed, and
 * [TransactionFailureState.NotCommitted] confirms only that the
 * managed transaction did not commit — the block may already have
 * performed external side effects or writes through another client
 * that the transaction could not roll back. Retry only when the block
 * and every side effect are deliberately idempotent or otherwise
 * retry-safe.
 */
fun <T> TransactionResult<T>.getOrThrow(): T = when (this) {
    is TransactionResult.Success<T> -> value
    is TransactionResult.Failed -> throw EntTransactionFailedException(transactionState, exception)
}

/**
 * Receiver scope of the `withTransaction { tx -> ... }` block,
 * providing the [orRollback] projections. Constructed only by the
 * runtime transaction boundary ([runEntTransaction]) — never by
 * application or generated code — which is what lets the constructor
 * stay internal even though generated clients compile in the
 * application module.
 */
class TransactionScope internal constructor(
    private val coordinator: TransactionCoordinator,
) {
    /**
     * Return a successful read value, or use the stored exception to
     * stop the block so the current transaction boundary rolls back.
     *
     * Read failures do not mark the scope rollback-only merely by
     * being constructed — a caller may legitimately transform root
     * LOAD denial with [visibleOrNull] — so a *required* read uses
     * this projection. (A database read failure that puts the
     * underlying transaction into an aborted state still fails the
     * boundary regardless.)
     */
    fun <T> ReadResult<T>.orRollback(): T = when (this) {
        is ReadResult.Success<T> -> value
        is ReadResult.Failed -> {
            coordinator.recordFailure(exception)
            throw AbortEntTransaction(exception)
        }
    }

    /**
     * Return a successful mutation value, or use the stored exception
     * to stop the block so the current transaction boundary rolls
     * back. This is the ordinary composition style: it stops dependent
     * code immediately. It is not required for transaction safety —
     * a failed transaction-client mutation has already marked the
     * scope rollback-only even if its result is ignored.
     */
    fun <T> MutationResult<T>.orRollback(): T = when (this) {
        is MutationResult.Success<T> -> value
        is MutationResult.Failed -> {
            coordinator.recordFailure(exception)
            throw AbortEntTransaction(exception)
        }
    }
}

/**
 * Internal control-flow signal thrown by [TransactionScope.orRollback]
 * to stop the transaction block. The boundary in [runEntTransaction]
 * recognizes it in the driver's failed outcome and reports [cause] as
 * the transaction failure; it never escapes `withTransaction()`.
 */
internal class AbortEntTransaction(
    override val cause: Exception,
) : RuntimeException("transaction aborted: ${cause.message}", cause)

/**
 * Per-transaction state shared between the boundary loop and every
 * mutation terminal executed through the transaction-scoped client.
 *
 * Producing a `MutationResult.Failed` through a transaction client
 * marks the scope rollback-only and records the first mutation failure
 * in encounter order; later failures are retained and attached to the
 * first as suppressed exceptions. If the block returns normally while
 * rollback-only, the boundary rolls back instead of committing — the
 * fail-closed correctness backstop that makes ignoring a failed
 * mutation unable to commit earlier writes.
 */
class TransactionCoordinator @EntktInternal constructor() {
    private val failures = mutableListOf<Exception>()

    /** True once any failure has been recorded. */
    val rollbackOnly: Boolean
        get() = synchronized(failures) { failures.isNotEmpty() }

    /**
     * Record a failure in encounter order and mark the scope
     * rollback-only. Recording the same exception instance twice
     * (e.g. once at Failed construction and again via [TransactionScope.orRollback])
     * keeps a single entry.
     */
    @EntktInternal
    fun recordFailure(exception: Exception) {
        synchronized(failures) {
            if (failures.none { it === exception }) {
                failures.add(exception)
            }
        }
    }

    /**
     * Replace an already-recorded failure with a broader one in place,
     * preserving encounter order — used when a bulk terminal re-reports
     * a row-level failure as a batch-level one: the transaction must
     * retain the batch failure, not the row failure it wraps. Records
     * [replacement] normally when [original] was never recorded.
     */
    @EntktInternal
    fun replaceFailure(original: Exception, replacement: Exception) {
        synchronized(failures) {
            val index = failures.indexOfFirst { it === original }
            if (index >= 0) {
                failures[index] = replacement
            } else if (failures.none { it === replacement }) {
                failures.add(replacement)
            }
        }
    }

    /** The first recorded failure, or null when nothing was recorded. */
    internal fun firstFailure(): Exception? = synchronized(failures) { failures.firstOrNull() }

    /**
     * Every recorded failure in encounter order. A snapshot — the
     * boundary attaches these to the primary transaction failure as
     * flat suppressed exceptions; nothing here mutates the recorded
     * exceptions themselves.
     */
    internal fun recordedFailures(): List<Exception> = synchronized(failures) { failures.toList() }
}

/**
 * The client transaction boundary. Generated
 * `EntClient.withTransaction` delegates here so the boundary loop,
 * rollback-only bookkeeping, and failure-precedence rules live in one
 * tested runtime path rather than in per-schema generated code.
 *
 * [driver] must be the client's root (non-transaction-scoped) driver;
 * a transaction-scoped driver throws
 * [NestedTransactionUnsupportedException] before any transaction I/O.
 * [makeTxClient] builds the transaction-scoped client over the
 * transaction driver and wires the [TransactionCoordinator] into it so
 * mutation terminals can record failures.
 *
 * Outcome rules (RFC failure precedence):
 * - block value returned and scope not rollback-only → driver commit;
 *   `Success(value)` only after confirmed commit.
 * - block stopped via [TransactionScope.orRollback], or an ordinary
 *   exception thrown by the block (or commit) → that exit cause is
 *   primary; distinct recorded mutation failures are attached to it
 *   as flat suppressed exceptions in encounter order. Rollback
 *   confirmed → `NotCommitted`, else `OutcomeUnknown`.
 * - block returned normally while rollback-only → rollback; the
 *   *first* recorded failure is primary with later distinct recorded
 *   failures suppressed onto it.
 * - `CancellationException` and JVM `Error`s roll back and rethrow;
 *   they are never stored in a result.
 */
@EntktInternal
fun <C, T> runEntTransaction(
    driver: Driver,
    makeTxClient: (Driver, TransactionCoordinator) -> C,
    block: TransactionScope.(C) -> T,
): TransactionResult<T> {
    // The result boundary begins with this call, so even the
    // transaction-posture read is inside it: an ordinary exception
    // from a third-party driver's [Driver.inTransaction] becomes
    // Failed(NotCommitted) — nothing began — rather than escaping the
    // algebra. The nested-transaction rejection itself stays a thrown
    // exception by contract, so it is raised outside the capture.
    val nested = try {
        driver.inTransaction
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        return TransactionResult.Failed(e, TransactionFailureState.NotCommitted)
    }
    if (nested) {
        throw NestedTransactionUnsupportedException()
    }
    @OptIn(EntktInternal::class)
    val coordinator = TransactionCoordinator()
    val scope = TransactionScope(coordinator)

    val driverResult: DriverTransactionResult<T> = try {
        driver.withTransaction { txDriver ->
            val txClient = makeTxClient(txDriver, coordinator)
            val value = scope.block(txClient)
            if (coordinator.rollbackOnly) {
                // Fail-closed backstop: a recorded mutation failure whose
                // result was ignored must not commit earlier writes. The
                // throw makes the driver roll back; the boundary below
                // reports the first recorded failure as primary (RFC
                // precedence rule for a normal return while rollback-only).
                throw AbortEntTransaction(coordinator.firstFailure()!!)
            }
            value
        }
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        // The driver SPI returns ordinary mid-transaction failures
        // structurally and only throws cancellation, JVM errors, and
        // pre-begin failures (connection acquisition, setup). A thrown
        // ordinary exception therefore means no transaction began.
        return TransactionResult.Failed(e, TransactionFailureState.NotCommitted)
    }

    return when (driverResult) {
        is DriverTransactionResult.Success -> TransactionResult.Success(driverResult.value)
        is DriverTransactionResult.Failed -> {
            val cause = driverResult.exception
            // The exit cause is primary: for an orRollback() exit or
            // the rollback-only backstop that is the abort's stored
            // cause (the backstop stores the first recorded failure);
            // for a block- or commit-thrown exception it is the
            // exception itself.
            val primary: Exception = if (cause is AbortEntTransaction) cause.cause else cause
            if (cause is AbortEntTransaction) {
                // The driver attached rollback/cleanup failures to the
                // abort wrapper it stored; the wrapper is discarded, so
                // transfer them onto the reported primary.
                for (cleanup in cause.suppressed) {
                    if (cleanup !== primary && primary.suppressed.none { it === cleanup }) {
                        primary.addSuppressed(cleanup)
                    }
                }
            }
            // Distinct recorded mutation failures attach to the primary
            // as FLAT suppressed exceptions in encounter order; the
            // recorded exceptions themselves are never mutated.
            for (recorded in coordinator.recordedFailures()) {
                if (recorded !== primary && primary.suppressed.none { it === recorded }) {
                    primary.addSuppressed(recorded)
                }
            }
            TransactionResult.Failed(primary, driverResult.transactionState)
        }
    }
}
