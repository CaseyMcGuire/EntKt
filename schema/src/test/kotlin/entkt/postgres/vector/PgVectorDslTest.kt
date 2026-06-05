package entkt.postgres.vector

import entkt.schema.ColumnStorage
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class VecSchema : EntSchema("articles") {
    override fun id() = EntId.long()
    val title = string("title")
    val embedding = postgresVector("embedding", dimensions = 1536).nullable()
}

class PgVectorDslTest {

    private fun finalize(vararg schemas: EntSchema) {
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
    }

    @Test
    fun `postgresVector builds a PGVECTOR field carrying native storage`() {
        val s = VecSchema()
        finalize(s)
        val f = s.fields().first { it.name == "embedding" }
        assertEquals(FieldType.PGVECTOR, f.type)
        assertTrue(f.nullable)
        val storage = f.storage
        assertTrue(storage is ColumnStorage.Native, "expected Native storage, got $storage")
        storage as ColumnStorage.Native
        assertEquals("postgres", storage.dialect)
        assertEquals("vector", storage.typeName)
        assertEquals("vector(1536)", storage.sqlType)
        assertEquals("postgres.vector", storage.codec)
        assertEquals("vector", storage.requiredExtension)
        assertEquals(1536, storage.dimensions)
    }

    @Test
    fun `dimensions out of 1_16000 throw at declaration`() {
        assertFailsWith<IllegalArgumentException> {
            object : EntSchema("t0") {
                override fun id() = EntId.long()
                val e = postgresVector("e", 0)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : EntSchema("t1") {
                override fun id() = EntId.long()
                val e = postgresVector("e", 16001)
            }
        }
        // 3072 (e.g. OpenAI text-embedding-3-large) is valid.
        object : EntSchema("t2") {
            override fun id() = EntId.long()
            val e = postgresVector("e", 3072)
        }
    }

    @Test
    fun `unique on a vector is rejected at build`() {
        val s = object : EntSchema("u") {
            override fun id() = EntId.long()
            val e = postgresVector("e", 4).unique()
        }
        finalize(s)
        // build() runs in fields(); the native-column .unique() rejection fires there.
        val err = assertFailsWith<IllegalStateException> { s.fields() }
        assertTrue("not supported" in (err.message ?: ""), "got: ${err.message}")
        assertTrue("vector" in (err.message ?: ""), "should name the native type: ${err.message}")
    }
}
