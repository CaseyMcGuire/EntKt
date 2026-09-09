@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.schema

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldTypeDslCompileTest {
    private fun compile(snippet: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Fields.kt", "import entkt.schema.*\n$snippet"))
            inheritClassPath = true
            jvmTarget = "17"
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    @Test
    fun `string and instant retain their types and defaults in schemas and mixins`() {
        val result = compile(
            """
            class AuditFields(scope: EntMixin.Scope) : EntMixin(scope) {
                val body: StringFieldBuilder by string("body").default("")
                val createdAt: InstantFieldBuilder by instant("created_at").defaultNow().immutable()
                val updatedAt by instant("updated_at").defaultNow().updateDefaultNow()
            }

            class Post : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                val title: StringFieldBuilder by string("title").nullable().default("Untitled")
                val publishedAt: InstantFieldBuilder by instant("published_at").nullable()
                val audit = include(::AuditFields)
            }

            fun handles(post: Post) {
                val title: FieldHandle<String> = post.title
                val publishedAt: FieldHandle<java.time.Instant> = post.publishedAt
                val updatedAt: FieldHandle<java.time.Instant> = post.audit.updatedAt
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `date stays LocalDate through modifiers delegation mixins and comparable columns`() {
        val result = compile(
            """
            class CalendarFields(scope: EntMixin.Scope) : EntMixin(scope) {
                val closedOn: DateFieldBuilder by date("closed_on").nullable()
                val byClosedOn = index("idx_closed_on", closedOn)
            }

            class Event : EntSchema("events", clientName = "events") {
                override fun id() = EntId.long()
                val startsOn: DateFieldBuilder by date("starts_on")
                    .nullable().unique().immutable().sensitive().comment("Start date")
                    .default(java.time.LocalDate.of(2024, 2, 29))
                val calendar = include(::CalendarFields)
            }

            fun handles(event: Event) {
                val startsOn: FieldHandle<java.time.LocalDate> = event.startsOn
                val closedOn: FieldHandle<java.time.LocalDate> = event.calendar.closedOn
                val column = entkt.query.ComparableColumn<Any, java.time.LocalDate>("starts_on")
                val nullableColumn = entkt.query.NullableComparableColumn<Any, java.time.LocalDate>("closed_on")
                val predicate: entkt.query.Predicate<Any> = column gte java.time.LocalDate.of(2024, 2, 29)
                val nullablePredicate: entkt.query.Predicate<Any> = nullableColumn lt java.time.LocalDate.of(2026, 1, 1)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `date defaults reject timestamps and strings`() {
        for (default in listOf("java.time.Instant.EPOCH", "\"2024-02-29\"")) {
            val result = compile(
                """
                class Event : EntSchema("events", clientName = "events") {
                    override fun id() = EntId.long()
                    val startsOn by date("starts_on").nullable().default($default)
                }
                """.trimIndent(),
            )

            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(result.messages.contains("mismatch"), result.messages)
            assertTrue(result.messages.contains("LocalDate"), result.messages)
        }
    }

    @Test
    fun `date does not expose clock based defaults`() {
        for (method in listOf("defaultNow", "updateDefaultNow")) {
            val result = compile(
                """
                class Event : EntSchema("events", clientName = "events") {
                    override fun id() = EntId.long()
                    val startsOn by date("starts_on").$method()
                }
                """.trimIndent(),
            )

            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(result.messages.contains("Unresolved reference '$method'"), result.messages)
        }
    }

    @Test
    fun `text and time builders are not retained as aliases`() {
        for (builder in listOf("text", "time")) {
            val result = compile(
                """
                class Post : EntSchema("posts", clientName = "posts") {
                    override fun id() = EntId.long()
                    val value by $builder("value")
                }
                """.trimIndent(),
            )

            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(result.messages.contains("Unresolved reference '$builder'"), result.messages)
        }
    }
}
