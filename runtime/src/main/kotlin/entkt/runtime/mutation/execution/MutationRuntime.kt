@file:OptIn(EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyContextProvider
import entkt.runtime.query.execution.LoadPrivacyEvaluator
import entkt.runtime.result.EntMutationException

/** Client capabilities shared by generated mutation evaluators. */
@EntktInternal
interface MutationRuntime<out PrivacyClient, out ValidationClient> :
    PrivacyContextProvider,
    LoadPrivacyEvaluator {
    /** Enforce the configured transaction requirement before lifecycle work begins. */
    fun checkTransactionRequirement(
        operation: String,
        multiWrite: Boolean = false,
    )

    /** Record a returned mutation failure on the current transaction, when one exists. */
    fun recordTransactionMutationFailure(exception: EntMutationException)

    /** Return the viewer-scoped read client exposed to CREATE-privacy rules. */
    fun privacyRuleClient(privacyContext: PrivacyContext): PrivacyClient

    /** Return the privileged read client exposed to CREATE-validation rules. */
    fun validationRuleClient(): ValidationClient
}
