package entkt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// A plain data class is enough here: the schema DSL stores only the KClass.
// The @Serializable requirement is a codegen-time concern (generated code
// references `X.serializer()`), not a schema-DSL one.
private data class Meta(val nickname: String?, val tags: List<String>)

private class JsonSchema : EntSchema("docs") {
    override fun id() = EntId.long()
    val title = string("title")
    val metadata = json("metadata", Meta::class).nullable()
    val required = json<Meta>("required")
}

class JsonFieldTest {

    private fun finalize(vararg schemas: EntSchema) {
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
    }

    @Test
    fun `json builds a JSON field carrying its jsonClass`() {
        val s = JsonSchema()
        finalize(s)
        val fields = s.fields().associateBy { it.name }

        val meta = fields.getValue("metadata")
        assertEquals(FieldType.JSON, meta.type)
        assertTrue(meta.nullable)
        assertEquals(Meta::class, meta.jsonClass)

        // Reified overload produces the same field, non-null.
        val req = fields.getValue("required")
        assertEquals(FieldType.JSON, req.type)
        assertEquals(Meta::class, req.jsonClass)
        assertTrue(!req.nullable)
    }

    @Test
    fun `unique on a json field is rejected at build`() {
        val s = object : EntSchema("u") {
            override fun id() = EntId.long()
            val m = json("m", Meta::class).unique()
        }
        finalize(s)
        // build() runs in fields(); the JSON .unique() rejection fires there.
        val err = assertFailsWith<IllegalStateException> { s.fields() }
        assertTrue("JSON column" in (err.message ?: ""), "got: ${err.message}")
        assertTrue("unique" in (err.message ?: "").lowercase(), "got: ${err.message}")
    }
}
