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

class WriteCandidateCompileTest {
    private class Widget : EntSchema("widgets", clientName = "widgets") {
        override fun id() = EntId.int()
        val name by string("name")
    }

    private class WriteCandidate : EntSchema("write_candidates", clientName = "writeCandidates") {
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

    private fun mutationTypes(candidate: String): List<String> = listOf(
        "CreateMutationConverter<WidgetDraft, $candidate, Widget>",
        "CreateMutationOperation<Unit, WidgetDraft, $candidate, Widget, Unit, Unit>",
        "CreateManyMutationOperation<Unit, WidgetDraft, $candidate, Widget, Unit, Unit>",
        "DeleteMutationConverter<Widget, $candidate>",
        "DeleteMutationOperation<Unit, Widget, $candidate>",
        "DeleteManyMutationOperation<Unit, Widget, $candidate>",
        "DeleteRuleCandidate<Widget, $candidate>",
    )

    private fun compileTypes(types: List<String>): JvmCompilationResult = compile(
        listOf(
            SourceFile.kotlin(
                "CandidateBounds.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)
                package com.example.app

                import entkt.runtime.entity.EntEntity
                import entkt.runtime.mutation.CreateMutationDraft
                import entkt.runtime.mutation.PreparedCreate
                import entkt.runtime.mutation.WriteCandidate
                import entkt.runtime.mutation.execution.*

                data class Widget(override val id: Int) : EntEntity.IntId
                data class Other(override val id: Int) : EntEntity.IntId
                class WidgetDraft : CreateMutationDraft<Widget>
                class WidgetCandidate : WriteCandidate<Widget>
                class OtherCandidate : WriteCandidate<Other>

                ${types.mapIndexed { index, type -> "fun accept$index(value: $type) {}" }.joinToString("\n")}
                """.trimIndent(),
            ),
        ),
    )

    @Test
    fun `mutation types accept candidates for their entity without bounding rule clients`() {
        val result = compileTypes(mutationTypes("WidgetCandidate") + "PreparedCreate<WidgetCandidate>")

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `each mutation type rejects candidates for a different entity with the same ID type`() {
        for (type in mutationTypes("OtherCandidate")) {
            assertCandidateBoundFailure(type)
        }
    }

    @Test
    fun `each mutation type rejects candidates that do not implement the marker`() {
        for (type in mutationTypes("String") + "PreparedCreate<String>") {
            assertCandidateBoundFailure(type)
        }
    }

    private fun assertCandidateBoundFailure(type: String) {
        val result = compileTypes(listOf(type))

        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "Expected $type to reject the candidate:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("bound", ignoreCase = true) && result.messages.contains("WriteCandidate"),
            "Expected a WriteCandidate bound diagnostic for $type:\n${result.messages}",
        )
    }

    @Test
    fun `generated candidates implement the marker including id-only and colliding entity names`() {
        val schemas = listOf(Widget(), WriteCandidate())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent")
            .generate(schemas.map { SchemaInput(it) })
            .toCompileTestSources()
        val result = compile(
            generated + SourceFile.kotlin(
                "GeneratedCandidateBounds.kt",
                """
                package com.example.app

                import com.example.ent.Widget
                import com.example.ent.WidgetWriteCandidate
                import com.example.ent.WriteCandidate
                import com.example.ent.WriteCandidateWriteCandidate
                import entkt.runtime.mutation.WriteCandidate as Candidate

                fun widget(value: WidgetWriteCandidate): Candidate<Widget> = value
                fun idOnly(value: WriteCandidateWriteCandidate): Candidate<WriteCandidate> = value
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
