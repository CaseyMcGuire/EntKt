package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the all-or-nothing contract of nested transaction blocks. A
 * nested `withTransaction` / `withTransactionOrError` runs on the same
 * connection under a savepoint: a failed nested block rolls back its
 * own writes — including partial writes made before the failure — and
 * leaves the outer transaction usable, even after a failed SQL
 * statement put PostgreSQL in its aborted state. And a block that
 * swallows a failed statement's error without ending cannot "commit"
 * into a silent rollback: the driver fails loudly instead.
 */
class NestedTransactionIntegrationTest : PostgresTestBase() {

    private fun freshClient(): EntClient = EntClient(resetAndDriver()) {
        privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
    }

    private fun emails(client: EntClient): List<String> =
        client.users.query { }.allOrThrow().map { it.email }.sorted()

    @Test
    fun `a failed nested block rolls back its partial writes while the outer commits`() {
        val client = freshClient()

        client.withTransaction { tx ->
            tx.users.create { name = "outer"; email = "outer@x" }.saveOrThrow()
            val inner = tx.withTransactionOrError { tx2 ->
                tx2.users.create { name = "inner"; email = "inner@x" }.saveOrThrow()
                // A non-SQL failure after the inner write: NotFound Err
                // aborts the nested block via bind().
                tx2.users.byIdOrError(-1L).bind()
            }
            assertTrue(inner is EntResult.Err, "nested block should report Err; got $inner")
            tx.users.create { name = "after"; email = "after@x" }.saveOrThrow()
        }

        assertEquals(listOf("after@x", "outer@x"), emails(freshChecker()))
    }

    @Test
    fun `a failed nested SQL statement leaves the outer transaction usable`() {
        val client = freshClient()
        client.users.create { name = "taken"; email = "taken@x" }.saveOrThrow()

        client.withTransaction { tx ->
            val inner = tx.withTransactionOrError { tx2 ->
                tx2.users.create { name = "inner"; email = "inner@x" }.saveOrThrow()
                // Unique violation: the statement fails on the server,
                // which aborts the real transaction — the savepoint
                // rollback must recover it.
                tx2.users.create { name = "dup"; email = "taken@x" }.saveOrError().bind()
            }
            assertTrue(inner is EntResult.Err, "nested block should report Err; got $inner")
            tx.users.create { name = "after"; email = "after@x" }.saveOrThrow()
        }

        assertEquals(listOf("after@x", "taken@x"), emails(freshChecker()))
    }

    @Test
    fun `a swallowed statement failure cannot commit as a silent rollback`() {
        val client = freshClient()
        client.users.create { name = "taken"; email = "taken@x" }.saveOrThrow()

        val ex = assertFailsWith<IllegalStateException> {
            client.withTransaction { tx ->
                tx.users.create { name = "outer"; email = "outer@x" }.saveOrThrow()
                // Swallow the constraint-violation Err and keep going —
                // the transaction is now aborted and can never commit.
                val err = tx.users.create { name = "dup"; email = "taken@x" }.saveOrError()
                assertTrue(err is EntResult.Err)
            }
        }
        assertTrue(
            ex.message.orEmpty().contains("aborted"),
            "failure should explain the aborted state; got: ${ex.message}",
        )
        assertEquals(listOf("taken@x"), emails(freshChecker()))
    }

    @Test
    fun `a successful nested block commits with the outer transaction`() {
        val client = freshClient()

        client.withTransaction { tx ->
            tx.users.create { name = "outer"; email = "outer@x" }.saveOrThrow()
            val inner = tx.withTransactionOrError { tx2 ->
                tx2.users.create { name = "inner"; email = "inner@x" }.saveOrThrow().id
            }
            assertTrue(inner is EntResult.Ok)
        }

        assertEquals(listOf("inner@x", "outer@x"), emails(freshChecker()))
    }

    /**
     * A verification client on a fresh connection, without resetting
     * the tables — reads what the transactions actually persisted.
     */
    private fun freshChecker(): EntClient = EntClient(newDriver()) {
        privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
    }
}
