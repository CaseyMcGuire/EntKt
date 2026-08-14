package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.NestedTransactionUnsupportedException
import entkt.runtime.result.getOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Generated transaction clients have no `withTransaction` member, so
 * client-level nesting is rejected at compile time. At the lower-level
 * driver boundary, nested transactions remain guarded at runtime and
 * throw [NestedTransactionUnsupportedException] before the nested block
 * or any nested transaction I/O runs.
 */
class NestedTransactionIntegrationTest : PostgresTestBase() {
    private fun emails(client: EntClient): List<String> =
        client.users.query { }.all().getOrThrow().map { it.email }.sorted()

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
