package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Note
import entkt.integrationtest.support.PostgresTestBase
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
 *
 * Writes go through the canonical mutation terminals
 * (`saveAndLoad(): MutationResult<Note>` / `save(): MutationResult<Unit>`)
 * and reads through `findById(): ReadResult<Note?>`, projected with
 * `getOrThrow()`.
 */
class FieldBackedFkDeclarationNameIntegrationTest : PostgresTestBase() {

    private fun freshClient(): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver)
    }

    @Test
    fun `generated entity exposes FK as 'writer', not 'authorId'`() {
        val client = freshClient()
        val user = client.users.create { name = "Alice"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()

        // Compile-time: the FK property on Note is `writer`. If the
        // declaration-name capture capture regressed and the property reverted to
        // `authorId`, this assignment would not type-check.
        val note: Note = client.notes.create {
            body = "first"
            writer = user.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals(user.id, note.writer)
        assertEquals("first", note.body)
    }

    @Test
    fun `storage column remains 'author_id' even though the Kotlin API says 'writer'`() {
        val client = freshClient()
        val user = client.users.create { name = "Bob"; email = "b@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val note = client.notes.create {
            body = "raw-check"
            writer = user.id
        }.saveAndLoad(testViewerContext).getOrThrow()

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
        val alice = client.users.create { name = "Alice"; email = "alice@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val bob = client.users.create { name = "Bob"; email = "bob@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val note = client.notes.create {
            body = "x"
            writer = alice.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        client.notes.update(note.id) {
            writer = bob.id
        }.save(testViewerContext).getOrThrow()

        // Read back via the Kotlin API.
        val reread = client.notes.findById(testViewerContext, note.id).getOrThrow()
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
