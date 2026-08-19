package entkt.schema

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// A plain data class is enough here: the schema DSL stores only the KType.
// The @Serializable requirement is a codegen-time concern (generated code
// references `X.serializer()`), not a schema-DSL one.
private data class Meta(val nickname: String?, val tags: List<String>)

private class JsonSchema : EntSchema("docs", clientName = "jsonSchemas") {
    override fun id() = EntId.long()
    val title by string("title")
    val metadata by json("metadata", Meta::class).nullable()
    val required by json<Meta>("required")
    val items by json<List<Meta>>("items")
    val labels by json<Map<String, Meta?>>("labels").nullable()
}

class JsonFieldTest {

    private fun finalize(vararg schemas: EntSchema) {
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
    }

    @Test
    fun `json builds a JSON field carrying its jsonType`() {
        val s = JsonSchema()
        finalize(s)
        val fields = s.fields().associateBy { it.name }

        val meta = fields.getValue("metadata")
        assertEquals(FieldType.JSON, meta.type)
        assertTrue(meta.nullable)
        assertEquals(typeOf<Meta>(), meta.jsonType)
        assertEquals(Meta::class, meta.jsonClass)

        // Reified overload produces the same field, non-null.
        val req = fields.getValue("required")
        assertEquals(FieldType.JSON, req.type)
        assertEquals(typeOf<Meta>(), req.jsonType)
        assertTrue(!req.nullable)
    }

    @Test
    fun `json captures full generic types, type arguments intact`() {
        val s = JsonSchema()
        finalize(s)
        val fields = s.fields().associateBy { it.name }

        val items = fields.getValue("items")
        assertEquals(typeOf<List<Meta>>(), items.jsonType)
        // jsonClass is the raw classifier — the element type lives on jsonType.
        assertEquals(List::class, items.jsonClass)

        val labels = fields.getValue("labels")
        assertEquals(typeOf<Map<String, Meta?>>(), labels.jsonType)
        assertEquals(Map::class, labels.jsonClass)
    }

    @Test
    fun `the KClass overload rejects classes with type parameters`() {
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("bad", clientName = "bad") {
                override fun id() = EntId.long()
                val m by json("m", List::class)
            }
        }
        assertTrue("type parameters" in (err.message ?: ""), "got: ${err.message}")
        assertTrue("json<List<Element>>" in (err.message ?: ""), "should point at the reified overload: ${err.message}")
    }

    @Test
    fun `a star projection is rejected at registration`() {
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("bad", clientName = "bad") {
                override fun id() = EntId.long()
                val m by json<List<*>>("m")
            }
        }
        assertTrue("star projection" in (err.message ?: ""), "got: ${err.message}")
    }

    @Test
    fun `a variance projection is rejected at registration`() {
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("bad", clientName = "bad") {
                override fun id() = EntId.long()
                val m by json<List<out Meta>>("m")
            }
        }
        assertTrue("projection" in (err.message ?: ""), "got: ${err.message}")
    }

    @Test
    fun `a json column in an index is rejected at build`() {
        val s = object : EntSchema("idx", clientName = "idx") {
            override fun id() = EntId.long()
            val m by json("m", Meta::class).nullable()
            val i = index("idx_m", m)
        }
        finalize(s)
        // build() runs in indexes(); JSON columns can't be indexed in V1.
        val err = assertFailsWith<IllegalStateException> { s.indexes() }
        assertTrue("JSON column" in (err.message ?: ""), "got: ${err.message}")
        assertTrue("'m'" in (err.message ?: ""), "should name the column: ${err.message}")
    }

    @Test
    fun `a unique index over a json column is rejected at build`() {
        val s = object : EntSchema("uidx", clientName = "uidx") {
            override fun id() = EntId.long()
            val m by json("m", Meta::class).nullable()
            val i = index("idx_m", m).unique()
        }
        finalize(s)
        val err = assertFailsWith<IllegalStateException> { s.indexes() }
        assertTrue("JSON column" in (err.message ?: ""), "got: ${err.message}")
    }

    @Test
    fun `unique on a json field is rejected at build`() {
        val s = object : EntSchema("u", clientName = "u") {
            override fun id() = EntId.long()
            val m by json("m", Meta::class).unique()
        }
        finalize(s)
        // build() runs in fields(); the JSON .unique() rejection fires there.
        val err = assertFailsWith<IllegalStateException> { s.fields() }
        assertTrue("JSON column" in (err.message ?: ""), "got: ${err.message}")
        assertTrue("unique" in (err.message ?: "").lowercase(), "got: ${err.message}")
    }
}
