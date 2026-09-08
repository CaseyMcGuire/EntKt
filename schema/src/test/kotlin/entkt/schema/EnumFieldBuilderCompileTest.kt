@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.schema

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumFieldBuilderCompileTest {
    private fun compile(snippet: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "EnumFields.kt",
                    """
                    import entkt.schema.*

                    enum class Status { OPEN, CLOSED }
                    enum class Priority { LOW, HIGH }

                    $snippet
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            jvmTarget = "17"
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun assertWrongEnumDefault(result: JvmCompilationResult) {
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("mismatch", ignoreCase = true), result.messages)
        assertTrue(result.messages.contains("Status"), result.messages)
        assertTrue(result.messages.contains("Priority"), result.messages)
    }

    @Test
    fun `schema and mixin fields retain their enum type through modifiers and delegation`() {
        val result = compile(
            """
            class StatusFields(scope: EntMixin.Scope) : EntMixin(scope) {
                val initialStatus: EnumFieldBuilder<Status> by enum<Status>("initial_status")
                    .immutable().default(Status.OPEN)
            }

            class Task : EntSchema("tasks", clientName = "tasks") {
                override fun id() = EntId.long()
                val status: EnumFieldBuilder<Status> by enum<Status>("status")
                    .nullable().unique().sensitive().comment("Current status").default(Status.OPEN)
                val statuses = include(::StatusFields)
                val byStatus = index("idx_status", status)
            }

            fun acceptTypedHandles(task: Task) {
                val status: FieldHandle<Status> = task.status
                val initialStatus: FieldHandle<Status> = task.statuses.initialStatus
                task.status.default(Status.CLOSED)
                task.statuses.initialStatus.default(Status.CLOSED)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `schema defaults reject another enum before and after modifiers`() {
        for (modifiers in listOf("", ".nullable().immutable().sensitive().comment(\"Status\")")) {
            val result = compile(
                """
                class Task : EntSchema("tasks", clientName = "tasks") {
                    override fun id() = EntId.long()
                    val status by enum<Status>("status")$modifiers.default(Priority.HIGH)
                }
                """.trimIndent(),
            )

            assertWrongEnumDefault(result)
        }
    }

    @Test
    fun `mixin defaults reject another enum`() {
        val result = compile(
            """
            class StatusFields(scope: EntMixin.Scope) : EntMixin(scope) {
                val status by enum<Status>("status").nullable().default(Priority.HIGH)
            }
            """.trimIndent(),
        )

        assertWrongEnumDefault(result)
    }

    @Test
    fun `inferred delegated builder rejects another enum`() {
        val result = compile(
            """
            class Task : EntSchema("tasks", clientName = "tasks") {
                override fun id() = EntId.long()
                val status by enum<Status>("status")
            }

            fun configure(task: Task) {
                task.status.default(Priority.HIGH)
            }
            """.trimIndent(),
        )

        assertWrongEnumDefault(result)
    }
}
