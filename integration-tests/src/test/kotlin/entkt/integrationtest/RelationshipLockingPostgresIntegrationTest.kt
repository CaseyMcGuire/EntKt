package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.postgres.PostgresDriver
import entkt.runtime.driver.Driver
import entkt.runtime.mutation.RelationshipLockKey
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Postgres-backed coverage for symmetric link-table writes canonical relationship locking
 * (`relationshipLocking = Canonical`) on the symmetric `Post.tags` /
 * `Tag.posts` link table.
 *
 * Pins the two properties that only show up against a real database:
 *  - both orientations take the *same* canonical relationship lock key
 *    (junction + sorted FK pair), not their per-owner key — proven with a
 *    recording driver;
 *  - concurrent opposite-orientation writes converge without deadlocking,
 *    because the canonical lock serializes the junction read-diff-write.
 */
@Testcontainers
class RelationshipLockingPostgresIntegrationTest {

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

    /**
     * A [Driver] decorator that records every [serializeRelationship] key,
     * delegating all other calls to [inner]. Wraps the transaction-scoped
     * driver too so locks taken inside `withTransaction` are observed.
     */
    private class RecordingDriver(
        private val inner: Driver,
        val keys: MutableList<RelationshipLockKey>,
    ) : Driver by inner {
        override fun serializeRelationship(key: RelationshipLockKey) {
            keys.add(key)
            inner.serializeRelationship(key)
        }

        override fun <T> withTransaction(block: (Driver) -> T): T =
            inner.withTransaction { tx -> block(RecordingDriver(tx, keys)) }
    }

    /**
     * A [Driver] decorator that forces the two relationship-locking capability flags to a
     * fixed value while delegating everything else (including the real lock
     * primitives) to [inner]. Lets the capability preflights be exercised
     * against a real Postgres database.
     */
    private class CapabilityOverrideDriver(
        private val inner: Driver,
        private val insertIgnore: Boolean? = null,
        private val relationshipSerialization: Boolean? = null,
    ) : Driver by inner {
        override val supportsInsertIgnore: Boolean
            get() = insertIgnore ?: inner.supportsInsertIgnore
        override val supportsRelationshipSerialization: Boolean
            get() = relationshipSerialization ?: inner.supportsRelationshipSerialization

        override fun <T> withTransaction(block: (Driver) -> T): T =
            inner.withTransaction { tx -> block(CapabilityOverrideDriver(tx, insertIgnore, relationshipSerialization)) }
    }

