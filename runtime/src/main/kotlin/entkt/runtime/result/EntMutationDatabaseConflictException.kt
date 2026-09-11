package entkt.runtime.result

/**
 * A classified database conflict propagated through a mutation boundary, such as
 * a locking read in a hook or an owned transaction's commit. [cause] retains the
 * classified conflict and its original database exception; [code] exposes its
 * driver-specific error code directly.
 *
 * Unlike [EntConflictException], this is not necessarily a rejected mutation
 * statement. [writeState] comes from the enclosing mutation's execution boundary
 * and may be committed or uncertain. The conflict category alone does not make
 * the mutation safe to retry.
 */
class EntMutationDatabaseConflictException internal constructor(
    writeState: MutationWriteState,
    override val cause: EntDatabaseConflictException,
) : EntMutationException(
    writeState,
    "Database conflict during mutation with write state $writeState",
    cause,
), EntConflictFailure {
    val code: String?
        get() = cause.code
}
