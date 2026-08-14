package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.NestedTransactionUnsupportedException
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.getOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Nested transactions are unsupported by design: calling
 * `withTransaction` on a transaction-scoped client or driver throws
 * [NestedTransactionUnsupportedException] BEFORE the nested block or
 * any nested transaction I/O runs. Savepoint semantics were removed —
 * a future transaction-client design owns any nested story. The outer
 * transaction is unchanged and fully usable when the caller catches
 * the rejection.
 */
class NestedTransactionIntegrationTest : PostgresTestBase() {

    private fun freshClient(): EntClient = EntClient(resetAndDriver()) {
        privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
    }

    private fun emails(client: EntClient): List<String> =
        client.users.query { }.all().getOrThrow().map { it.email }.sorted()

    // ---- client level ----

    @Test
    fun `nested client withTransaction throws before the nested block runs`() {
        val client = freshClient()
        var nestedRan = false

        val result = client.withTransaction { tx ->
            tx.users.create { name = "outer"; email = "outer@x" }.saveAndLoad().orRollback()
            assertFailsWith<NestedTransactionUnsupportedException> {
                tx.withTransaction { tx2 ->
                    nestedRan = true
                    tx2.users.create { name = "inner"; email = "inner@x" }.save()
                }
            }
            // The outer transaction is unchanged and usable after catching.
            tx.users.create { name = "after"; email = "after@x" }.saveAndLoad().orRollback()
            "done"
        }

        assertIs<TransactionResult.Success<String>>(result)
        assertFalse(nestedRan, "the nested block must never run")
        assertEquals(listOf("after@x", "outer@x"), emails(freshChecker()))
    }

    @Test
    fun `an uncaught nested rejection fails the outer boundary with NotCommitted`() {
        val client = freshClient()

        val result = client.withTransaction { tx ->
            tx.users.create { name = "outer"; email = "outer@x" }.saveAndLoad().orRollback()
            // Not caught: the rejection propagates as the block's
            // exception, so the outer transaction rolls back.
            tx.withTransaction { "never" }
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertIs<NestedTransactionUnsupportedException>(failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertEquals(emptyList(), emails(freshChecker()))
    }

    // ---- driver level ----

    @Test
    fun `nested driver withTransaction throws before any nested transaction IO`() {
        val driver = resetAndDriver()
        var nestedRan = false

        val result = driver.withTransaction { txDriver ->
            txDriver.insert("users", mapOf("name" to "outer", "email" to "outer@x"))
            assertFailsWith<NestedTransactionUnsupportedException> {
                txDriver.withTransaction {
                    nestedRan = true
                }
            }
            // Outer transaction still usable.
            txDriver.insert("users", mapOf("name" to "after", "email" to "after@x"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertFalse(nestedRan, "the nested driver block must never run")
        assertEquals(listOf("after@x", "outer@x"), emails(freshChecker()))
    }

    /**
     * A verification client on a fresh connection, without resetting
     * the tables — reads what the transactions actually persisted.
     */
    private fun freshChecker(): EntClient = EntClient(newDriver()) {
        privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
    }
}
