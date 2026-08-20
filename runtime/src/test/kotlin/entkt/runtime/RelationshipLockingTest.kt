package entkt.runtime
import entkt.runtime.driver.NoopDriver
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.RelationshipLockKey
import entkt.runtime.query.QueryExplanation
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.Driver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the symmetric link-table writes driver primitives' runtime types and the
 * default (unsupported) Driver behavior. Postgres-specific behavior of
 * `insertIgnore` / `serializeRelationship` lives in the integration tests.
 */
class RelationshipLockingTest {

    // ---------- RelationshipLockKey.canonical ----------

    @Test
    fun `canonical sorts fkColumns into deterministic order`() {
        val key = RelationshipLockKey.canonical("post_tag", listOf("tag_id", "post_id"))
        assertEquals(listOf("post_id", "tag_id"), key.fkColumns)
        assertEquals("post_tag", key.junctionTable)
    }

    @Test
    fun `both orientations of the same pair produce an equal key`() {
        // One side declares (post_id, tag_id); the swapped side declares
        // (tag_id, post_id). The canonical sort must collapse them to one key
        // so both orientations lock against each other.
        val fromPost = RelationshipLockKey.canonical("post_tag", listOf("post_id", "tag_id"))
        val fromTag = RelationshipLockKey.canonical("post_tag", listOf("tag_id", "post_id"))
        assertEquals(fromPost, fromTag)
        assertEquals(fromPost.hashCode(), fromTag.hashCode())
    }

    @Test
    fun `different junction tables produce distinct keys`() {
        val a = RelationshipLockKey.canonical("post_tag", listOf("post_id", "tag_id"))
        val b = RelationshipLockKey.canonical("user_group", listOf("post_id", "tag_id"))
        assertNotEquals(a, b)
    }

    @Test
    fun `direct construction with unsorted columns is rejected`() {
        // The constructor enforces canonical order: an unsorted FK pair
        // would silently hash to a different advisory-lock key and lose
        // cross-orientation serialization (fail-open), so the type
        // rejects it instead of representing it. canonical() is the
        // sorting builder for callers holding an unordered pair.
        assertFailsWith<IllegalArgumentException> {
            RelationshipLockKey("post_tag", listOf("tag_id", "post_id"))
        }
        val canonical = RelationshipLockKey.canonical("post_tag", listOf("tag_id", "post_id"))
        assertEquals(listOf("post_id", "tag_id"), canonical.fkColumns)
    }

    @Test
    fun `copy with unsorted columns is rejected too`() {
        // copy() calls the primary constructor, so the init guard closes
        // that bypass as well — a companion-only factory would not.
        val canonical = RelationshipLockKey.canonical("post_tag", listOf("post_id", "tag_id"))
        assertFailsWith<IllegalArgumentException> {
            canonical.copy(fkColumns = listOf("tag_id", "post_id"))
        }
    }

    // ---------- RelationshipLocking enum ----------

    @Test
    fun `RelationshipLocking has OwnerOnly and Canonical`() {
        assertEquals(listOf("OwnerOnly", "Canonical"), RelationshipLocking.entries.map { it.name })
    }

    // ---------- Driver defaults (unsupported) ----------

    @Test
    fun `default driver reports the new capabilities off`() {
        assertTrue(!NoopDriver.supportsInsertIgnore)
        assertTrue(!NoopDriver.supportsRelationshipSerialization)
    }

    @Test
    fun `NoopDriver insertIgnore throws`() {
        assertFailsWith<IllegalStateException> {
            NoopDriver.insertIgnore("post_tag", mapOf("post_id" to 1, "tag_id" to 2), listOf("post_id", "tag_id"))
        }
    }

    @Test
    fun `NoopDriver serializeRelationship throws`() {
        assertFailsWith<IllegalStateException> {
            NoopDriver.serializeRelationship(
                RelationshipLockKey.canonical("post_tag", listOf("post_id", "tag_id")),
            )
        }
    }

    @Test
    fun `interface default insertIgnore and serializeRelationship throw UnsupportedOperationException`() {
        // NoopDriver overrides these to IllegalStateException; assert the bare
        // *interface* default is the UnsupportedOperationException callers
        // preflight against via the capability flags.
        val defaultsOnly = DefaultsOnlyDriver()
        assertFailsWith<UnsupportedOperationException> {
            defaultsOnly.insertIgnore("t", emptyMap(), emptyList())
        }
        assertFailsWith<UnsupportedOperationException> {
            defaultsOnly.serializeRelationship(RelationshipLockKey.canonical("t", listOf("a", "b")))
        }
    }

    /**
     * A Driver that overrides only the abstract members, leaving every
     * defaulted method (including the two new symmetric link-table writes primitives) at its
     * interface default — so we can assert the *interface* default throws
     * `UnsupportedOperationException`, not NoopDriver's `IllegalStateException`.
     */
    private class DefaultsOnlyDriver : Driver {
        override fun register(schema: EntitySchema) {}
        override fun registerAll(schemas: List<EntitySchema>) {}
        override fun requireBindCapacity(minimumParameters: Long, table: String) {}
        override fun registeredIdColumn(table: String): String = "id"
        override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> = emptyMap()
        override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? = null
        override fun byId(table: String, id: Any): Map<String, Any?>? = null
        override fun query(
            table: String,
            predicates: List<entkt.query.Predicate<*>>,
            orderBy: List<entkt.query.OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> = emptyList()
        override fun count(table: String, predicates: List<entkt.query.Predicate<*>>): Long = 0
        override fun exists(table: String, predicates: List<entkt.query.Predicate<*>>): Boolean = false
        override fun delete(table: String, id: Any): Boolean = false
        override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> = emptyList()
        override fun updateMany(
            table: String,
            values: Map<String, Any?>,
            predicates: List<entkt.query.Predicate<*>>,
        ): Int = 0
        override fun explainQuery(
            table: String,
            predicates: List<entkt.query.Predicate<*>>,
            orderBy: List<entkt.query.OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): QueryExplanation = throw UnsupportedOperationException()
        override fun explainCount(table: String, predicates: List<entkt.query.Predicate<*>>): QueryExplanation =
            throw UnsupportedOperationException()
        override fun deleteMany(table: String, predicates: List<entkt.query.Predicate<*>>): Int = 0
        override fun <T> withTransaction(block: (Driver) -> T): entkt.runtime.driver.DriverTransactionResult<T> =
            entkt.runtime.driver.DriverTransactionResult.Success(block(this))
    }
}
