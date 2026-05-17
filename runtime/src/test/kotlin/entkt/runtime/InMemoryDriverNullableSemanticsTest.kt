package entkt.runtime

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins InMemoryDriver's predicate / orderBy treatment of NULL values
 * to match Postgres's three-valued semantics. Pre-fix the in-memory
 * driver used Kotlin `==` / Comparable semantics where null was a
 * regular value, which diverged from Postgres on NEQ / NOT_IN /
 * LT-family / orderBy null placement — tests would pass in-memory
 * and break on Postgres.
 *
 * Three-valued rules (matching Postgres):
 *  - any comparison with NULL on either side (other than IS_NULL /
 *    IS_NOT_NULL) → false
 *  - `value IN (..., NULL, ...)` for non-null value → still false
 *    because NULL propagates
 *  - orderBy puts nulls last in both directions
 */
class InMemoryDriverNullableSemanticsTest {

    private fun freshDriver(): InMemoryDriver {
        val driver = InMemoryDriver()
        driver.register(
            EntitySchema(
                table = "items",
                idColumn = "id",
                idStrategy = IdStrategy.AUTO_LONG,
                columns = listOf(
                    ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                    ColumnMetadata("label", FieldType.STRING, nullable = true),
                    ColumnMetadata("score", FieldType.INT, nullable = true),
                ),
                edges = emptyMap(),
            )
        )
        return driver
    }

    private fun seed(driver: InMemoryDriver) {
        driver.insert("items", mapOf("label" to "a", "score" to 10))
        driver.insert("items", mapOf("label" to "b", "score" to 20))
        driver.insert("items", mapOf("label" to null, "score" to null))
    }

    private fun query(driver: InMemoryDriver, vararg predicates: Predicate.Leaf) =
        driver.query("items", predicates.toList(), emptyList(), null, null)

    // ---- NEQ ----

    @Test
    fun `NEQ does not match null rows (matches Postgres NULL is-not-x = NULL)`() {
        val driver = freshDriver()
        seed(driver)
        val rows = query(driver, Predicate.Leaf("label", Op.NEQ, "a"))
        // Pre-fix: 2 rows (b + null). Post-fix: 1 row (b only).
        // Postgres `label <> 'a'` returns NULL for the null-label row → excluded.
        assertEquals(1, rows.size)
        assertEquals("b", rows[0]["label"])
    }

    // ---- LT / LTE / GT / GTE ----

    @Test
    fun `LT does not match null rows`() {
        val driver = freshDriver()
        seed(driver)
        val rows = query(driver, Predicate.Leaf("score", Op.LT, 15))
        // Pre-fix: 2 rows (10 and the null row, since compare(null, 15) = -1).
        // Post-fix: 1 row (10 only).
        assertEquals(1, rows.size)
        assertEquals(10, rows[0]["score"])
    }

    @Test
    fun `GT does not match null rows`() {
        val driver = freshDriver()
        seed(driver)
        val rows = query(driver, Predicate.Leaf("score", Op.GT, 15))
        assertEquals(1, rows.size)
        assertEquals(20, rows[0]["score"])
    }

    // ---- NOT_IN ----

    @Test
    fun `NOT_IN does not match null rows`() {
        val driver = freshDriver()
        seed(driver)
        val rows = query(driver, Predicate.Leaf("label", Op.NOT_IN, listOf("a")))
        // Pre-fix: 2 rows (b + null row, since the list doesn't contain null).
        // Post-fix: 1 row (b only) — null on the value side.
        assertEquals(1, rows.size)
        assertEquals("b", rows[0]["label"])
    }

    @Test
    fun `NOT_IN with NULL in the list excludes everything (NULL propagates)`() {
        val driver = freshDriver()
        seed(driver)
        // Postgres `x NOT IN ('a', NULL)` is NULL for every x → excludes all rows.
        val rows = query(driver, Predicate.Leaf("label", Op.NOT_IN, listOf("a", null)))
        assertTrue(rows.isEmpty(), "NOT_IN with NULL in the list should exclude all rows; got $rows")
    }

    // ---- IN ----

    @Test
    fun `IN does not match null rows even when the list contains other values`() {
        val driver = freshDriver()
        seed(driver)
        val rows = query(driver, Predicate.Leaf("label", Op.IN, listOf("a", "b")))
        assertEquals(2, rows.size, "should match a and b but not null row")
    }

    // ---- EQ ----

    @Test
    fun `EQ does not match when one side is null (use IS_NULL for that)`() {
        val driver = freshDriver()
        seed(driver)
        val rows = query(driver, Predicate.Leaf("label", Op.EQ, null))
        assertTrue(rows.isEmpty(), "EQ NULL should not match; use IS_NULL")
    }

    @Test
    fun `IS_NULL still matches null rows (the explicit null check)`() {
        val driver = freshDriver()
        seed(driver)
        val rows = query(driver, Predicate.Leaf("label", Op.IS_NULL, null))
        assertEquals(1, rows.size)
        assertEquals(null, rows[0]["label"])
    }

    // ---- orderBy null placement ----

    @Test
    fun `orderBy ASC puts nulls last (Postgres default)`() {
        val driver = freshDriver()
        seed(driver)
        val rows = driver.query(
            "items",
            emptyList(),
            listOf(OrderField("score", OrderDirection.ASC)),
            null, null,
        )
        // Pre-fix: null first → [null, 10, 20]. Post-fix: nulls last → [10, 20, null].
        assertEquals(listOf(10, 20, null), rows.map { it["score"] })
    }

    @Test
    fun `orderBy DESC also puts nulls last`() {
        val driver = freshDriver()
        seed(driver)
        val rows = driver.query(
            "items",
            emptyList(),
            listOf(OrderField("score", OrderDirection.DESC)),
            null, null,
        )
        assertEquals(listOf(20, 10, null), rows.map { it["score"] })
    }
}
