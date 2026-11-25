package entkt.runtime.query.execution

import entkt.query.EntktInternal
import entkt.runtime.query.EntInterceptorsConfig

/**
 * Contextless services a generated query needs when it executes a read.
 *
 * Generated read runtimes implement this contract once and pass themselves to each query builder.
 * The operation's viewer context remains an explicit terminal argument and is never stored here.
 */
@EntktInternal
interface ReadQueryExecutionHost<Viewer : Any> : LoadPrivacyDispatcher<Viewer> {
    /** Reject reads through an escaped or otherwise stale transaction-scoped client. */
    fun checkReadExecution()

    /** Stable interceptor registry shared by queries hosted by this runtime. */
    val entityInterceptors: EntInterceptorsConfig<Viewer>
}
