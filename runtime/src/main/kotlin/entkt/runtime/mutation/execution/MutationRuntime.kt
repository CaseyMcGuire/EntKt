@file:OptIn(EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.query.execution.LoadPrivacyDispatcher
import entkt.runtime.result.EntMutationException

/** Client capabilities shared by generated mutation executors. */
@EntktInternal
interface MutationRuntime : LoadPrivacyDispatcher {
    /** Enforce the configured transaction requirement before lifecycle work begins. */
    fun checkTransactionRequirement(
        operation: String,
        multiWrite: Boolean = false,
    )

    /** Record a returned mutation failure on the current transaction, when one exists. */
    fun recordTransactionMutationFailure(exception: EntMutationException)

}
