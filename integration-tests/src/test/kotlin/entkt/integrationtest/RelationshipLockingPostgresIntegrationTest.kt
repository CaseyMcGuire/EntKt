package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.postgres.PostgresDriver
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.mutation.RelationshipLockKey
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionResult
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
import kotlin.test.assertIs
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
 *
 * Under the canonical result algebra, capability preflight failures
 * (`UnsupportedDriverCapabilityException`) no longer propagate out of the
 * mutation terminal: they surface as
 * `MutationResult.Failed(EntUnexpectedMutationException(NotPersisted, cause))`,
 * and — because a failed transaction-client mutation marks the scope
 * rollback-only — the `withTransaction` boundary reports
 * `TransactionResult.Failed` carrying that same exception.
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
     * A [DatabaseDriver] decorator that records every [serializeRelationship] key,
     * delegating all other calls to [inner]. Wraps the transaction-scoped
     * driver too so locks taken inside `withTransaction` are observed.
     */
    private class RecordingDriver(
        private val inner: DatabaseDriver,
        val keys: MutableList<RelationshipLockKey>,
    ) : DatabaseDriver by inner {
        override fun serializeRelationship(key: RelationshipLockKey) {
            keys.add(key)
            inner.serializeRelationship(key)
        }

        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
            inner.withTransaction { tx -> block(RecordingDriver(tx, keys)) }
    }

    /**
     * A [DatabaseDriver] decorator that forces the two relationship-locking capability flags to a
     * fixed value while delegating everything else (including the real lock
     * primitives) to [inner]. Lets the capability preflights be exercised
     * against a real Postgres database.
     */
    private class CapabilityOverrideDriver(
        private val inner: DatabaseDriver,
        private val insertIgnore: Boolean? = null,
        private val relationshipSerialization: Boolean? = null,
    ) : DatabaseDriver by inner {
        override val supportsInsertIgnore: Boolean
            get() = insertIgnore ?: inner.supportsInsertIgnore
        override val supportsRelationshipSerialization: Boolean
            get() = relationshipSerialization ?: inner.supportsRelationshipSerialization

        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
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

    /**
     * Assert the boundary reports the canonical capability-preflight
     * failure shape: `TransactionResult.Failed` carrying
     * `EntUnexpectedMutationException(NotPersisted)` whose cause is
     * [UnsupportedDriverCapabilityException].
     */
    private fun assertCapabilityRejected(result: TransactionResult<*>) {
        val failed = assertIs<TransactionResult.Failed>(result)
        val wrapped = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, wrapped.writeState, "preflight rejects before any write")
        assertIs<UnsupportedDriverCapabilityException>(wrapped.cause)
    }

    @Test
    fun `Canonical save takes the canonical relationship lock — identical key from both orientations`() {
        setupDb()
        val keys = Collections.synchronizedList(mutableListOf<RelationshipLockKey>())
        val client = sysClient(RecordingDriver(PostgresDriver(dataSource), keys)) {
            defaultRelationshipLocking = RelationshipLocking.Canonical
        }
        val post = client.posts.create { title = "p" }.saveAndLoad().getOrThrow()
        val post2 = client.posts.create { title = "p2" }.saveAndLoad().getOrThrow()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()

        // Post-side Canonical add, then Tag-side Canonical add.
        client.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tag.id) }.save().orRollback()
        }.getOrThrow()
        client.withTransaction { tx ->
            tx.tags.update(tag.id) { posts.add(post2.id) }.save().orRollback()
        }.getOrThrow()

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
        val post = client.posts.create { title = "p" }.saveAndLoad().getOrThrow()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()

        client.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tag.id) }.save().orRollback()
        }.getOrThrow()

        assertTrue(keys.isEmpty(), "OwnerOnly must not call serializeRelationship; got $keys")
    }

    @Test
    fun `concurrent opposite-side Canonical writes converge without deadlock`() {
        setupDb()
        val client = sysClient(PostgresDriver(dataSource)) {
            defaultRelationshipLocking = RelationshipLocking.Canonical
        }
        val post = client.posts.create { title = "p" }.saveAndLoad().getOrThrow()
        val tag = client.tags.create { name = "a" }.saveAndLoad().getOrThrow()

        val rounds = 20
        val errorA = AtomicReference<Throwable?>(null)
        val errorB = AtomicReference<Throwable?>(null)

        // Both threads repeatedly toggle the SAME (post, tag) pair from
        // opposite orientations — maximal contention on the post_tags
        // relationship. Without the canonical lock these interleave at the
        // junction-row level and deadlock (SQLSTATE 40P01); with it they
        // serialize and every transaction commits. getOrThrow() on each
        // boundary makes any Failed transaction (e.g. a deadlock reported
        // structurally) throw into the thread's error ref.
        val a = thread(start = false, name = "post-side") {
            try {
                repeat(rounds) {
                    client.withTransaction { tx ->
                        tx.posts.update(post.id) { tags.set(listOf(tag.id)) }.save().orRollback()
                    }.getOrThrow()
                    client.withTransaction { tx ->
                        tx.posts.update(post.id) { tags.set(emptyList()) }.save().orRollback()
                    }.getOrThrow()
                }
            } catch (t: Throwable) {
                errorA.set(t)
            }
        }
        val b = thread(start = false, name = "tag-side") {
            try {
                repeat(rounds) {
                    client.withTransaction { tx ->
                        tx.tags.update(tag.id) { posts.set(listOf(post.id)) }.save().orRollback()
                    }.getOrThrow()
                    client.withTransaction { tx ->
                        tx.tags.update(tag.id) { posts.set(emptyList()) }.save().orRollback()
                    }.getOrThrow()
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
        val post = base.posts.create { title = "p" }.saveAndLoad().getOrThrow()
        val tagA = base.tags.create { name = "a" }.saveAndLoad().getOrThrow()
        val tagB = base.tags.create { name = "b" }.saveAndLoad().getOrThrow()
        // Seed a link to remove later.
        base.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tagA.id) }.save().orRollback()
        }.getOrThrow()
        assertEquals(1L, junctionRowCount())

        val capped = sysClient(CapabilityOverrideDriver(PostgresDriver(dataSource), insertIgnore = false))

        // add / set stage an insert → require supportsInsertIgnore → rejected
        // before any write. The preflight failure no longer propagates as a
        // thrown exception: it is Failed(EntUnexpectedMutationException(
        // NotPersisted, cause = UnsupportedDriverCapabilityException)), and
        // the boundary reports the recorded failure.
        assertCapabilityRejected(
            capped.withTransaction { tx ->
                tx.posts.update(post.id) { tags.add(tagB.id) }.save().orRollback()
            },
        )
        assertCapabilityRejected(
            capped.withTransaction { tx ->
                tx.posts.update(post.id) { tags.set(listOf(tagB.id)) }.save().orRollback()
            },
        )
        assertEquals(1L, junctionRowCount(), "rejected saves must not have written")

        // remove-only stages no insert → exempt from the preflight → commits.
        capped.withTransaction { tx ->
            tx.posts.update(post.id) { tags.remove(tagA.id) }.save().orRollback()
        }.getOrThrow()
        assertEquals(0L, junctionRowCount(), "remove-only save commits on a driver without insertIgnore")
    }

    @Test
    fun `Canonical save is rejected on a driver lacking relationship serialization while OwnerOnly commits`() {
        setupDb()
        val base = sysClient(PostgresDriver(dataSource))
        val post = base.posts.create { title = "p" }.saveAndLoad().getOrThrow()
        val tag = base.tags.create { name = "a" }.saveAndLoad().getOrThrow()

        // Canonical needs supportsRelationshipSerialization → rejected as
        // Failed(EntUnexpectedMutationException(NotPersisted, cause =
        // UnsupportedDriverCapabilityException)).
        val canonicalClient = sysClient(
            CapabilityOverrideDriver(PostgresDriver(dataSource), relationshipSerialization = false),
        ) { defaultRelationshipLocking = RelationshipLocking.Canonical }
        assertCapabilityRejected(
            canonicalClient.withTransaction { tx ->
                tx.posts.update(post.id) { tags.add(tag.id) }.save().orRollback()
            },
        )
        assertEquals(0L, junctionRowCount(), "rejected Canonical save must not have written")

        // The same capability-lacking driver under the default OwnerOnly needs
        // no relationship lock, so the write proceeds.
        val ownerOnlyClient = sysClient(
            CapabilityOverrideDriver(PostgresDriver(dataSource), relationshipSerialization = false),
        )
        ownerOnlyClient.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tag.id) }.save().orRollback()
        }.getOrThrow()
        assertEquals(1L, junctionRowCount())
    }
}
