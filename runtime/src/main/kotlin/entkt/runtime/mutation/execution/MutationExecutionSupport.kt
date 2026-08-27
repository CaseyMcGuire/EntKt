@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import java.util.concurrent.CancellationException

/** Shared cancellation, failure-recording, and write-state boundary for mutation executors. */
internal class MutationExecutionSupport(
    private val mutationRuntime: MutationRuntime,
) {
    fun <Result> execute(
        block: (MutationAttempt) -> Result,
    ): MutationResult<Result> {
        val attempt = MutationAttempt()
        return try {
            MutationResult.Success(block(attempt))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MutationRejected) {
            fail(e.exception)
        } catch (e: Exception) {
            fail(EntUnexpectedMutationException(attempt.writeState, e))
        }
    }

    private fun fail(exception: EntMutationException): MutationResult<Nothing> {
        mutationRuntime.recordTransactionMutationFailure(exception)
        return MutationResult.failedForInternalUse(exception)
    }
}

/** Mutable certainty state owned by one mutation execution. */
internal class MutationAttempt {
    var writeState: MutationWriteState = MutationWriteState.NotPersisted

    fun reject(exception: EntMutationException): Nothing = throw MutationRejected(exception)
}

private class MutationRejected(
    val exception: EntMutationException,
) : RuntimeException(exception)
