package entkt.runtime.mutation.execution

import entkt.runtime.result.EntDatabaseConflictException
import entkt.runtime.result.EntMutationDatabaseConflictException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationWriteState

/**
 * Preserve an already-classified database conflict, but always use this boundary's write state.
 * Do not classify arbitrary callback exceptions by inspecting their codes or cause chains.
 */
internal fun mutationFailure(
    writeState: MutationWriteState,
    cause: Exception,
): EntMutationException = when (cause) {
    is EntDatabaseConflictException -> EntMutationDatabaseConflictException(writeState, cause)
    is EntMutationDatabaseConflictException ->
        EntMutationDatabaseConflictException(writeState, cause.cause).also { failure ->
            cause.suppressed.forEach(failure::addSuppressed)
        }
    else -> EntUnexpectedMutationException(writeState, cause)
}
