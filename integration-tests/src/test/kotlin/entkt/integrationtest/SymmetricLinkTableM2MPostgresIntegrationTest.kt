package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.postgres.PostgresDriver
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Postgres-backed coverage for symmetric link-table writes. The
 * `Tag.posts` orientation is the pair-swapped reverse of `Post.tags` over
 * the same `post_tags` junction. Both orientations are writable, junction
 * inserts go through `insertIgnore` (idempotent), and reads from either
 * side resolve the same relationship.
 *
 * Each test brings up a single `postgres:16-alpine` container, registers
 * all schemas via the generated `EntClient.SCHEMAS`, and truncates between
 * tests for isolation. Link-table M2M writes require a transaction-scoped
 * client, so every write runs inside `withTransaction { tx -> ... }`,
 * extracting each `MutationResult` with `orRollback()` and projecting the
 * boundary's `TransactionResult` with `getOrThrow()` so any failure fails
 * the test loudly.
 */
@Testcontainers
class SymmetricLinkTableM2MPostgresIntegrationTest {

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

    private fun freshClient(): EntClient {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.registerAll(EntClient.SCHEMAS)

        val tables = EntClient.SCHEMAS.joinToString(", ") { "\"${it.table}\"" }
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE $tables RESTART IDENTITY CASCADE")
            }
        }
        return sysClient(driver)
    }

    private fun junctionRowCount(): Long =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM \"post_tags\"").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun linkedPostIds(tagId: Long): List<Long> =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT \"post_id\" FROM \"post_tags\" WHERE \"tag_id\" = ?").use { stmt ->
                stmt.setLong(1, tagId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Long>()
                    while (rs.next()) out.add(rs.getLong(1))
                    out.sorted()
                }
            }
        }

    @Test
    fun `Tag-side add writes the same junction row as the Post side`() {
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.saveAndLoad().getOrThrow()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()

        client.withTransaction { tx ->
            tx.tags.update(tag.id) {
                posts.add(post.id)
            }.save().orRollback()
        }.getOrThrow()

        assertEquals(listOf(post.id), linkedPostIds(tag.id))
        assertEquals(1L, junctionRowCount())
    }

    @Test
    fun `reads resolve the same relationship from both orientations`() {
        val client = freshClient()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()
        val postA = client.posts.create { title = "A" }.saveAndLoad().getOrThrow()
        val postB = client.posts.create { title = "B" }.saveAndLoad().getOrThrow()
        client.posts.create { title = "C (unlinked)" }.save().getOrThrow()

        // Link A and B to the tag from the Post side.
        client.withTransaction { tx ->
            tx.posts.update(postA.id) { tags.add(tag.id) }.save().orRollback()
            tx.posts.update(postB.id) { tags.add(tag.id) }.save().orRollback()
        }.getOrThrow()

        // Read from the Tag side: the single tag reaches exactly {A, B}.
        val postsForTag = client.tags.query().queryPosts().all().getOrThrow()
        assertEquals(listOf(postA.id, postB.id).sorted(), postsForTag.map { it.id }.sorted())

        // Read from the Post side: the linked posts reach the tag (the
        // unlinked post C contributes nothing).
        val tagsReached = client.posts.query().queryTags().all().getOrThrow()
        assertEquals(setOf(tag.id), tagsReached.map { it.id }.toSet())
    }

    @Test
    fun `cross-anchor re-add is an idempotent no-op`() {
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.saveAndLoad().getOrThrow()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()

        // Add from the Tag side.
        client.withTransaction { tx ->
            tx.tags.update(tag.id) { posts.add(post.id) }.save().orRollback()
        }.getOrThrow()
        assertEquals(1L, junctionRowCount())

        // Re-add the SAME pair from the opposite (Post) anchor. insertIgnore
        // makes this a no-op rather than a unique-constraint error, and no
        // duplicate junction row appears.
        client.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tag.id) }.save().orRollback()
        }.getOrThrow()
        assertEquals(1L, junctionRowCount())
        assertEquals(listOf(post.id), linkedPostIds(tag.id))
    }

    @Test
    fun `remove from the Tag side clears the junction row written from the Post side`() {
        val client = freshClient()
        val post = client.posts.create { title = "Hello" }.saveAndLoad().getOrThrow()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()

        client.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tag.id) }.save().orRollback()
        }.getOrThrow()
        assertEquals(1L, junctionRowCount())

        // The remove targets the same junction relationship from the
        // opposite orientation.
        client.withTransaction { tx ->
            tx.tags.update(tag.id) { posts.remove(post.id) }.save().orRollback()
        }.getOrThrow()
        assertEquals(0L, junctionRowCount())
    }

    @Test
    fun `set from the Tag side replaces the post set for that tag`() {
        val client = freshClient()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()
        val postA = client.posts.create { title = "A" }.saveAndLoad().getOrThrow()
        val postB = client.posts.create { title = "B" }.saveAndLoad().getOrThrow()
        val postC = client.posts.create { title = "C" }.saveAndLoad().getOrThrow()

        // Seed [A, B] from the Tag side.
        client.withTransaction { tx ->
            tx.tags.update(tag.id) { posts.set(listOf(postA.id, postB.id)) }.save().orRollback()
        }.getOrThrow()
        assertEquals(listOf(postA.id, postB.id).sorted(), linkedPostIds(tag.id))

        // Replace with [A, C]: adds C, removes B, keeps A — idempotently.
        client.withTransaction { tx ->
            tx.tags.update(tag.id) { posts.set(listOf(postA.id, postC.id)) }.save().orRollback()
        }.getOrThrow()
        assertEquals(listOf(postA.id, postC.id).sorted(), linkedPostIds(tag.id))
    }
}
