package entkt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Schema fixture that includes the canonical [DeletedAt] mixin. */
private class DeletableMemo : EntSchema("memos") {
    override fun id() = EntId.long()
    val softDelete = include(::DeletedAt)
    val body = text("body")
}

/** Schema fixture without the mixin, for negative checks. */
private class PlainMemo : EntSchema("plain_memos") {
    override fun id() = EntId.long()
    val body = text("body")
}

class DeletedAtTest {

    private fun finalized(schema: EntSchema): EntSchema {
        schema.finalize(mapOf(schema::class to schema))
        return schema
    }

    @Test
    fun `including DeletedAt adds a single nullable deleted_at field of type TIME`() {
        val memo = finalized(DeletableMemo())
        val fields = memo.fields()

        // 2 fields total: deleted_at (from mixin) + body
        assertEquals(listOf("deleted_at", "body"), fields.map { it.name })

        val deletedAt = fields[0]
        assertEquals(FieldType.TIME, deletedAt.type)
        assertTrue(deletedAt.nullable, "deleted_at must be nullable so a live row can hold null")
    }

    @Test
    fun `DeletedAt is mutable — applications soft-delete and restore by updating it`() {
        val memo = finalized(DeletableMemo())
        val deletedAt = memo.fields().first { it.name == "deleted_at" }

        // The whole convention rests on the field being writable through
        // the generated update builder; if a future refactor accidentally
        // marks it immutable, soft-delete / restore stops working.
        assertEquals(false, deletedAt.immutable)
    }

    @Test
    fun `DeletedAt adds no indexes — applications declare partial-unique or lookup indexes themselves`() {
        val memo = finalized(DeletableMemo())
        assertEquals(emptyList(), memo.indexes())
    }

    @Test
    fun `a schema without the mixin has no deleted_at field`() {
        val memo = finalized(PlainMemo())
        assertEquals(listOf("body"), memo.fields().map { it.name })
    }
}
