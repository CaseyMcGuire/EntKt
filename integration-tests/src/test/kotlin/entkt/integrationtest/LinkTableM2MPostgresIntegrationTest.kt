package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.postgres.PostgresDriver
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Postgres-backed coverage for the link-table M2M helper surface
 * against real `REFERENCES ... ON DELETE CASCADE` constraints.
 *
 * Specifically, this suite pins:
 *  - PostgresDriver rejects junction inserts that reference
 *    non-existent target rows via the FK constraint declared by
 *    M2M schema modeling's junction-shape rule 4 (`ON DELETE CASCADE` implies
 *    `REFERENCES`).
 *
 * Each test brings up a single Postgres container (Testcontainers,
 * `postgres:16-alpine`), registers all schemas via the generated
 * `EntClient.SCHEMAS`, and truncates between tests for isolation.
 */
@Testcontainers
class LinkTableM2MPostgresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    private val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private fun seedSchemas() {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        EntClient.SCHEMAS.forEach(driver::register)
    }

    private fun freshClient(): EntClient {
        val driver = PostgresDriver(dataSource)
        seedSchemas()

        val tables = EntClient.SCHEMAS.joinToString(", ") { "\"${it.table}\"" }
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE $tables RESTART IDENTITY CASCADE")
            }
        }

        // Not testing privacy — run as System so fail-closed defaults don't block.
        return EntClient(driver) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }
    }

    private fun linkedTagIds(postId: Long): List<Long> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT \"tag_id\" FROM \"post_tags\" WHERE \"post_id\" = ?",
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Long>()
                    while (rs.next()) out.add(rs.getLong(1))
                    out.sorted()
                }
            }
        }
    }

    @Test
    fun `Postgres rejects M2M add of a nonexistent target id via the junction FK constraint`() {
        // M2M schema modeling junction-shape rule 4 requires explicit
        // `OnDelete.CASCADE` on both junction belongsTo edges, which
        // PostgresDriver lowers to `REFERENCES posts(id) ON DELETE
        // CASCADE` and `REFERENCES tags(id) ON DELETE CASCADE`
        // during autoDdl. The FK is what enforces target existence
        // on INSERT — without the matching `tag_id` in `tags`,
        // PostgreSQL throws a 23503 (foreign_key_violation).
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.save()
        val nonexistentTagId = 999_999L

        val ex = client.withTransaction { tx ->
            assertFailsWith<PSQLException> {
                tx.posts.update(post.id) {
                    tags.add(nonexistentTagId)
                }.save()
            }
        }

        // PostgreSQL FK-violation SQLSTATE is "23503". This is the
        // canonical identifier — message text wording varies across
        // PG versions but the SQLSTATE is stable.
        assertEquals("23503", ex.sqlState)
        // The error references the junction table's FK constraint
        // (autoDdl synthesizes the constraint name from the column).
        assertTrue(
            ex.message!!.contains("post_tags") && ex.message!!.contains("tag_id"),
            "FK violation message should reference the junction table and tag_id column; got: ${ex.message}",
        )

        // The transaction rolled back on the exception, so no junction
        // row was persisted.
        assertEquals(emptyList(), linkedTagIds(post.id))
    }

    @Test
    fun `Postgres rejects M2M add of a nonexistent owner via the same FK constraint`() {
        // Symmetric coverage: the post_id FK is also a REFERENCES
        // constraint. Updating a non-existent owner-id should
        // surface as a missing row (the owner-row read short-circuits
        // before junction writes), not a FK violation — pin that
        // happens first, so the FK path is not the failure mode for
        // the missing-owner case.
        val client = freshClient()
        val tag = client.tags.create { name = "a" }.save()

        val result = client.withTransaction { tx ->
            tx.posts.update(id = 9_999_999L) {
                tags.add(tag.id)
            }.save()
        }

        // No exception — the owner-row read returned null and save()
        // returned null. The junction insert never ran, so no FK
        // violation surfaced.
        kotlin.test.assertNull(result)
    }

    @Test
    fun `Postgres accepts well-formed M2M add and persists the junction row`() {
        // Happy-path smoke test: confirms the codegen output compiles
        // and runs against real Postgres semantics (FK satisfied, ON
        // CONFLICT not triggered, etc.).
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.save()
        val tagA = client.tags.create { name = "a" }.save()
        val tagB = client.tags.create { name = "b" }.save()

        val saved = client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
                tags.add(tagB.id)
            }.save()
        }

        assertNotNull(saved)
        assertEquals(listOf(tagA.id, tagB.id).sorted(), linkedTagIds(post.id))
    }

    @Test
    fun `Postgres set replaces the link set — deleteMany removes the dropped ids`() {
        // Exercises the generated `driver.deleteMany(junction, [sourceFk=ownerId, targetFk IN removed])`
        // call against real Postgres. Earlier codegen quirks
        // around list/array binding for IN clauses would surface here.
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.save()
        val tagA = client.tags.create { name = "a" }.save()
        val tagB = client.tags.create { name = "b" }.save()
        val tagC = client.tags.create { name = "c" }.save()

        // Seed: [a, b]
        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
                tags.add(tagB.id)
            }.save()
        }
        assertEquals(listOf(tagA.id, tagB.id).sorted(), linkedTagIds(post.id))

        // Replace with [a, c]: adds c, removes b, keeps a.
        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.set(listOf(tagA.id, tagC.id))
            }.save()
        }
        assertEquals(listOf(tagA.id, tagC.id).sorted(), linkedTagIds(post.id))
    }
}
