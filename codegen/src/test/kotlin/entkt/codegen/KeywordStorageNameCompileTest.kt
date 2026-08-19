@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class KeywordItem : EntSchema("keyword_items", clientName = "keywordItems") {
    override fun id() = EntId.long()

    /** Inverse of [KeywordStorage.related]; storage name is a keyword too. */
    val holder by belongsTo<KeywordStorage>("object").inverse(KeywordStorage::related)
}

/**
 * Every storage string here is a Kotlin hard keyword. The declarations
 * that name the generated API are ordinary identifiers, so the two
 * vocabularies are exercised against each other.
 */
private class KeywordStorage : EntSchema("object", clientName = "keywordStorages") {
    override fun id() = EntId.long()

    val category by string("class")
    val whenever by time("when")
    val truthy by bool("true")
    val related by hasMany<KeywordItem>("object")

    val byCategory = index("class", category)
}

/**
 * Storage names are Kotlin-keyword-agnostic: they reach generated source
 * only as string literals and reach SQL only as quoted identifiers, so a
 * column named `class` is legal. Only *declaration* names have to be
 * Kotlin identifiers.
 *
 * Compiling the generated source is the assertion — a storage string
 * leaking into a raw identifier position would produce `val class = …`
 * and fail here.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
class KeywordStorageNameCompileTest {

    private fun generate(): List<com.squareup.kotlinpoet.FileSpec> {
        val item = KeywordItem()
        val schema = KeywordStorage()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            item::class to item,
            schema::class to schema,
        )
        item.finalize(registry)
        schema.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(item), SchemaInput(schema)))
    }

    @Test
    fun `keyword storage names generate compilable source`() {
        val result = KotlinCompilation().apply {
            sources = generate().toCompileTestSources()
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "keyword storage names must not reach a raw identifier position:\n${result.messages}",
        )
    }

    @Test
    fun `the Kotlin API comes from the declarations, and storage keeps the keywords`() {
        val entity = generate().first { it.name == "KeywordStorage" }.toString()

        // Declarations name the API...
        for (api in listOf("category", "whenever", "truthy")) {
            assertTrue("public val $api:" in entity, "expected API property '$api'\n$entity")
        }
        // ...and the keywords survive as storage strings.
        assertTrue("""TABLE: String = "object"""" in entity, entity)
        for (storage in listOf("\"class\"", "\"when\"", "\"true\"")) {
            assertTrue(storage in entity, "expected storage literal $storage\n$entity")
        }
    }
}
