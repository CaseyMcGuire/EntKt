@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime

import entkt.runtime.result.NestedTransactionUnsupportedException
import entkt.runtime.result.RootOperationInsideTransactionException
import entkt.runtime.result.TransactionExecutionGuard
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TransactionExecutionGuardTest {
    @Test
    fun `only the transaction token is accepted while the scope is active`() {
        val guard = TransactionExecutionGuard()
        guard.checkClientOperation(null)

        val token = guard.enterTransaction()
        try {
            guard.checkClientOperation(token)
            assertFailsWith<RootOperationInsideTransactionException> {
                guard.checkClientOperation(null)
            }
            assertFailsWith<NestedTransactionUnsupportedException> {
                guard.enterTransaction()
            }
        } finally {
            guard.exitTransaction(token)
        }

        guard.checkClientOperation(null)
    }

    @Test
    fun `an active transaction does not block unrelated threads`() {
        val guard = TransactionExecutionGuard()
        val token = guard.enterTransaction()
        val failure = AtomicReference<Throwable?>()
        try {
            thread {
                runCatching { guard.checkClientOperation(null) }
                    .exceptionOrNull()
                    .let(failure::set)
            }.join()
        } finally {
            guard.exitTransaction(token)
        }

        assertNull(failure.get())
    }
}
