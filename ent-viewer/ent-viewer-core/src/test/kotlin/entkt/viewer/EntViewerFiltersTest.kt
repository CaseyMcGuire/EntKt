package entkt.viewer

import entkt.query.Op
import entkt.query.Predicate
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The op-per-type and value-parsing rules that every generated adapter delegates to. */
class EntViewerFiltersTest {

    private fun column(
        type: FieldType,
        nullable: Boolean = false,
        filterable: Boolean = true,
    ) = EntViewerColumn("c", type, nullable, unique = false, sensitive = false, filterable = filterable, orderable = true)

    private fun leaf(type: FieldType, op: EntViewerFilterOp, value: String?): Predicate.Leaf<Any> =
        EntViewerFilters.predicate<Any>(column(type), EntViewerFilter("c", op, value)) as Predicate.Leaf<Any>

    @Test
    fun `values parse to the column's Kotlin type`() {
        assertEquals(Predicate.Leaf<Any>("c", Op.EQ, 42), leaf(FieldType.INT, EntViewerFilterOp.EQ, "42"))
        assertEquals(Predicate.Leaf<Any>("c", Op.GT, 42L), leaf(FieldType.LONG, EntViewerFilterOp.GT, "42"))
        assertEquals(Predicate.Leaf<Any>("c", Op.EQ, true), leaf(FieldType.BOOL, EntViewerFilterOp.EQ, "true"))
        assertEquals(Predicate.Leaf<Any>("c", Op.EQ, "x"), leaf(FieldType.STRING, EntViewerFilterOp.EQ, "x"))
        assertEquals(
            Predicate.Leaf<Any>("c", Op.EQ, java.time.Instant.parse("2026-01-01T00:00:00Z")),
            leaf(FieldType.TIME, EntViewerFilterOp.EQ, "2026-01-01T00:00:00Z"),
        )
        assertEquals(Op.CONTAINS, leaf(FieldType.TEXT, EntViewerFilterOp.CONTAINS, "x").op)
        assertEquals(Op.HAS_PREFIX, leaf(FieldType.STRING, EntViewerFilterOp.PREFIX, "x").op)
    }

    @Test
    fun `unparseable values fail before any query with the column named`() {
        for ((type, bad) in listOf(
            FieldType.INT to "nope",
            FieldType.BOOL to "TRUE",
            FieldType.TIME to "yesterday",
            FieldType.UUID to "123",
        )) {
            val ex = assertFailsWith<EntViewerBadRequestException>("$type") {
                leaf(type, EntViewerFilterOp.EQ, bad)
            }
            assertTrue("'c'" in ex.message!!, "$type: ${ex.message}")
        }
    }

    @Test
    fun `ops are gated per type`() {
        assertFailsWith<EntViewerBadRequestException> { leaf(FieldType.INT, EntViewerFilterOp.CONTAINS, "1") }
        assertFailsWith<EntViewerBadRequestException> { leaf(FieldType.BOOL, EntViewerFilterOp.GT, "true") }
        assertFailsWith<EntViewerBadRequestException> { leaf(FieldType.UUID, EntViewerFilterOp.LT, java.util.UUID.randomUUID().toString()) }
        assertTrue(EntViewerFilters.supportedOps(FieldType.JSON).isEmpty(), "display-only types support no ops")
        assertTrue(EntViewerFilters.supportedOps(FieldType.PGVECTOR).isEmpty())
        assertTrue(EntViewerFilters.supportedOps(FieldType.BYTES).isEmpty())
    }

    @Test
    fun `null ops require nullability and carry no value`() {
        val p = EntViewerFilters.predicate<Any>(
            column(FieldType.INT, nullable = true),
            EntViewerFilter("c", EntViewerFilterOp.IS_NULL, null),
        )
        assertEquals(Predicate.Leaf<Any>("c", Op.IS_NULL, null), p)
        assertFailsWith<EntViewerBadRequestException> {
            EntViewerFilters.predicate<Any>(column(FieldType.INT), EntViewerFilter("c", EntViewerFilterOp.IS_NULL, null))
        }
    }

    @Test
    fun `enum values validate against constant names and bind as the name`() {
        val col = column(FieldType.ENUM)
        val ok = EntViewerFilters.predicate<Any>(col, EntViewerFilter("c", EntViewerFilterOp.EQ, "PRO"), setOf("FREE", "PRO"))
        assertEquals(Predicate.Leaf<Any>("c", Op.EQ, "PRO"), ok)
        val ex = assertFailsWith<EntViewerBadRequestException> {
            EntViewerFilters.predicate<Any>(col, EntViewerFilter("c", EntViewerFilterOp.EQ, "GOLD"), setOf("FREE", "PRO"))
        }
        assertTrue("FREE" in ex.message!! && "PRO" in ex.message!!)
    }

    @Test
    fun `non-filterable columns are rejected regardless of op`() {
        assertFailsWith<EntViewerBadRequestException> {
            EntViewerFilters.predicate<Any>(
                column(FieldType.STRING, filterable = false),
                EntViewerFilter("c", EntViewerFilterOp.EQ, "x"),
            )
        }
    }
}
