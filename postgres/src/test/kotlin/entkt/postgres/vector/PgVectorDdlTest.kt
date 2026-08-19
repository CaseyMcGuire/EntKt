package entkt.postgres.vector

import entkt.codegen.SchemaInput
import entkt.codegen.buildEntitySchemas
import entkt.migrations.MigrationOp
import entkt.migrations.NormalizedSchema
import entkt.migrations.SchemaDiffer
import entkt.postgres.PostgresSqlRenderer
import entkt.postgres.PostgresTypeMapper
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class VecDdlArticle : EntSchema("articles", clientName = "vecDdlArticles") {
    override fun id() = EntId.long()
    val title by text("title")
    val embedding by postgresVector("embedding", 1536).nullable()
    val embHnsw = postgresVectorIndex("idx_articles_embedding_hnsw", embedding).hnsw(VectorMetric.Cosine)
}

private class VecDdlIvf : EntSchema("ivf_items", clientName = "vecDdlIvfs") {
    override fun id() = EntId.long()
    val embedding by postgresVector("embedding", 3)
    val ivf = postgresVectorIndex("idx_ivf_embedding", embedding).ivfflat(VectorMetric.L2, lists = 100)
}

class PgVectorDdlTest {

    private val typeMapper = PostgresTypeMapper()
    private val renderer = PostgresSqlRenderer()
    private val differ = SchemaDiffer()

    private fun normalized(vararg schemas: EntSchema): NormalizedSchema =
        NormalizedSchema.fromEntitySchemas(
            buildEntitySchemas(schemas.map { SchemaInput(it) }),
            typeMapper,
        )

    @Test
    fun `vector column and hnsw index render the expected DDL`() {
        val table = normalized(VecDdlArticle()).tables.getValue("articles")
        val createTable = renderer.render(MigrationOp.CreateTable(table)).joinToString("\n")
        assertTrue("\"embedding\" vector(1536)" in createTable, createTable)
        val idxDdl = table.indexes.flatMap { renderer.render(MigrationOp.AddIndex("articles", it)) }
        assertTrue(
            idxDdl.any { "USING hnsw" in it && "\"embedding\" vector_cosine_ops" in it && "idx_articles_embedding_hnsw" in it },
            idxDdl.toString(),
        )
    }

    @Test
    fun `ivfflat index renders the operator class and WITH lists`() {
        val table = normalized(VecDdlIvf()).tables.getValue("ivf_items")
        val idxDdl = table.indexes.flatMap { renderer.render(MigrationOp.AddIndex("ivf_items", it)) }
        assertTrue(
            idxDdl.any { "USING ivfflat" in it && "vector_l2_ops" in it && "WITH (lists = 100)" in it },
            idxDdl.toString(),
        )
    }

    @Test
    fun `differ emits CreateExtension first for a new vector table`() {
        val result = differ.diff(normalized(VecDdlArticle()), NormalizedSchema(emptyMap()))
        assertEquals(MigrationOp.CreateExtension("vector"), result.ops.first())
        val extIdx = result.ops.indexOfFirst { it is MigrationOp.CreateExtension }
        val tableIdx = result.ops.indexOfFirst { it is MigrationOp.CreateTable }
        assertTrue(extIdx in 0 until tableIdx, "CreateExtension must precede CreateTable: ${result.ops}")
        assertEquals(
            """CREATE EXTENSION IF NOT EXISTS "vector"""",
            renderer.render(MigrationOp.CreateExtension("vector")).single(),
        )
    }

    @Test
    fun `an unchanged vector schema produces no ops (no spurious extension)`() {
        val n = normalized(VecDdlArticle())
        val result = differ.diff(n, n)
        assertTrue(result.ops.isEmpty(), result.ops.toString())
        assertTrue(result.manual.isEmpty(), result.manual.toString())
    }

    @Test
    fun `a vector dimension change is classified manual via the type-change path`() {
        class Big : EntSchema("articles", clientName = "bigs") {
            override fun id() = EntId.long()
            val title by text("title")
            val embedding by postgresVector("embedding", 3072).nullable()
        }
        class Small : EntSchema("articles", clientName = "smalls") {
            override fun id() = EntId.long()
            val title by text("title")
            val embedding by postgresVector("embedding", 1536).nullable()
        }
        val result = differ.diff(normalized(Big()), normalized(Small()))
        assertTrue(result.ops.none { it is MigrationOp.AlterColumnType }, "must not be auto: ${result.ops}")
        assertTrue(
            result.manual.any {
                it is MigrationOp.AlterColumnType && it.newType == "vector(3072)" && it.oldType == "vector(1536)"
            },
            "expected a manual vector(1536)->vector(3072) AlterColumnType: ${result.manual}",
        )
    }
}
