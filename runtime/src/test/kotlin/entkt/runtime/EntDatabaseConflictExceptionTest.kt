package entkt.runtime

import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntConflictFailure
import entkt.runtime.result.EntDatabaseConflictException
import entkt.runtime.result.EntException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationWriteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntDatabaseConflictExceptionTest {
    @Test
    fun `database conflicts retain the code message and original cause`() {
        val cause = IllegalStateException("database rejected the transaction")
        val conflict = EntDatabaseConflictException("40001", "serialization failure", cause)

        assertEquals("40001", conflict.code)
        assertEquals("serialization failure", conflict.message)
        assertSame(cause, conflict.cause)
        assertIs<EntConflictFailure>(conflict)
        assertTrue(conflict.stackTrace.isNotEmpty())
    }

    @Test
    fun `database conflicts do not acquire a mutation write state`() {
        val conflict: EntException = EntDatabaseConflictException("40P01", "deadlock", Exception())

        assertFalse(conflict is EntMutationException)
    }

    @Test
    fun `drivers can classify conflicts without an error code`() {
        val conflict = EntDatabaseConflictException(null, "concurrency conflict", Exception())

        assertNull(conflict.code)
        assertIs<EntConflictFailure>(conflict)
    }

    @Test
    fun `unexpected mutation failures do not inherit conflict classification from their causes`() {
        val causes = listOf(
            EntDatabaseConflictException("40001", "serialization failure", Exception()),
            EntConflictException("User", EntOperation.UPDATE, null, "optimistic conflict"),
        )
        for (cause in causes) {
            val unexpected: EntException = EntUnexpectedMutationException(
                MutationWriteState.TransactionPending,
                cause,
            )

            assertFalse(unexpected is EntConflictFailure)
            assertSame(cause, unexpected.cause)
        }
    }
}
