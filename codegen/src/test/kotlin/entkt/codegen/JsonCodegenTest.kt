package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Plain data class: the codegen tests assert on generated TEXT, not compiled
// output, so @Serializable isn't required here (it is exercised end-to-end in
// integration-tests). Generated code references Meta.serializer() either way.
data class Meta(val nickname: String?, val tags: List<String>)

private class JsonArticle : EntSchema("articles") {
    override fun id() = EntId.long()
    val title = string("title")
    val metadata = json("metadata", Meta::class).nullable()
}

// Generic JSON shapes: the full KType must survive into the property type,
// the column ref, the fromRow cast, and the serializer expression.
private class JsonBoard : EntSchema("boards") {
    override fun id() = EntId.long()
    val rects = json<List<Meta>>("rects")
    val labels = json<Map<String, Meta>>("labels").nullable()
    val sparse = json<List<Meta?>>("sparse")
}

class JsonCodegenTest {

    private fun gen(name: String, schema: EntSchema): Map<String, String> {
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(schema::class to schema)
        schema.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(name, schema)))
            .associate { it.name to it.toString().replace("\\s+".toRegex(), " ") }
    }

    private fun gen(): Map<String, String> = gen("Article", JsonArticle())

    private fun genGeneric(): Map<String, String> = gen("Board", JsonBoard())

    @Test
    fun `entity exposes the supplied JSON class as the property type`() {
        val entity = gen().getValue("Article")
        assertTrue("metadata: Meta?" in entity, entity)
    }

    @Test
    fun `companion column ref is a narrow NullableJsonColumn`() {
        val entity = gen().getValue("Article")
        assertTrue("NullableJsonColumn<Article, Meta>" in entity, entity)
        assertTrue("import entkt.query.NullableJsonColumn" in entity, entity)
    }

    @Test
    fun `fromRow decodes JSON via a direct cast (driver owns decode)`() {
        val entity = gen().getValue("Article")
        assertTrue("""metadata = row["metadata"] as Meta?""" in entity, entity)
    }

    @Test
    fun `SCHEMA literal carries JsonColumnMetadata with the serializer`() {
        val entity = gen().getValue("Article")
        assertTrue(
            """json = JsonColumnMetadata(klass = Meta::class, serializer = Meta.serializer(), typeName = "entkt.codegen.Meta")""" in entity,
            entity,
        )
    }

    @Test
    fun `a non-generic JSON field does not suppress unchecked casts`() {
        val entity = gen().getValue("Article")
        assertFalse("UNCHECKED_CAST" in entity, entity)
    }

    @Test
    fun `create write-map passes the typed JSON value straight through`() {
        val create = gen().getValue("ArticleCreate")
        assertTrue(""""metadata" to metadata,""" in create, create)
    }

    // ── Generic JSON types ─────────────────────────────────────────

    @Test
    fun `entity exposes the full parameterized type, not a raw class`() {
        val entity = genGeneric().getValue("Board")
        assertTrue("rects: List<Meta>" in entity, entity)
        assertTrue("labels: Map<String, Meta>?" in entity, entity)
    }

    @Test
    fun `generic column refs carry the parameterized type`() {
        val entity = genGeneric().getValue("Board")
        assertTrue("JsonColumn<Board, List<Meta>>" in entity, entity)
        assertTrue("NullableJsonColumn<Board, Map<String, Meta>>" in entity, entity)
    }

    @Test
    fun `fromRow casts to the parameterized type under an unchecked-cast suppression`() {
        val entity = genGeneric().getValue("Board")
        assertTrue("""rects = row["rects"] as List<Meta>""" in entity, entity)
        assertTrue("""@Suppress("UNCHECKED_CAST") public fun fromRow""" in entity, entity)
    }

    @Test
    fun `SCHEMA literal builds collection serializers from builtins factories`() {
        val entity = genGeneric().getValue("Board")
        assertTrue(
            """json = JsonColumnMetadata(klass = List::class, serializer = ListSerializer(Meta.serializer()), typeName = "kotlin.collections.List<entkt.codegen.Meta>")""" in entity,
            entity,
        )
        assertTrue(
            """json = JsonColumnMetadata(klass = Map::class, serializer = MapSerializer(String.serializer(), Meta.serializer()), typeName = "kotlin.collections.Map<kotlin.String, entkt.codegen.Meta>")""" in entity,
            entity,
        )
        assertTrue("import kotlinx.serialization.builtins.ListSerializer" in entity, entity)
        assertTrue("import kotlinx.serialization.builtins.MapSerializer" in entity, entity)
        assertTrue("import kotlinx.serialization.builtins.serializer" in entity, entity)
    }

    @Test
    fun `a nullable type argument wraps its serializer with nullable`() {
        val entity = genGeneric().getValue("Board")
        assertTrue("serializer = ListSerializer(Meta.serializer().nullable)" in entity, entity)
        assertTrue("import kotlinx.serialization.builtins.nullable" in entity, entity)
    }

    @Test
    fun `generic create write-map passes the typed value straight through`() {
        val create = genGeneric().getValue("BoardCreate")
        assertTrue(""""rects" to rects,""" in create, create)
        assertTrue("rects: List<Meta>" in create, create)
    }
}
