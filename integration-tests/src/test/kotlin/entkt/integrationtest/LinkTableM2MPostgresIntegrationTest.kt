package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.postgres.PostgresDriver
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntTargetAbsentException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.getOrThrow
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Postgres-backed coverage for the link-table M2M helper surface
 * against real `REFERENCES ... ON DELETE CASCADE` constraints.
 *
 * Specifically, this suite pins:
 *  - PostgresDriver rejects junction inserts that reference
 *    non-existent target rows via the FK constraint declared by
 *    M2M schema modeling's junction-shape rule 4 (`ON DELETE CASCADE` implies
 *    `REFERENCES`). Under the canonical result algebra the raw
 *    PSQLException no longer propagates: the driver classifies the
 *    23503 into `EntConstraintViolationException`, the save returns
 *    `MutationResult.Failed`, and the `withTransaction` boundary
 *    reports `TransactionResult.Failed(..., NotCommitted)` after a
 *    confirmed rollback.
 *  - A missing *owner* row short-circuits at the owner-row read as
 *    `Failed(EntTargetAbsentException)` before any junction write —
 *    the FK path is never the failure mode for that case.
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
        driver.registerAll(EntClient.SCHEMAS)
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
        // PostgreSQL raises a 23503 (foreign_key_violation).
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.saveAndLoad().getOrThrow()
        val nonexistentTagId = 999_999L

        // The failed junction INSERT surfaces structurally: the save
        // returns Failed(EntConstraintViolationException) and marks the
        // scope rollback-only, so the boundary rolls back and reports
        // the recorded failure — a doomed commit can't complete silently.
        val result = client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(nonexistentTagId)
            }.save().orRollback()
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        val constraint = assertIs<EntConstraintViolationException>(failed.exception)
        // PostgreSQL FK-violation SQLSTATE is "23503", preserved as
        // driverCode with the raw PSQLException as the cause. This is
        // the canonical identifier — message text wording varies across
        // PG versions but the SQLSTATE is stable.
        assertEquals("23503", constraint.driverCode)
        val cause = assertIs<PSQLException>(constraint.cause)
        assertEquals("23503", cause.sqlState)
        // The error references the junction table's FK constraint
        // (autoDdl synthesizes the constraint name from the column).
        assertTrue(
            cause.message!!.contains("post_tags") && cause.message!!.contains("tag_id"),
            "FK violation message should reference the junction table and tag_id column; got: ${cause.message}",
        )

        // The transaction rolled back on the failure, so no junction
        // row was persisted.
        assertEquals(emptyList(), linkedTagIds(post.id))
    }

    @Test
    fun `M2M failure after an owner write reports TransactionPending and rolls the owner write back`() {
        val client = freshClient()
        val post = client.posts.create { title = "Original" }.saveAndLoad().getOrThrow()
        val nonexistentTagId = 999_999L

        var inner: MutationResult<Unit>? = null
        val result = client.withTransaction { tx ->
            inner = tx.posts.update(post.id) {
                title = "Changed"
                tags.add(nonexistentTagId)
            }.save()
            // Ignoring the returned failure cannot commit the earlier
            // owner-row update: the mutation coordinator is rollback-only.
        }

        val innerFailed = assertIs<MutationResult.Failed>(inner)
        val pending = assertIs<EntUnexpectedMutationException>(innerFailed.exception)
        assertEquals(MutationWriteState.TransactionPending, pending.writeState)
        val constraint = assertIs<EntConstraintViolationException>(pending.cause)
        assertEquals("23503", constraint.driverCode)

        val transactionFailed = assertIs<TransactionResult.Failed>(result)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertSame(pending, transactionFailed.exception)
        assertEquals("Original", client.posts.findById(post.id).getOrThrow()?.title)
        assertEquals(emptyList(), linkedTagIds(post.id))
    }

    @Test
    fun `re-adding an existing M2M link remains NotPersisted when an afterUpdate hook fails`() {
        val seedClient = freshClient()
        val post = seedClient.posts.create { title = "Hello" }.saveAndLoad().getOrThrow()
        val tag = seedClient.tags.create { name = "a" }.saveAndLoad().getOrThrow()
        seedClient.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tag.id) }.save().orRollback()
        }.getOrThrow()

        val hookFailure = IllegalStateException("afterUpdate failed")
        val client = EntClient(PostgresDriver(dataSource)) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            hooks { posts { afterUpdate { throw hookFailure } } }
        }

        var inner: MutationResult<Unit>? = null
        val result = client.withTransaction { tx ->
            inner = tx.posts.update(post.id) { tags.add(tag.id) }.save()
        }

        val innerFailed = assertIs<MutationResult.Failed>(inner)
        val noWrite = assertIs<EntUnexpectedMutationException>(innerFailed.exception)
        assertEquals(MutationWriteState.NotPersisted, noWrite.writeState)
        assertSame(hookFailure, noWrite.cause)

        val transactionFailed = assertIs<TransactionResult.Failed>(result)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertSame(noWrite, transactionFailed.exception)
        assertEquals(listOf(tag.id), linkedTagIds(post.id))
    }

    @Test
    fun `Postgres surfaces M2M add on a nonexistent owner as target-absent, not an FK violation`() {
        // Symmetric coverage: the post_id FK is also a REFERENCES
        // constraint. Updating a non-existent owner id surfaces as
        // Failed(EntTargetAbsentException) from the owner-row read,
        // which short-circuits before any junction write — pin that
        // this happens first, so the FK path is not the failure mode
        // for the missing-owner case.
        val client = freshClient()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()

        val result = client.withTransaction { tx ->
            tx.posts.update(id = 9_999_999L) {
                tags.add(tag.id)
            }.save().orRollback()
        }

        // The owner-row read returned no row → EntTargetAbsentException.
        // The junction insert never ran, so no FK violation surfaced.
        val failed = assertIs<TransactionResult.Failed>(result)
        val absent = assertIs<EntTargetAbsentException>(failed.exception)
        assertEquals("Post", absent.entityType)
        assertEquals(9_999_999L, absent.key.value)
    }

    @Test
    fun `Postgres accepts well-formed M2M add and persists the junction row`() {
        // Happy-path smoke test: confirms the codegen output compiles
        // and runs against real Postgres semantics (FK satisfied, ON
        // CONFLICT not triggered, etc.).
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.saveAndLoad().getOrThrow()
        val tagA = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()
        val tagB = client.tags.create { name = "b" }.saveAndLoad().getOrThrow()

        val saved = client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
                tags.add(tagB.id)
            }.saveAndLoad().orRollback()
        }.getOrThrow()

        assertEquals(post.id, saved.id)
        assertEquals(listOf(tagA.id, tagB.id).sorted(), linkedTagIds(post.id))
    }

    @Test
    fun `Postgres set replaces the link set — deleteMany removes the dropped ids`() {
        // Exercises the generated `driver.deleteMany(junction, [sourceFk=ownerId, targetFk IN removed])`
        // call against real Postgres. Earlier codegen quirks
        // around list/array binding for IN clauses would surface here.
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.saveAndLoad().getOrThrow()
        val tagA = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()
        val tagB = client.tags.create { name = "b" }.saveAndLoad().getOrThrow()
        val tagC = client.tags.create { name = "c" }.saveAndLoad().getOrThrow()

        // Seed: [a, b]
        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
                tags.add(tagB.id)
            }.save().orRollback()
        }.getOrThrow()
        assertEquals(listOf(tagA.id, tagB.id).sorted(), linkedTagIds(post.id))

        // Replace with [a, c]: adds c, removes b, keeps a.
        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.set(listOf(tagA.id, tagC.id))
            }.save().orRollback()
        }.getOrThrow()
        assertEquals(listOf(tagA.id, tagC.id).sorted(), linkedTagIds(post.id))
    }
}
