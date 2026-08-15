@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.result

import entkt.query.EntktInternal
import entkt.runtime.driver.Driver
import java.util.WeakHashMap

/**
 * Thrown when a root client or driver is used from inside a transaction
 * opened by that same root. Such work would otherwise run on a separate
 * connection and could commit independently of the surrounding transaction.
 */
class RootOperationInsideTransactionException : IllegalStateException(
    "An operation through a transaction's root client or driver cannot run inside its " +
        "withTransaction block; use the transaction-scoped client or driver supplied to the block",
)

/** Opaque capability carried only by the client bound to an open transaction. */
@EntktInternal
class TransactionExecutionToken internal constructor()

/**
 * Execution-local guard shared by generated clients backed by one root driver.
 *
 * The state is thread-local because EntKt's transaction API is synchronous:
 * unrelated work on other threads must remain usable while a transaction is
 * open. If a future suspending transaction API allows thread migration, it must
 * provide an equivalent coroutine-context propagation mechanism.
 */
@EntktInternal
class TransactionExecutionGuard {
    private val active = ThreadLocal<TransactionExecutionToken?>()

    fun enterTransaction(): TransactionExecutionToken {
        if (active.get() != null) throw NestedTransactionUnsupportedException()
        return TransactionExecutionToken().also(active::set)
    }

    fun exitTransaction(token: TransactionExecutionToken) {
        check(active.get() === token) {
            "EntKt transaction execution guard exited by a different transaction"
        }
        active.remove()
    }

    fun checkClientOperation(token: TransactionExecutionToken?) {
        val current = active.get() ?: return
        if (token !== current) throw RootOperationInsideTransactionException()
    }
}

private val guardsByDriver = WeakHashMap<Driver, TransactionExecutionGuard>()

/**
 * Returns the execution guard shared by generated clients over [driver].
 * Weak keys avoid extending the lifetime of application-owned drivers.
 */
@EntktInternal
fun transactionExecutionGuardForInternalUse(driver: Driver): TransactionExecutionGuard =
    synchronized(guardsByDriver) {
        guardsByDriver.getOrPut(driver) { TransactionExecutionGuard() }
    }
