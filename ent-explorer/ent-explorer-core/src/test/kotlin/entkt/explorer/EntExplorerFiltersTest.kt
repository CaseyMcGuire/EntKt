package entkt.explorer

import entkt.query.Op
import entkt.query.Predicate
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The op-per-type and value-parsing rules that every generated adapter delegates to. */
class EntExplorerFiltersTest {

    private fun column(
        type: FieldType,
        nullable: Boolean = false,
        filterable: Boolean = true,
    ) = EntExplorerColumn("c", type, nullable, unique = false, sensitive = false, filterable = filterable, orderable = true)

    private fun leaf(type: FieldType, op: EntExplorerFilterOp, value: String?): Predicate.Leaf<Any> =
        EntExplorerFilters.predicate<Any>(column(type), EntExplorerFilter("c", op, value)) as Predicate.Leaf<Any>

    @Test
    fun `values parse to the column's Kotlin type`() {
        assertEquals(Predicate.Leaf<Any>("c", Op.EQ, 42), leaf(FieldType.INT, EntExplorerFilterOp.EQ, "42"))
        assertEquals(Predicate.Leaf<Any>("c", Op.GT, 42L), leaf(FieldType.LONG, EntExplorerFilterOp.GT, "42"))
        assertEquals(Predicate.Leaf<Any>("c", Op.EQ, true), leaf(FieldType.BOOL, EntExplorerFilterOp.EQ, "true"))
        assertEquals(Predicate.Leaf<Any>("c", Op.EQ, "x"), leaf(FieldType.STRING, EntExplorerFilterOp.EQ, "x"))
        assertEquals(
            Predicate.Leaf<Any>("c", Op.EQ, java.time.Instant.parse("2026-01-01T00:00:00Z")),
            leaf(FieldType.INSTANT, EntExplorerFilterOp.EQ, "2026-01-01T00:00:00Z"),
        )
        assertEquals(Op.CONTAINS, leaf(FieldType.STRING, EntExplorerFilterOp.CONTAINS, "x").op)
        assertEquals(Op.HAS_PREFIX, leaf(FieldType.STRING, EntExplorerFilterOp.PREFIX, "x").op)
    }

    @Test
    fun `dates support comparisons and reject invalid dates or timestamps`() {
        val date = java.time.LocalDate.of(2024, 2, 29)
        for ((filterOp, op) in listOf(
            EntExplorerFilterOp.EQ to Op.EQ, EntExplorerFilterOp.NEQ to Op.NEQ,
            EntExplorerFilterOp.GT to Op.GT, EntExplorerFilterOp.GTE to Op.GTE,
            EntExplorerFilterOp.LT to Op.LT, EntExplorerFilterOp.LTE to Op.LTE,
        )) {
            assertEquals(Predicate.Leaf<Any>("c", op, date), leaf(FieldType.DATE, filterOp, "2024-02-29"))
        }

        for (invalid in listOf("2023-02-29", "2024-02-30", "2024-02-29T00:00:00Z", "today", "02/29/2024")) {
            val error = assertFailsWith<EntExplorerBadRequestException> {
                leaf(FieldType.DATE, EntExplorerFilterOp.EQ, invalid)
            }
            assertTrue("ISO-8601 date" in error.message.orEmpty(), error.message)
        }
        assertFailsWith<EntExplorerBadRequestException> {
            leaf(FieldType.DATE, EntExplorerFilterOp.CONTAINS, "2024")
        }
        assertEquals(
            Predicate.Leaf<Any>("c", Op.IS_NULL, null),
            EntExplorerFilters.predicate<Any>(
                column(FieldType.DATE, nullable = true),
                EntExplorerFilter("c", EntExplorerFilterOp.IS_NULL, null),
            ),
        )
    }

    @Test
    fun `unparseable values fail before any query with the column named`() {
        for ((type, bad) in listOf(
            FieldType.INT to "nope",
            FieldType.BOOL to "TRUE",
            FieldType.INSTANT to "yesterday",
            FieldType.UUID to "123",
        )) {
            val ex = assertFailsWith<EntExplorerBadRequestException>("$type") {
                leaf(type, EntExplorerFilterOp.EQ, bad)
            }
            assertTrue("'c'" in ex.message!!, "$type: ${ex.message}")
        }
    }

    @Test
    fun `ops are gated per type`() {
        assertFailsWith<EntExplorerBadRequestException> { leaf(FieldType.INT, EntExplorerFilterOp.CONTAINS, "1") }
        assertFailsWith<EntExplorerBadRequestException> { leaf(FieldType.BOOL, EntExplorerFilterOp.GT, "true") }
        assertFailsWith<EntExplorerBadRequestException> { leaf(FieldType.UUID, EntExplorerFilterOp.LT, java.util.UUID.randomUUID().toString()) }
        assertTrue(EntExplorerFilters.supportedOps(FieldType.JSON).isEmpty(), "display-only types support no ops")
        assertTrue(EntExplorerFilters.supportedOps(FieldType.PGVECTOR).isEmpty())
        assertTrue(EntExplorerFilters.supportedOps(FieldType.BYTES).isEmpty())
    }

    @Test
    fun `null ops require nullability and carry no value`() {
        val p = EntExplorerFilters.predicate<Any>(
            column(FieldType.INT, nullable = true),
            EntExplorerFilter("c", EntExplorerFilterOp.IS_NULL, null),
        )
        assertEquals(Predicate.Leaf<Any>("c", Op.IS_NULL, null), p)
        assertFailsWith<EntExplorerBadRequestException> {
            EntExplorerFilters.predicate<Any>(column(FieldType.INT), EntExplorerFilter("c", EntExplorerFilterOp.IS_NULL, null))
        }
    }

    @Test
    fun `enum values validate against constant names and bind as the name`() {
        val col = column(FieldType.ENUM)
        val ok = EntExplorerFilters.predicate<Any>(col, EntExplorerFilter("c", EntExplorerFilterOp.EQ, "PRO"), setOf("FREE", "PRO"))
        assertEquals(Predicate.Leaf<Any>("c", Op.EQ, "PRO"), ok)
        val ex = assertFailsWith<EntExplorerBadRequestException> {
            EntExplorerFilters.predicate<Any>(col, EntExplorerFilter("c", EntExplorerFilterOp.EQ, "GOLD"), setOf("FREE", "PRO"))
        }
        assertTrue("FREE" in ex.message!! && "PRO" in ex.message!!)
    }

    @Test
    fun `non-filterable columns are rejected regardless of op`() {
        assertFailsWith<EntExplorerBadRequestException> {
            EntExplorerFilters.predicate<Any>(
                column(FieldType.STRING, filterable = false),
                EntExplorerFilter("c", EntExplorerFilterOp.EQ, "x"),
            )
        }
    }
}
