@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.MutationWriteState

/** Transaction-bound resources and persistence certainty for one operation invocation. */
@EntktInternal
class MutationExecution internal constructor(
    internal val driver: DatabaseDriver,
    private val successfulWriteState: MutationWriteState,
    internal val isOwnedTransaction: Boolean,
) {
    internal val inTransaction: Boolean
        get() = successfulWriteState == MutationWriteState.TransactionPending

    internal var writeState: MutationWriteState = MutationWriteState.NotPersisted

    /** Record that a multi-write operation may have staged writes in its transaction. */
    internal fun markWritePending() {
        writeState = MutationWriteState.TransactionPending
    }

    /** Record a confirmed driver write under the enclosing transaction posture. */
    internal fun markWriteSucceeded() {
        writeState = successfulWriteState
    }

    internal fun reject(exception: EntMutationException): Nothing = throw Rejected(exception)

    /** Internal control flow keeps an intentional rejection distinct from an unexpected exception. */
    internal class Rejected(
        val exception: EntMutationException,
    ) : RuntimeException(exception)
}
