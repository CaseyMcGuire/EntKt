package entkt.runtime.result

/**
 * A recognized database concurrency conflict without mutation-specific state.
 * [code] is the driver-specific error code, when available, and [cause] retains
 * the original database exception.
 *
 * Unlike [EntConflictException], this exception does not describe a mutation's
 * persistence. It also makes no commit or rollback claim: the enclosing
 * [TransactionResult] reports the transaction's final outcome separately.
 */
class EntDatabaseConflictException(
    val code: String?,
    message: String,
    cause: Exception,
) : EntException(message, cause), EntConflictFailure
