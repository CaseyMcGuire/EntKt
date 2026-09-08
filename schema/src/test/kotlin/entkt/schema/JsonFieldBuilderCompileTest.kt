@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.schema

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonFieldBuilderCompileTest {
    private fun compile(snippet: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "JsonFields.kt",
                    """
                    import entkt.schema.*

                    data class Metadata(val label: String)
                    data class OtherMetadata(val value: Int)

                    $snippet
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            jvmTarget = "17"
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    @Test
    fun `both JSON overloads retain concrete and nested types through modifiers and delegation`() {
        val result = compile(
            """
            class Document : EntSchema("documents", clientName = "documents") {
                override fun id() = EntId.long()
                val metadata: JsonFieldBuilder<Metadata> by json("metadata", Metadata::class)
                    .nullable().immutable().sensitive().comment("Metadata")
                val required: JsonFieldBuilder<Metadata> by json<Metadata>("required")
                val items by json<List<Metadata>>("items").nullable()
                val nested by json<Map<String, List<Metadata?>>>("nested").comment("Nested values")
            }

            fun handles(document: Document) {
                val metadata: FieldHandle<Metadata> = document.metadata
                val required: FieldHandle<Metadata> = document.required
                val items: FieldHandle<List<Metadata>> = document.items
                val nested: FieldHandle<Map<String, List<Metadata?>>> = document.nested
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `inferred JSON handles reject a different value type`() {
        for (declaration in listOf("json<Metadata>(\"metadata\")", "json(\"metadata\", Metadata::class)")) {
            assertTypeMismatch(declaration, "OtherMetadata")
        }
    }

    @Test
    fun `inferred JSON handles reject a different nested element type`() {
        assertTypeMismatch("json<List<Metadata>>(\"metadata\")", "List<OtherMetadata>")
    }

    private fun assertTypeMismatch(declaration: String, expectedType: String) {
        val result = compile(
            """
            class Document : EntSchema("documents", clientName = "documents") {
                override fun id() = EntId.long()
                val metadata by $declaration.nullable().comment("Metadata")
            }

            fun consume(handle: FieldHandle<$expectedType>) {}
            fun wrongType(document: Document) = consume(document.metadata)
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("mismatch", ignoreCase = true), result.messages)
        assertTrue(result.messages.contains("OtherMetadata"), result.messages)
    }
}
