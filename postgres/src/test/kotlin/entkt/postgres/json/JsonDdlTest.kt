package entkt.postgres.json

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
import kotlin.test.assertTrue

// A different JSON class for the class-change test. Plain is fine — the
// migration path only sees the jsonb SQL type, never the serializer.
private data class Other(val count: Int)

private class JsonDoc : EntSchema("json_docs") {
    override fun id() = EntId.long()
    val title = text("title")
    val metadata = json("metadata", Meta::class).nullable()
}

class JsonDdlTest {

    private val typeMapper = PostgresTypeMapper()
    private val renderer = PostgresSqlRenderer()
    private val differ = SchemaDiffer()

    private fun normalized(vararg schemas: EntSchema): NormalizedSchema =
        NormalizedSchema.fromEntitySchemas(
            buildEntitySchemas(schemas.map { SchemaInput(it::class.simpleName ?: "S", it) }),
            typeMapper,
        )

    @Test
    fun `json column renders jsonb DDL`() {
        val table = normalized(JsonDoc()).tables.getValue("json_docs")
        val createTable = renderer.render(MigrationOp.CreateTable(table)).joinToString("\n")
        assertTrue("\"metadata\" jsonb" in createTable, createTable)
    }

    @Test
    fun `an unchanged json schema produces no ops`() {
        val n = normalized(JsonDoc())
        val result = differ.diff(n, n)
        assertTrue(result.ops.isEmpty(), result.ops.toString())
        assertTrue(result.manual.isEmpty(), result.manual.toString())
    }

    @Test
    fun `changing only the Kotlin JSON class produces no migration`() {
        // Migrations diff only DB facts (jsonb + nullability), never the Kotlin
        // class or serializer — so swapping the JSON type yields no ops.
        class DocA : EntSchema("json_docs") {
            override fun id() = EntId.long()
            val metadata = json("metadata", Meta::class).nullable()
        }
        class DocB : EntSchema("json_docs") {
            override fun id() = EntId.long()
            val metadata = json("metadata", Other::class).nullable()
        }
        val result = differ.diff(normalized(DocA()), normalized(DocB()))
        assertTrue(result.ops.isEmpty(), result.ops.toString())
        assertTrue(result.manual.isEmpty(), result.manual.toString())
    }
}
