package entkt.postgres.aggregate

import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.AggregateFunction
import entkt.runtime.ColumnMetadata
import entkt.runtime.EntitySchema
import entkt.runtime.IdStrategy
import entkt.schema.FieldType
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Driver-level coverage for [PostgresDriver.aggregate] against a real Postgres.
 * A hand-built [EntitySchema] (no extension needed) exercises sum/avg/min/max/count,
 * ungrouped and grouped, the decode-by-type contract, empty-set semantics, and
 * field-named identifier validation.
 */
@Testcontainers
class AggregatePostgresDriverTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    private val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password
        }
    }

    private val schema = EntitySchema(
        table = "stats",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("amount", FieldType.INT, nullable = false),
            ColumnMetadata("bonus", FieldType.LONG, nullable = true),
            ColumnMetadata("rating", FieldType.DOUBLE, nullable = true),
            ColumnMetadata("category", FieldType.STRING, nullable = false),
            ColumnMetadata("status", FieldType.ENUM, nullable = false),
            ColumnMetadata("created_at", FieldType.TIME, nullable = false),
            ColumnMetadata("payload", FieldType.BYTES, nullable = true),
        ),
        edges = emptyMap(),
    )

    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun fresh(): PostgresDriver {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(schema)
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE \"stats\" RESTART IDENTITY") }
        }
        return driver
    }

    private fun PostgresDriver.add(
        amount: Int, category: String, status: String,
        bonus: Long? = null, rating: Double? = null, at: Instant = t0,
    ) {
        insert(
            "stats",
            mapOf(
                "amount" to amount, "category" to category, "status" to status,
                "bonus" to bonus, "rating" to rating, "created_at" to at,
            ),
        )
    }

    private fun PostgresDriver.agg(
        fn: AggregateFunction, column: String?, groupBy: String? = null,
        predicates: List<Predicate<*>> = emptyList(),
    ) = aggregate("stats", fn, column, predicates, groupBy)

    @Test
    fun `ungrouped sum over INT returns one Long row`() {
        val d = fresh()
        d.add(5, "a", "ACTIVE"); d.add(3, "b", "ACTIVE")
        val rows = d.agg(AggregateFunction.SUM, "amount")
        assertEquals(1, rows.size)
        assertNull(rows[0].key)
        assertEquals(8L, rows[0].value)
    }

    @Test
    fun `sum over nullable LONG skips nulls and is null over an empty set`() {
        val d = fresh()
        d.add(1, "a", "ACTIVE", bonus = 10L); d.add(1, "a", "ACTIVE", bonus = null)
        assertEquals(10L, d.agg(AggregateFunction.SUM, "bonus").single().value)
        val none = listOf<Predicate<*>>(Predicate.Leaf<Any>("amount", Op.EQ, 999))
        assertNull(d.agg(AggregateFunction.SUM, "bonus", predicates = none).single().value)
        assertEquals(0L, d.agg(AggregateFunction.COUNT, null, predicates = none).single().value)
    }

    @Test
    fun `avg returns a Double, fractional even for integer inputs`() {
        val d = fresh()
        d.add(1, "a", "ACTIVE"); d.add(2, "a", "ACTIVE")
        assertEquals(1.5, d.agg(AggregateFunction.AVG, "amount").single().value)
    }

    @Test
    fun `min and max return the column's own decoded type`() {
        val d = fresh()
        d.add(5, "x", "ACTIVE", at = t0)
        d.add(2, "a", "ACTIVE", at = t0.plusSeconds(60))
        assertEquals(2, d.agg(AggregateFunction.MIN, "amount").single().value)         // Int
        assertEquals("x", d.agg(AggregateFunction.MAX, "category").single().value)     // String
        assertEquals(t0.plusSeconds(60), d.agg(AggregateFunction.MAX, "created_at").single().value) // Instant
    }

    @Test
    fun `count grouped by a string key returns one bucket per value`() {
        val d = fresh()
        d.add(1, "a", "ACTIVE"); d.add(1, "a", "ACTIVE"); d.add(1, "b", "ACTIVE")
        val byCat = d.agg(AggregateFunction.COUNT, null, groupBy = "category").associate { it.key to it.value }
        assertEquals(mapOf<Any?, Any?>("a" to 2L, "b" to 1L), byCat)
    }

    @Test
    fun `grouped key keeps its Int type`() {
        val d = fresh()
        d.add(10, "a", "ACTIVE"); d.add(10, "a", "ACTIVE"); d.add(20, "b", "ACTIVE")
        val byAmount = d.agg(AggregateFunction.COUNT, null, groupBy = "amount").associate { it.key to it.value }
        assertEquals(mapOf<Any?, Any?>(10 to 2L, 20 to 1L), byAmount)
    }

    @Test
    fun `an enum group key is returned as its stored String`() {
        val d = fresh()
        d.add(1, "a", "ACTIVE"); d.add(1, "a", "PENDING"); d.add(1, "a", "ACTIVE")
        val byStatus = d.agg(AggregateFunction.COUNT, null, groupBy = "status").associate { it.key to it.value }
        // The driver returns the stored String; the generated terminal decodes the enum.
        assertEquals(mapOf<Any?, Any?>("ACTIVE" to 2L, "PENDING" to 1L), byStatus)
    }

    @Test
    fun `grouped sum sums each bucket`() {
        val d = fresh()
        d.add(5, "a", "ACTIVE"); d.add(3, "a", "ACTIVE"); d.add(7, "b", "ACTIVE")
        val byCat = d.agg(AggregateFunction.SUM, "amount", groupBy = "category").associate { it.key to it.value }
        assertEquals(mapOf<Any?, Any?>("a" to 8L, "b" to 7L), byCat)
    }

    @Test
    fun `predicates filter the aggregate`() {
        val d = fresh()
        d.add(5, "a", "ACTIVE"); d.add(7, "b", "ACTIVE")
        val onlyA = listOf<Predicate<*>>(Predicate.Leaf<Any>("category", Op.EQ, "a"))
        assertEquals(5L, d.agg(AggregateFunction.SUM, "amount", predicates = onlyA).single().value)
    }

    @Test
    fun `an unknown metric column throws a field-named error before SQL`() {
        val d = fresh()
        val ex = assertFailsWith<IllegalArgumentException> { d.agg(AggregateFunction.SUM, "bogus") }
        assertTrue("stats.bogus" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `an unknown group column throws a field-named error before SQL`() {
        val d = fresh()
        val ex = assertFailsWith<IllegalArgumentException> {
            d.agg(AggregateFunction.COUNT, null, groupBy = "bogus")
        }
        assertTrue("stats.bogus" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `COUNT with a metric column is rejected rather than silently ignored`() {
        val d = fresh()
        val ex = assertFailsWith<IllegalArgumentException> {
            d.agg(AggregateFunction.COUNT, "amount")
        }
        assertTrue("COUNT" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `sum over a non-numeric column is rejected before SQL`() {
        val d = fresh()
        val ex = assertFailsWith<IllegalArgumentException> { d.agg(AggregateFunction.SUM, "category") }
        assertTrue("numeric" in ex.message!!, ex.message ?: "")
        assertTrue("stats.category" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `min over an enum column is rejected before SQL`() {
        val d = fresh()
        val ex = assertFailsWith<IllegalArgumentException> { d.agg(AggregateFunction.MIN, "status") }
        assertTrue("comparable" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `grouping by a non-groupable column is rejected before SQL`() {
        val d = fresh()
        // payload is BYTES — excluded from group keys (like pgvector / JSON).
        val ex = assertFailsWith<IllegalArgumentException> {
            d.agg(AggregateFunction.COUNT, null, groupBy = "payload")
        }
        assertTrue("group key" in ex.message!!, ex.message ?: "")
        assertTrue("stats.payload" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `the driver reports aggregate support`() {
        assertTrue(fresh().supportsAggregates())
    }
}
