@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Deliberately NOT @Serializable: the element type of the failing case.
// Top-level so the compiled test classpath (inherited by the snippet
// compilation) carries it as a real class.
data class PlainRect(val x: Int, val y: Int)

private class RectBoard : EntSchema("rect_boards") {
    override fun id() = EntId.long()
    val rects = json<List<PlainRect>>("rects")
}

private class BareBoard : EntSchema("bare_boards") {
    override fun id() = EntId.long()
    val title = string("title")
}

/**
 * Compile-fail test for typed JSON's core contract: generated code
 * references statically-resolved serializers
 * (`ListSerializer(PlainRect.serializer())`), so a json type whose classes
 * lack kotlinx serialization support **fails at consumer compile time**
 * rather than as a late read failure. The other JSON tests prove the
 * passing side (integration codegen compiles with `@Serializable` types);
 * this pins the failing side.
 *
 * The whole generated output for the schema is compiled in-process via
 * kctfork with the test classpath inherited, so the snippet sees entkt's
 * runtime/query classes, kotlinx-serialization-core, and the element
 * class itself — everything except the generated serializer that
 * `@Serializable` would have produced.
 */
class JsonCompileFailTest {

    private fun generatedSources(name: String, schema: EntSchema): List<SourceFile> {
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(schema::class to schema)
        schema.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(name, schema)))
            .map { SourceFile.kotlin("${it.name}.kt", it.toString()) }
    }

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            // The generated code references entkt runtime/query classes,
            // kotlinx-serialization-core, and the test-defined element class.
            inheritClassPath = true
            // kctfork bundles its own kotlinc, which may lag the project's
            // compiler; the contract under test (companion serializer
            // resolution) is independent of the metadata version.
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            // The inherited classpath is built for 17; match it so calls
            // into inline entkt functions don't trip the jvm-target check.
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    @Test
    fun `control - generated code without a json field compiles`() {
        // Proves the harness itself is sound (classpath, targets, codegen
        // output) so the failing test below can't pass vacuously.
        val result = compile(generatedSources("BareBoard", BareBoard()))
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the json-free generated code to compile, got:\n${result.messages}",
        )
    }

    @Test
    fun `a json type argument without kotlinx serialization support fails to compile`() {
        val result = compile(generatedSources("RectBoard", RectBoard()))
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected generated code for json<List<PlainRect>> to fail compilation " +
                "(PlainRect is not @Serializable) but it succeeded",
        )
        assertTrue(
            "serializer" in result.messages,
            "Expected the error to point at the missing serializer, got:\n${result.messages}",
        )
    }
}
