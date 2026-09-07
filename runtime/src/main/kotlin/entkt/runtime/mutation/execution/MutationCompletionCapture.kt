@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial

/**
 * Boundary-owned tracking for an owned transaction. Retains a return failure even when commit
 * fails and the transaction cannot return its block value. Successful entity values are not retained.
 */
@EntktInternal
class MutationCompletionCapture {
    internal var writeState: MutationWriteState = MutationWriteState.NotPersisted
        private set

    internal var denial: PrivacyDenial? = null
        private set

    internal var failure: Exception? = null
        private set

    internal fun record(completion: MutationCompletion<*>, writeState: MutationWriteState) {
        this.writeState = writeState
        denial = (completion as? MutationCompletion.ReturnDenied)?.denial
        failure = (completion as? MutationCompletion.ReturnFailed)?.cause
    }
}
