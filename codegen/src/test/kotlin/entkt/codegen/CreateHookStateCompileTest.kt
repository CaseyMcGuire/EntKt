@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateHookStateCompileTest {
    private class Widget : EntSchema("widgets", clientName = "widgets") {
        override fun id() = EntId.int()
        val name by string("name")
    }

    private class EmptyWidget : EntSchema("empty_widgets", clientName = "emptyWidgets") {
        override fun id() = EntId.int()
    }

    private class BeforeCreateHookState : EntSchema("hook_states", clientName = "hookStates") {
        override fun id() = EntId.int()
    }

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun createTypes(beforeSave: String, beforeCreate: String): List<String> = listOf(
        "CreateMutationOperation<Unit, WidgetDraft, WidgetCandidate, Widget, $beforeSave, $beforeCreate>",
        "CreateManyMutationOperation<Unit, WidgetDraft, WidgetCandidate, Widget, $beforeSave, $beforeCreate>",
        "CreateMutationHookStateConverter<WidgetDraft, Widget, $beforeSave, $beforeCreate>",
    )

    private fun compileTypes(types: List<String>): JvmCompilationResult = compile(
        listOf(
            SourceFile.kotlin(
                "CreateHookStateBounds.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)
                package com.example.app

                import entkt.runtime.entity.EntEntity
                import entkt.runtime.mutation.BeforeCreateHookState
                import entkt.runtime.mutation.BeforeSaveHookState
                import entkt.runtime.mutation.CreateMutationDraft
                import entkt.runtime.mutation.WriteCandidate
                import entkt.runtime.mutation.execution.*

                data class Widget(override val id: Int) : EntEntity.IntId
                data class Other(override val id: Int) : EntEntity.IntId
                class WidgetDraft : CreateMutationDraft<Widget>
                class WidgetCandidate : WriteCandidate<Widget>
                class SaveState : BeforeSaveHookState<Widget>
                class CreateState : BeforeCreateHookState<Widget>
                class OtherSaveState : BeforeSaveHookState<Other>
                class OtherCreateState : BeforeCreateHookState<Other>

                ${types.mapIndexed { index, type -> "fun accept$index(value: $type) {}" }.joinToString("\n")}
                """.trimIndent(),
            ),
        ),
    )

    @Test
    fun `create operations and converter accept hook states for their entity`() {
        val result = compileTypes(createTypes("SaveState", "CreateState"))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `each create type rejects unmarked and wrong-lifecycle hook states`() {
        val invalidStates = listOf(
            "String" to "CreateState",
            "SaveState" to "String",
            "CreateState" to "CreateState",
            "SaveState" to "SaveState",
        )

        for ((beforeSave, beforeCreate) in invalidStates) {
            createTypes(beforeSave, beforeCreate).forEach(::assertHookStateBoundFailure)
        }
    }

    @Test
    fun `each create type rejects hook states for another entity with the same ID type`() {
        createTypes("OtherSaveState", "CreateState").forEach(::assertHookStateBoundFailure)
        createTypes("SaveState", "OtherCreateState").forEach(::assertHookStateBoundFailure)
    }

    private fun assertHookStateBoundFailure(type: String) {
        val result = compileTypes(listOf(type))

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("BeforeSaveHookState") || result.messages.contains("BeforeCreateHookState"),
            "Expected a hook-state bound diagnostic for $type:\n${result.messages}",
        )
    }

    @Test
    fun `generated hook states implement entity markers including empty and colliding schemas`() {
        val schemas = listOf(Widget(), EmptyWidget(), BeforeCreateHookState())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent")
            .generate(schemas.map { SchemaInput(it) })
            .toCompileTestSources()

        val result = compile(
            generated + SourceFile.kotlin(
                "GeneratedHookStateBounds.kt",
                """
                package com.example.app

                import com.example.ent.Widget
                import com.example.ent.WidgetBeforeSaveState
                import com.example.ent.WidgetBeforeCreateState
                import com.example.ent.EmptyWidget
                import com.example.ent.EmptyWidgetBeforeCreateState
                import com.example.ent.BeforeCreateHookState
                import com.example.ent.BeforeCreateHookStateBeforeCreateState
                import entkt.runtime.mutation.BeforeSaveHookState as SaveState
                import entkt.runtime.mutation.BeforeCreateHookState as CreateState

                fun save(value: WidgetBeforeSaveState): SaveState<Widget> = value
                fun create(value: WidgetBeforeCreateState): CreateState<Widget> = value
                fun empty(value: EmptyWidgetBeforeCreateState): CreateState<EmptyWidget> = value
                fun collision(value: BeforeCreateHookStateBeforeCreateState): CreateState<BeforeCreateHookState> = value
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
