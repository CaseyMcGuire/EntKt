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

    // ── Vector index validation ────────────────────────────────────

    @Test
    fun `postgresVectorIndex on a non-vector column is rejected at declaration`() {
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("nv") {
                override fun id() = EntId.long()
                val title = string("title")
                val i = postgresVectorIndex("i", title).hnsw(VectorMetric.Cosine)
            }
        }
        assertTrue("requires a pgvector column" in (err.message ?: ""), err.message ?: "")
    }

    @Test
    fun `postgresVectorIndex on a column wider than 2000 dimensions is rejected`() {
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("big") {
                override fun id() = EntId.long()
                val emb = postgresVector("emb", 3072)
                val i = postgresVectorIndex("i", emb).hnsw(VectorMetric.Cosine)
            }
        }
        assertTrue("2000 dimensions" in (err.message ?: ""), err.message ?: "")
    }

    @Test
    fun `hnsw on a plain index() is rejected`() {
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("plain") {
                override fun id() = EntId.long()
                val emb = postgresVector("emb", 4)
                val i = index("i", emb).hnsw(VectorMetric.Cosine)
            }
        }
        assertTrue("only valid on a postgresVectorIndex" in (err.message ?: ""), err.message ?: "")
    }

    @Test
    fun `ivfflat with non-positive lists is rejected`() {
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("ivf") {
                override fun id() = EntId.long()
                val emb = postgresVector("emb", 4)
                val i = postgresVectorIndex("i", emb).ivfflat(VectorMetric.L2, lists = 0)
            }
        }
        assertTrue("lists must be > 0" in (err.message ?: ""), err.message ?: "")
    }

    @Test
    fun `unique on a vector index is rejected at build`() {
        val s = object : EntSchema("uqidx") {
            override fun id() = EntId.long()
            val emb = postgresVector("emb", 4)
            val i = postgresVectorIndex("i", emb).hnsw(VectorMetric.Cosine).unique()
        }
        finalize(s)
        val err = assertFailsWith<IllegalArgumentException> { s.indexes() }
        assertTrue("cannot be UNIQUE" in (err.message ?: ""), err.message ?: "")
    }

    @Test
    fun `vector index without an access method is rejected at build`() {
        val s = object : EntSchema("noam") {
            override fun id() = EntId.long()
            val emb = postgresVector("emb", 4)
            val i = postgresVectorIndex("i", emb)
        }
        finalize(s)
        val err = assertFailsWith<IllegalArgumentException> { s.indexes() }
        assertTrue("requires an access method" in (err.message ?: ""), err.message ?: "")
    }

    @Test
    fun `valid hnsw index builds with its native metadata`() {
        val s = object : EntSchema("okh") {
            override fun id() = EntId.long()
            val emb = postgresVector("emb", 4)
            val h = postgresVectorIndex("h", emb).hnsw(VectorMetric.Cosine)
        }
        finalize(s)
        val h = s.indexes().single()
        assertEquals("hnsw", h.using)
        assertEquals(listOf("vector_cosine_ops"), h.opclasses)
        assertEquals(null, h.with)
        assertEquals(false, h.unique)
    }

    @Test
    fun `valid ivfflat index builds with its native metadata`() {
        val s = object : EntSchema("okv") {
            override fun id() = EntId.long()
            val emb = postgresVector("emb", 4)
            val v = postgresVectorIndex("v", emb).ivfflat(VectorMetric.L2, lists = 100)
        }
        finalize(s)
        val v = s.indexes().single()
        assertEquals("ivfflat", v.using)
        assertEquals(listOf("vector_l2_ops"), v.opclasses)
        assertEquals(mapOf("lists" to "100"), v.with)
    }
}
