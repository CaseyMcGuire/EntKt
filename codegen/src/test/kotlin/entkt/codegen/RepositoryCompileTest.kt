@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise the runtime repository surface as application code, without internal opt-ins. */
class RepositoryCompileTest {
    private fun compile(body: String): JvmCompilationResult = KotlinCompilation().apply {
        sources = listOf(
            SourceFile.kotlin(
                "RepositoryTypes.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)
                package example

                import entkt.runtime.driver.NoopDriver
                import entkt.runtime.entity.EntEntity
                import entkt.runtime.mutation.CreateMutationDraft
                import entkt.runtime.mutation.UpdateMutationDraft
                import entkt.runtime.query.EntityQueryBuilder
                import entkt.runtime.repository.GeneratedIdRepository
                import entkt.runtime.repository.ExplicitIdRepository
                import entkt.runtime.rule.EntRuleClient

                data class Widget(override val id: Long) : EntEntity.LongId
                data class Other(override val id: Long) : EntEntity.LongId
                class CreateDraft(var name: String = "") : CreateMutationDraft<Widget>
                class UpdateDraft(var name: String = "") : UpdateMutationDraft<Widget>
                class OtherCreateDraft : CreateMutationDraft<Other>
                class OtherUpdateDraft : UpdateMutationDraft<Other>
                abstract class WidgetQuery : EntityQueryBuilder<Widget, WidgetQuery>(NoopDriver, null, "Widget")
                abstract class OtherQuery : EntityQueryBuilder<Other, OtherQuery>(NoopDriver, null, "Other")

                typealias GeneratedRepo = GeneratedIdRepository<Widget, Long, CreateDraft, UpdateDraft, WidgetQuery, EntRuleClient>
                typealias ExplicitRepo = ExplicitIdRepository<Widget, Long, CreateDraft, UpdateDraft, WidgetQuery, EntRuleClient>
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Application.kt",
                """
                package example

                import entkt.query.Predicate
                import entkt.runtime.mutation.PendingCreateMutation
                import entkt.runtime.mutation.PendingUpdateMutation
                import entkt.runtime.privacy.ViewerContext
                import entkt.runtime.repository.GeneratedIdRepository
                import entkt.runtime.result.MutationResult
                import entkt.runtime.result.ReadResult
                import entkt.runtime.rule.EntRuleClient

                $body
                """.trimIndent(),
            ),
        )
        inheritClassPath = true
        kotlincArguments = listOf("-Xskip-metadata-version-check")
        jvmTarget = "17"
        messageOutputStream = java.io.OutputStream.nullOutputStream()
    }.compile()

    @Test
    fun `inherited entry points retain concrete types and need no internal opt-in`() {
        val result = compile(
            """
            fun generated(repo: GeneratedRepo, viewer: ViewerContext, widget: Widget, predicate: Predicate<Widget>) {
                val query: WidgetQuery = repo.query { where(predicate) }
                val create: PendingCreateMutation<CreateDraft, Widget> = repo.create { name = "new" }
                val update: PendingUpdateMutation<UpdateDraft, Widget> = repo.update(1L) { name = "changed" }
                val found: ReadResult<Widget?> = repo.findById(viewer, 1L)
                val deleted: MutationResult<Boolean> = repo.deleteById(viewer, 1L)
                val removed: MutationResult<Unit> = repo.delete(viewer, widget)
                val created: MutationResult<List<Widget>> = repo.createMany(viewer, { name = "one" }, { name = "two" })
                val count: MutationResult<Int> = repo.deleteMany(viewer, predicate)
            }

            fun explicit(repo: ExplicitRepo, viewer: ViewerContext, widget: Widget) {
                val query: WidgetQuery = repo.query()
                val create: PendingCreateMutation<CreateDraft, Widget> = repo.create(1L) { name = "new" }
                val update: PendingUpdateMutation<UpdateDraft, Widget> = repo.update(1L) { name = "changed" }
                val found: ReadResult<Widget?> = repo.findById(viewer, 1L)
                val deleted: MutationResult<Boolean> = repo.deleteById(viewer, 1L)
                val removed: MutationResult<Unit> = repo.delete(viewer, widget)
                val count: MutationResult<Int> = repo.deleteMany(viewer)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `explicit ID repositories do not expose generated ID creation methods`() {
        assertRejected("ExplicitRepo", "repo.create { name = \"invalid\" }", "id")
        assertRejected("ExplicitRepo", "repo.createMany(viewer, { name = \"invalid\" })", "createMany")
    }

    @Test
    fun `ID parameters and creation signatures remain typed`() {
        assertRejected("GeneratedRepo", "repo.create(1L) { name = \"invalid\" }", "argument")
        for (repo in listOf("GeneratedRepo", "ExplicitRepo")) {
            assertRejected(repo, "repo.update(\"wrong ID\") { name = \"invalid\" }", "Long")
            assertRejected(repo, "repo.deleteById(viewer, \"wrong ID\")", "Long")
        }
    }

    @Test
    fun `application code cannot access repository execution dependencies`() {
        for (repo in listOf("GeneratedRepo", "ExplicitRepo")) {
            for (property in listOf("ruleClient", "mutationExecutor", "createOperations", "self")) {
                assertRejected(repo, "repo.$property", property)
            }
            assertRejected(repo, "repo.withTransaction { Unit }", "withTransaction")
        }
    }

    @Test
    fun `repository bounds reject mismatched entity ID draft and query types`() {
        val invalidTypes = listOf(
            "Widget, String, CreateDraft, UpdateDraft, WidgetQuery, EntRuleClient",
            "Widget, Long, OtherCreateDraft, UpdateDraft, WidgetQuery, EntRuleClient",
            "Widget, Long, CreateDraft, OtherUpdateDraft, WidgetQuery, EntRuleClient",
            "Widget, Long, CreateDraft, UpdateDraft, OtherQuery, EntRuleClient",
        )
        for (types in invalidTypes) {
            val result = compile("fun invalid(repo: GeneratedIdRepository<$types>) {}")
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(result.messages.contains("bound", ignoreCase = true), result.messages)
        }
    }

    private fun assertRejected(repo: String, expression: String, diagnostic: String) {
        val result = compile("fun invalid(repo: $repo, viewer: ViewerContext) { $expression }")

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains(diagnostic, ignoreCase = true), result.messages)
    }
}
