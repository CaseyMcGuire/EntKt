@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.schema

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldModifiersCompileTest {
    private fun compile(snippet: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "Modifiers.kt",
                    """
                    import entkt.schema.*
                    import entkt.postgres.vector.*

                    enum class Status { OPEN, CLOSED }
                    data class Metadata(val value: String)

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
    fun `supported modifiers preserve each concrete builder type`() {
        val result = compile(
            """
            class AuditFields(scope: EntMixin.Scope) : EntMixin(scope) {
                val eventKey: StringFieldBuilder by string("event_key").nullable().unique().immutable()
            }

            class Document : EntSchema("documents", clientName = "documents") {
                override fun id() = EntId.long()
                val title: StringFieldBuilder by string("title").nullable().unique().default("")
                val active: BoolFieldBuilder by bool("active").nullable().unique().default(true)
                val count: IntFieldBuilder by int("count").nullable().unique().default(1)
                val total: LongFieldBuilder by long("total").nullable().unique().default(1L)
                val score: FloatFieldBuilder by float("score").nullable().unique().default(1f)
                val ratio: DoubleFieldBuilder by double("ratio").nullable().unique().default(1.0)
                val createdAt: InstantFieldBuilder by instant("created_at").nullable().unique().defaultNow()
                val externalId: UuidFieldBuilder by uuid("external_id").nullable().unique().immutable()
                val payload: BytesFieldBuilder by bytes("payload").nullable().unique().sensitive()
                val status: EnumFieldBuilder<Status> by enum<Status>("status").nullable().unique().default(Status.OPEN)
                val metadata: JsonFieldBuilder<Metadata> by json<Metadata>("metadata")
                    .nullable().immutable().sensitive().comment("Metadata")
                val embedding: PgVectorFieldBuilder by postgresVector("embedding", 4)
                    .nullable().immutable().sensitive().comment("Embedding")
                val audit = include(::AuditFields)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `JSON and vector fields reject uniqueness before and after common modifiers`() {
        val declarations = listOf(
            "json<Metadata>(\"value\")",
            "json(\"value\", Metadata::class)",
            "postgresVector(\"value\", 4)",
        )
        for (declaration in declarations) {
            for (modifiers in listOf("", ".nullable().immutable().sensitive().comment(\"Value\")")) {
                val result = compile(
                    """
                    class Document : EntSchema("documents", clientName = "documents") {
                        override fun id() = EntId.long()
                        val value by $declaration$modifiers.unique()
                    }
                    """.trimIndent(),
                )

                assertUniqueUnavailable(result)
            }
        }
    }

    @Test
    fun `the common field builder cannot enable uniqueness`() {
        val result = compile(
            """
            fun configure(builder: FieldBuilder<*, *>) {
                builder.unique()
            }
            """.trimIndent(),
        )

        assertUniqueUnavailable(result)
    }

    private fun assertUniqueUnavailable(result: JvmCompilationResult) {
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("Unresolved reference 'unique'"), result.messages)
    }
}
