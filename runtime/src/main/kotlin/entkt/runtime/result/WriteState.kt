package entkt.runtime.result

/**
 * What EntKt knows about a failed mutation's database effect. Carried
 * by every [EntMutationException] so callers can make safe retry
 * decisions from the raw result.
 *
 * A *successful* mutation terminal does not report a write state:
 * success in a caller-owned transaction has the ordinary transactional
 * meaning of successful staging, while success from an EntKt-owned
 * transaction means its commit completed.
 */
enum class MutationWriteState {
    /**
     * The mutation has no durable effect. It did not execute, was
     * rejected before execution, or was rolled back successfully.
     */
    NotPersisted,

    /**
     * The mutation's SQL executed inside a caller-owned transaction
     * that remains open. The transaction owner still determines
     * whether it commits.
     */
    TransactionPending,

    /** EntKt received confirmation that the transaction committed. */
    Committed,

    /**
     * EntKt cannot determine whether the mutation committed, normally
     * because the driver connection failed during commit or outcome
     * confirmation. Never retried automatically; the affected
     * connection and transaction are treated as unusable.
     */
    PersistenceUnknown,
}

/**
 * How a failed transaction boundary left the database. Deliberately
 * narrower than [MutationWriteState]: a completed transaction cannot
 * be pending, and no operation can fail after a confirmed commit, so
 * only the not-committed and unknown outcomes are representable.
 */
enum class TransactionFailureState {
    /** Rollback was confirmed; the transaction has no durable effect. */
    NotCommitted,

    /**
     * Neither commit nor rollback could be confirmed — the failed
     * commit may already have reached the database. Never retried
     * automatically.
     */
    OutcomeUnknown,
}
