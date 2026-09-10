package entkt.schema

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BelongsToImmutableTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
        val records by hasMany<Record>("records")
    }

    private class Record : EntSchema("records", clientName = "records") {
        override fun id() = EntId.long()
        val owner by belongsTo<Owner>("owner_id").immutable().inverse(Owner::records)
        val reviewer by belongsTo<Owner>("reviewer_id").nullable().unique().immutable().onDelete(OnDelete.SET_NULL)
        val editor by belongsTo<Owner>("editor_id")
        val writer by long("writer_id")
        val author by belongsTo<Owner>("writer_id").immutable().field(writer)
        val byOwner = index("idx_records_owner", owner.fk)
        val byAuthor = index("idx_records_author", author.fk)
    }

    private fun record(): Record {
        val record = Record()
        val schemas = listOf(Owner(), record)
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        return record
    }

    @Test
    fun `immutable composes with belongsTo modifiers and FK indexes`() {
        val record = record()
        val edges = record.edges().associateBy { it.declarationName }
        val owner = edges.getValue("owner")
        assertTrue((owner.kind as EdgeKind.BelongsTo).immutable)
        assertEquals("records", owner.ref)
        assertEquals(
            EdgeKind.BelongsTo(required = false, unique = true, onDelete = OnDelete.SET_NULL, immutable = true),
            edges.getValue("reviewer").kind,
        )
        assertFalse((edges.getValue("editor").kind as EdgeKind.BelongsTo).immutable)
        assertEquals(
            EdgeKind.BelongsTo(field = "writer_id", immutable = true),
            edges.getValue("author").kind,
        )
        assertEquals(listOf(listOf("owner_id"), listOf("writer_id")), record.indexes().map { it.fields })
    }

    @Test
    fun `immutable cannot be changed after finalization`() {
        val record = record()
        val error = assertFailsWith<IllegalStateException> { record.editor.immutable() }
        assertContains(error.message!!, "cannot be modified after schema finalization")
    }
}