    private fun setupDb() {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.registerAll(EntClient.SCHEMAS)
        val tables = EntClient.SCHEMAS.joinToString(", ") { "\"${it.table}\"" }
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE $tables RESTART IDENTITY CASCADE")
            }
        }
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

    @Test
    fun `Canonical save takes the canonical relationship lock — identical key from both orientations`() {
        setupDb()
        val keys = Collections.synchronizedList(mutableListOf<RelationshipLockKey>())
        val client = sysClient(RecordingDriver(PostgresDriver(dataSource), keys)) {
            defaultRelationshipLocking = RelationshipLocking.Canonical
        }
        val post = client.posts.create { title = "p" }.save()
        val post2 = client.posts.create { title = "p2" }.save()
        val tag = client.tags.create { name = "a" }.save()

        // Post-side Canonical add, then Tag-side Canonical add.
        client.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tag.id) }.save() }
        client.withTransaction { tx -> tx.tags.update(tag.id) { posts.add(post2.id) }.save() }

        val expected = RelationshipLockKey.canonical("post_tags", listOf("post_id", "tag_id"))
        assertEquals(2, keys.size, "both Canonical saves take the relationship lock once")
        assertTrue(
            keys.all { it == expected },
            "both orientations must lock the same canonical relationship key, not a per-owner key; got $keys",
        )
    }

    @Test
    fun `OwnerOnly save does not take the relationship lock`() {
        setupDb()
        val keys = Collections.synchronizedList(mutableListOf<RelationshipLockKey>())
        // No config override → defaultRelationshipLocking = OwnerOnly.
        val client = sysClient(RecordingDriver(PostgresDriver(dataSource), keys))
        val post = client.posts.create { title = "p" }.save()
        val tag = client.tags.create { name = "a" }.save()

        client.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tag.id) }.save() }

        assertTrue(keys.isEmpty(), "OwnerOnly must not call serializeRelationship; got $keys")
    }

    @Test
    fun `concurrent opposite-side Canonical writes converge without deadlock`() {
        setupDb()
        val client = sysClient(PostgresDriver(dataSource)) {
            defaultRelationshipLocking = RelationshipLocking.Canonical
        }
        val post = client.posts.create { title = "p" }.save()
        val tag = client.tags.create { name = "a" }.save()

        val rounds = 20
        val errorA = AtomicReference<Throwable?>(null)
        val errorB = AtomicReference<Throwable?>(null)

        // Both threads repeatedly toggle the SAME (post, tag) pair from
        // opposite orientations — maximal contention on the post_tags
        // relationship. Without the canonical lock these interleave at the
        // junction-row level and deadlock (SQLSTATE 40P01); with it they
        // serialize and every transaction commits.
        val a = thread(start = false, name = "post-side") {
            try {
                repeat(rounds) {
                    client.withTransaction { tx -> tx.posts.update(post.id) { tags.set(listOf(tag.id)) }.save() }
                    client.withTransaction { tx -> tx.posts.update(post.id) { tags.set(emptyList()) }.save() }
                }
            } catch (t: Throwable) {
                errorA.set(t)
            }
        }
        val b = thread(start = false, name = "tag-side") {
            try {
                repeat(rounds) {
                    client.withTransaction { tx -> tx.tags.update(tag.id) { posts.set(listOf(post.id)) }.save() }
                    client.withTransaction { tx -> tx.tags.update(tag.id) { posts.set(emptyList()) }.save() }
                }
            } catch (t: Throwable) {
                errorB.set(t)
            }
        }
        a.start(); b.start()
        a.join(60_000); b.join(60_000)

        // A hang (e.g. a lock wait that never resolves) would leave a thread
        // alive after the join timeout with its error ref still null — assert
        // both actually finished so a livelock/hang fails the test rather than
        // silently passing.
        assertTrue(!a.isAlive() && !b.isAlive(), "both threads must finish within the join timeout (no hang)")
        assertNull(errorA.get(), "Post-side thread failed: ${errorA.get()}")
        assertNull(errorB.get(), "Tag-side thread failed: ${errorB.get()}")
        // There is only one possible junction row for this pair, so any
        // committed end state holds 0 or 1 rows — never a duplicate.
        assertTrue(junctionRowCount() in 0L..1L, "junction must hold at most the single (post, tag) row")
    }

    @Test
    fun `add or set is rejected on a driver lacking insertIgnore, but remove-only commits`() {
        setupDb()
        val base = sysClient(PostgresDriver(dataSource))
        val post = base.posts.create { title = "p" }.save()
        val tagA = base.tags.create { name = "a" }.save()
        val tagB = base.tags.create { name = "b" }.save()
        // Seed a link to remove later.
        base.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tagA.id) }.save() }
        assertEquals(1L, junctionRowCount())

        val capped = sysClient(CapabilityOverrideDriver(PostgresDriver(dataSource), insertIgnore = false))

        // add / set stage an insert → require supportsInsertIgnore → rejected
        // before any write.
        assertFailsWith<UnsupportedDriverCapabilityException> {
            capped.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tagB.id) }.save() }
        }
        assertFailsWith<UnsupportedDriverCapabilityException> {
            capped.withTransaction { tx -> tx.posts.update(post.id) { tags.set(listOf(tagB.id)) }.save() }
        }
        assertEquals(1L, junctionRowCount(), "rejected saves must not have written")

        // remove-only stages no insert → exempt from the preflight → commits.
        capped.withTransaction { tx -> tx.posts.update(post.id) { tags.remove(tagA.id) }.save() }
        assertEquals(0L, junctionRowCount(), "remove-only save commits on a driver without insertIgnore")
    }

    @Test
    fun `Canonical save is rejected on a driver lacking relationship serialization while OwnerOnly commits`() {
        setupDb()
        val base = sysClient(PostgresDriver(dataSource))
        val post = base.posts.create { title = "p" }.save()
        val tag = base.tags.create { name = "a" }.save()

        // Canonical needs supportsRelationshipSerialization → rejected.
        val canonicalClient = sysClient(
            CapabilityOverrideDriver(PostgresDriver(dataSource), relationshipSerialization = false),
        ) { defaultRelationshipLocking = RelationshipLocking.Canonical }
        assertFailsWith<UnsupportedDriverCapabilityException> {
            canonicalClient.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tag.id) }.save() }
        }
        assertEquals(0L, junctionRowCount(), "rejected Canonical save must not have written")

        // The same capability-lacking driver under the default OwnerOnly needs
        // no relationship lock, so the write proceeds.
        val ownerOnlyClient = sysClient(
            CapabilityOverrideDriver(PostgresDriver(dataSource), relationshipSerialization = false),
        )
        ownerOnlyClient.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tag.id) }.save() }
        assertEquals(1L, junctionRowCount())
    }
}
