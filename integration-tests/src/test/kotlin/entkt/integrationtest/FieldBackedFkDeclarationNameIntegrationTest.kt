package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Note
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.PrivacyContext
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * End-to-end Postgres coverage for declaration-name capture's field-backed FK
 * declaration-name capture. [Note] declares the backing field as
 * `val writer = long("author_id")` so the generated Kotlin API
 * exposes the FK as `writer` while the storage column stays
 * `author_id`. This suite pins the bridge in both directions:
 *
 *  - Kotlin call sites read / write the FK via `writer` only —
 *    no `authorId` shadow property exists (compile-time check
 *    via the test's own use of the property).
 *  - The underlying database row carries the value under the
 *    `author_id` column (verified via raw JDBC).
 */
class FieldBackedFkDeclarationNameIntegrationTest : PostgresTestBase() {

    private fun freshClient(): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
    }

    @Test
    fun `generated entity exposes FK as 'writer', not 'authorId'`() {
        val client = freshClient()
        val user = client.users.create { name = "Alice"; email = "a@example.com" }.save()

        // Compile-time: the FK property on Note is `writer`. If the
        // declaration-name capture capture regressed and the property reverted to
        // `authorId`, this assignment would not type-check.
        val note: Note = client.notes.create {
            body = "first"
            writer = user.id
        }.save()

        assertEquals(user.id, note.writer)
        assertEquals("first", note.body)
    }

    @Test
    fun `storage column remains 'author_id' even though the Kotlin API says 'writer'`() {
        val client = freshClient()
        val user = client.users.create { name = "Bob"; email = "b@example.com" }.save()
        val note = client.notes.create {
            body = "raw-check"
            writer = user.id
        }.save()

        // Read the row back via the column name. If declaration-name capture
        // accidentally renamed the storage column too, this query
        // would fail with "column writer does not exist".
        val storedAuthorId: Long = dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT author_id FROM notes WHERE id = ?",
            ).use { stmt ->
                stmt.setLong(1, note.id)
                stmt.executeQuery().use { rs ->
                    assertEquals(true, rs.next(), "expected one row for the inserted note")
                    rs.getLong("author_id")
                }
            }
        }
        assertEquals(user.id, storedAuthorId)
    }

    @Test
    fun `update through 'writer' setter writes to the author_id column`() {
        val client = freshClient()
        val alice = client.users.create { name = "Alice"; email = "alice@example.com" }.save()
        val bob = client.users.create { name = "Bob"; email = "bob@example.com" }.save()
        val note = client.notes.create {
            body = "x"
            writer = alice.id
        }.save()

        client.notes.update(note.id) {
            writer = bob.id
        }.save()

        // Read back via the Kotlin API.
        val reread = client.notes.byIdOrNull(note.id)
        assertNotNull(reread)
        assertEquals(bob.id, reread.writer, "update through `writer` setter should change the FK")

        // And via raw SQL on the storage column.
        val storedAuthorId: Long = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT author_id FROM notes WHERE id = ?").use { stmt ->
                stmt.setLong(1, note.id)
                stmt.executeQuery().use { rs ->
                    rs.next(); rs.getLong("author_id")
                }
            }
        }
        assertEquals(bob.id, storedAuthorId)
    }
}
