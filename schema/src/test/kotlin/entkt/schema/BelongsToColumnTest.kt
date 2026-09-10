package entkt.schema

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BelongsToColumnTest {
    private class Target : EntSchema("targets", clientName = "targets") {
        override fun id() = EntId.long()
    }

    @Test
    fun `belongsTo stores the column verbatim regardless of suffix`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val problemLanguage by belongsTo<Target>(column = "problem_language_id")
            val writer by belongsTo<Target>("legacy_writer_ref")
            val owner by belongsTo<Target>("owner")
            val unusual by belongsTo<Target>("legacy_id_id")
            val byRelationship by index("idx_relationships", problemLanguage.fk, writer.fk, owner.fk, unusual.fk)
        }
        val record = Record()
        val columns = listOf("problem_language_id", "legacy_writer_ref", "owner", "legacy_id_id")
        assertEquals(columns, listOf(record.problemLanguage.fk, record.writer.fk, record.owner.fk, record.unusual.fk).map { it.fieldName })
        val registry = listOf(Target(), record).associateBy { it::class }
        registry.values.forEach { it.finalize(registry) }
        assertEquals(columns, record.indexes().single().fields)
        assertEquals(columns, record.edges().map { it.name })
        assertEquals(listOf("problemLanguage", "writer", "owner", "unusual"), record.edges().map { it.declarationName })
    }

    @Test
    fun `a backing field must agree with the declared column`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val writer by long("writer_ref")
            val author by belongsTo<Target>("author_id").field(writer)
        }
        val error = assertFailsWith<IllegalArgumentException> { Record() }
        assertContains(error.message!!, "declares FK column 'author_id'")
        assertContains(error.message!!, "backing field's exact column name")
    }

    @Test
    fun `matching backing fields and FK handles describe the same column`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val writer by long("writer_ref")
            val author by belongsTo<Target>("writer_ref").field(writer)
            val byAuthor = index("idx_author", author.fk)
        }
        val record = Record()
        val registry = listOf(Target(), record).associateBy { it::class }
        registry.values.forEach { it.finalize(registry) }
        val edge = record.edges().single()
        assertEquals("writer_ref", edge.name)
        assertEquals("writer_ref", (edge.kind as EdgeKind.BelongsTo).field)
        assertEquals(listOf("writer_ref"), record.indexes().single().fields)
    }

    @Test
    fun `unique FK duplicate index checks use the literal column`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val owner by belongsTo<Target>("owner_ref").unique()
            val byOwner = index("uq_owner", owner.fk).unique()
        }
        val record = Record()
        val registry = listOf(Target(), record).associateBy { it::class }
        registry.values.forEach { it.finalize(registry) }
        val error = assertFailsWith<IllegalArgumentException> { record.indexes() }
        assertContains(error.message!!, "duplicate semantic indexes are not allowed")
    }
}
