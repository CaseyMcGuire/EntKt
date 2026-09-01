package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.NestedTransactionUnsupportedException
import entkt.runtime.result.ReadResult
import entkt.runtime.result.RootOperationInsideTransactionException
import entkt.runtime.result.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Generated transaction clients have no `withTransaction` member. A
 * captured root client or root driver is also guarded at runtime, so it
 * cannot escape the outer transaction through a separate connection.
 */
class NestedTransactionIntegrationTest : PostgresTestBase() {
    private fun emails(client: EntClient): List<String> =
        client.users.query { }.all(testViewerContext).getOrThrow().map { it.email }.sorted()

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
            assertFailsWith<NestedTransactionUnsupportedException> {
                driver.withTransaction {
                    nestedRan = true
                }
            }
            assertFailsWith<RootOperationInsideTransactionException> {
                driver.insert("users", mapOf("name" to "escaped", "email" to "escaped@x"))
            }
            // Outer transaction still usable.
            txDriver.insert("users", mapOf("name" to "after", "email" to "after@x"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertFalse(nestedRan, "the nested driver block must never run")
        assertEquals(listOf("after@x", "outer@x"), emails(freshChecker()))
    }

    @Test
    fun `captured root client reads and transactions fail before application callbacks`() {
        val driver = resetAndDriver()
        val client = EntClient(driver)
        var nestedRan = false

        val result = client.withTransaction {
            val read = assertIs<ReadResult.Failed>(client.users.query().all(testViewerContext))
            assertIs<RootOperationInsideTransactionException>(read.exception)

            assertFailsWith<NestedTransactionUnsupportedException> {
                client.withTransaction {
                    nestedRan = true
                }
            }
            "ok"
        }

        assertEquals(TransactionResult.Success("ok"), result)
        assertFalse(nestedRan)
        // The finally path cleared the guard; ordinary root work is usable again.
        assertEquals(emptyList(), client.users.query().all(testViewerContext).getOrThrow())
    }

    @Test
    fun `all root clients sharing a driver reject mutations before hooks`() {
        val driver = resetAndDriver()
        val transactionOwner = EntClient(driver)
        var beforeCreateCalls = 0
        val capturedRoot = EntClient(driver) {
            hooks { users { beforeCreate { state -> beforeCreateCalls++; state } } }
        }

        val result = transactionOwner.withTransaction {
            val failure = assertIs<MutationResult.Failed>(
                capturedRoot.users.create {
                    name = "escaped"
                    email = "escaped@x"
                }.save(testViewerContext),
            )
            val exception = assertIs<EntUnexpectedMutationException>(failure.exception)
            assertEquals(MutationWriteState.NotPersisted, exception.writeState)
            assertIs<RootOperationInsideTransactionException>(exception.cause)
            assertEquals(0, beforeCreateCalls)
            "ok"
        }

        assertEquals(TransactionResult.Success("ok"), result)
        assertEquals(emptyList(), emails(freshChecker()))
    }

    /**
     * A verification client on a fresh connection, without resetting
     * the tables — reads what the transactions actually persisted.
     */
    private fun freshChecker(): EntClient = EntClient(newDriver())
}
