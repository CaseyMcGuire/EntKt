package entkt.codegen

import entkt.postgres.vector.VectorMetric
import entkt.postgres.vector.hnsw
import entkt.postgres.vector.postgresVector
import entkt.postgres.vector.postgresVectorIndex
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertTrue

private fun finalizeVec(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

private class VecArticle : EntSchema("articles", clientName = "vecArticles") {
    override fun id() = EntId.long()
    val title by string("title")
    val embedding by postgresVector("embedding", dimensions = 1536).nullable()
    val embHnsw = postgresVectorIndex("idx_articles_embedding_hnsw", embedding).hnsw(VectorMetric.Cosine)
}

class PgVectorCodegenTest {

    private fun gen(): Map<String, String> {
        val s = VecArticle()
        finalizeVec(s)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(s)))
            .associate { it.name to it.toString().replace("\\s+".toRegex(), " ") }
    }

    @Test
    fun `entity exposes a PgVector property and imports the value type`() {
        val entity = gen().getValue("VecArticle")
        assertTrue("embedding: PgVector?" in entity, entity)
        assertTrue("import entkt.postgres.vector.PgVector" in entity, entity)
    }

    @Test
    fun `fromRow decodes the vector via a direct cast (driver owns decode)`() {
        val entity = gen().getValue("VecArticle")
        assertTrue("""embedding = row["embedding"] as PgVector?""" in entity, entity)
    }

    @Test
    fun `SCHEMA literal carries ColumnStorage Native for the vector column`() {
        val entity = gen().getValue("VecArticle")
        // kotlinpoet renders Int literals with digit-group underscores (1536 -> 1_536).
        assertTrue(
            """storage = ColumnStorage.Native(dialect = "postgres", typeName = "vector", sqlType = "vector(1536)", codec = "postgres.vector", requiredExtension = "vector", dimensions = 1_536)""" in entity,
            entity,
        )
    }

    @Test
    fun `SCHEMA literal carries the vector index metadata (using + opclasses)`() {
        val entity = gen().getValue("VecArticle")
        assertTrue("""using = "hnsw"""" in entity, entity)
        assertTrue("""opclasses = listOf("vector_cosine_ops")""" in entity, entity)
    }

    @Test
    fun `create field validation checks the vector dimension`() {
        val create = gen().getValue("VecArticleRepo").replace("\\s+".toRegex(), " ")
        assertTrue(
            "if (candidate.embedding != null && candidate.embedding.dimensions != 1_536) return listOf(ValidationViolation(\"embedding expects vector(1536)\", field = \"embedding\"))" in create,
            create,
        )
    }

    @Test
    fun `update write-map validates the vector dimension`() {
        val update = gen().getValue("VecArticleUpdate")
        assertTrue("require(vec.dimensions == 1_536)" in update, update)
        assertTrue("""values["embedding"] = it.value?.also""" in update, update)
    }
}
