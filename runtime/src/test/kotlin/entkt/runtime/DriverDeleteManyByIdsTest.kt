package entkt.runtime

import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DriverDeleteManyByIdsTest {
    @Test
    fun `default fallback deletes each distinct id with frozen predicates`() {
        val driver = DefaultDeleteDriver(counts = mapOf(1L to 1, 2L to 0))
        val scope = Predicate.Leaf<Any>("tenant_id", Op.EQ, 7L)

        val deleted = driver.deleteManyByIds(
            table = "widgets",
            idColumn = "id",
            ids = listOf(1L, 2L, 1L),
            predicates = listOf(scope),
        )

        assertEquals(listOf(1L), deleted)
        assertEquals(listOf<Any>(1L, 2L), driver.deletedIds)
        assertTrue(driver.calls.all { it.first() === scope })
    }

    @Test
    fun `default fallback rejects a non-id column before deleting`() {
        val driver = DefaultDeleteDriver()

        assertFailsWith<IllegalArgumentException> {
            driver.deleteManyByIds("widgets", "tenant_id", listOf(1L), emptyList())
        }

        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun `default fallback rejects an impossible per-id affected count`() {
        val driver = DefaultDeleteDriver(counts = mapOf(1L to 2))

        val error = assertFailsWith<IllegalStateException> {
            driver.deleteManyByIds("widgets", "id", listOf(1L), emptyList())
        }

        assertTrue(error.message.orEmpty().contains("affected 2 rows"))
    }

    @Test
    fun `empty fallback does not inspect schema metadata`() {
        val driver = DefaultDeleteDriver()

        assertEquals(emptyList(), driver.deleteManyByIds("missing", "wrong", emptyList(), emptyList()))
        assertEquals(0, driver.metadataLookups)
    }

    private class DefaultDeleteDriver(
        private val counts: Map<Any, Int> = emptyMap(),
    ) : DatabaseDriver by NoopDriver {
        val calls = mutableListOf<List<Predicate<*>>>()
        val deletedIds = mutableListOf<Any>()
        var metadataLookups: Int = 0

        override fun registeredIdColumn(table: String): String {
            metadataLookups++
            return "id"
        }

        override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int {
            calls += predicates
            val id = (predicates.last() as Predicate.Leaf<*>).value
            deletedIds += checkNotNull(id)
            return counts[id] ?: 0
        }

        override fun deleteManyByIds(
            table: String,
            idColumn: String,
            ids: List<Any>,
            predicates: List<Predicate<*>>,
        ): List<Any> = super<DatabaseDriver>.deleteManyByIds(table, idColumn, ids, predicates)
    }
}
