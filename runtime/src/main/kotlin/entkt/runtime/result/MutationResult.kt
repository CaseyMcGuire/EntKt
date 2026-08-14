package entkt.runtime.result

import entkt.query.EntktInternal

/**
 * The canonical exhaustive result of a generated mutation terminal.
 *
 * `Success(value)` means the requested terminal completed: `Unit` for
 * `save()` and the delete-entity handle, the materialized entity for
 * `saveAndLoad()`, and the documented acknowledgement payloads for the
 * id-keyed and bulk operations. As with ordinary transactional APIs,
 * success inside a caller-owned transaction means the mutation is
 * staged in that transaction; it does not claim the outer transaction
 * has committed.
 *
 * [Failed] means the terminal did not produce its requested success
 * value. It does *not* imply the database write rolled back: the
 * stored [EntMutationException] explains why the terminal failed, and
 * its [EntMutationException.writeState] records what EntKt knows about
 * the database effect so callers can make safe retry decisions from
 * the raw result.
 */
sealed interface MutationResult<out T> {
    /** The requested mutation terminal completed. */
    data class Success<T>(
        val value: T,
    ) : MutationResult<T>

    /**
     * The terminal did not produce its requested success value.
     * [exception] carries both the failure reason and, via
     * [EntMutationException.writeState], EntKt's assessment of the
     * database effect.
     *
     * Only EntKt constructs failures — the constructor and `copy()`
     * are internal. Generated code uses [failedForInternalUse].
     */
    @ConsistentCopyVisibility
    data class Failed internal constructor(
        val exception: EntMutationException,
    ) : MutationResult<Nothing>

    companion object {
        /**
         * Constructs [Failed] for generated code, which compiles in
         * the application module and cannot reach the internal
         * constructor. Guarded by the error-level [EntktInternal]
         * opt-in; also the initial test-fixture path for application
         * tests exercising exhaustive `Failed` branches.
         */
        @EntktInternal
        fun failedForInternalUse(
            exception: EntMutationException,
        ): MutationResult<Nothing> = Failed(exception)
    }
}

/**
 * Return the successful value or throw the stored
 * [EntMutationException] directly — the same contract and name as
 * [ReadResult.getOrThrow]. Each mutation exception carries its write
 * state, so this projection neither introduces a wrapper nor discards
 * whether the mutation committed or has an uncertain persistence
 * outcome — the raw result and the throwing projection expose the
 * same exception instance. Performs no I/O.
 *
 * **This is a propagation convenience, not a retry policy.** A thrown
 * mutation exception does not imply the write rolled back, so blanket
 * `retry { ...save().getOrThrow() }` is unsafe. Callers deciding
 * whether to retry must inspect both the exception type and
 * [EntMutationException.writeState]: [MutationWriteState.Committed]
 * must not be repeated as though it failed to persist;
 * [MutationWriteState.PersistenceUnknown] requires reconciliation or
 * an idempotent retry mechanism; [MutationWriteState.TransactionPending]
 * is resolved by the enclosing transaction; and
 * [MutationWriteState.NotPersisted] establishes only that no write
 * survived, not that privacy, validation, or another deterministic
 * rejection will succeed on retry.
 *
 * There is deliberately no mutation `orNull()`: collapsing every
 * failure to null would hide materially different states, including a
 * definitely unpersisted write, a committed write whose callback
 * failed, and an unknown commit outcome.
 */
fun <T> MutationResult<T>.getOrThrow(): T = when (this) {
    is MutationResult.Success<T> -> value
    is MutationResult.Failed -> throw exception
}
