package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
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

class JsonCodegenTest {

    private fun gen(): Map<String, String> {
        val s = JsonArticle()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(s::class to s)
        s.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput("Article", s)))
            .associate { it.name to it.toString().replace("\\s+".toRegex(), " ") }
    }

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
            """json = JsonColumnMetadata(klass = Meta::class, serializer = Meta.serializer())""" in entity,
            entity,
        )
    }

    @Test
    fun `create write-map passes the typed JSON value straight through`() {
        val create = gen().getValue("ArticleCreate")
        assertTrue(""""metadata" to metadata,""" in create, create)
    }
}
