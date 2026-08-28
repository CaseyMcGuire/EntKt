@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Compile-time contract for the one read-only client shared by all rule contexts. */
class ReadOnlyEntClientCompileTest {

    private fun generatedSources(): List<SourceFile> {
        val car = Car()
        val user = User()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            car::class to car,
            user::class to user,
        )
        car.finalize(registry)
        user.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(car), SchemaInput(user)))
            .toCompileTestSources()
    }

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    @Test
    fun `one read-only helper accepts privacy and validation contexts`() {
        val result = compile(
            generatedSources() + SourceFile.kotlin(
                "SharedReadClientSnippet.kt",
                """
                package com.example.app

                import com.example.ent.CarCreateValidationRule
                import com.example.ent.CarLoadPrivacyRule
                import com.example.ent.ReadOnlyEntClient
                import entkt.runtime.privacy.PrivacyDecision
                import entkt.runtime.privacy.ViewerContext
                import entkt.runtime.validation.ValidationDecision

                fun anyCarExists(client: ReadOnlyEntClient, viewerContext: ViewerContext): Boolean =
                    client.cars.query { }.firstOrNull(viewerContext).getOrThrow() != null

                val validation = CarCreateValidationRule { ctx, _ ->
                    val client: ReadOnlyEntClient = ctx.client
                    anyCarExists(client, ctx.readViewerContext)
                    ValidationDecision.Valid
                }
                val privacy = CarLoadPrivacyRule { ctx, _ ->
                    val client: ReadOnlyEntClient = ctx.client
                    anyCarExists(client, ctx.viewerContext)
                    PrivacyDecision.Continue
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected ReadOnlyEntClient to be shared by both contexts, got:\n${result.messages}",
        )
    }

    @Test
    fun `removed read-client names do not resolve`() {
        val result = compile(
            generatedSources() + SourceFile.kotlin(
                "RemovedReadClientsSnippet.kt",
                """
                package com.example.app

                import com.example.ent.EntPrivacyReadClient
                import com.example.ent.EntValidationReadClient
                import com.example.ent.EntReadClient

                val privacy: EntPrivacyReadClient? = null
                val validation: EntValidationReadClient? = null
                val shared: EntReadClient? = null
                """.trimIndent(),
            ),
        )

        assertNotEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertTrue(
            result.messages.contains("EntPrivacyReadClient") &&
                result.messages.contains("EntValidationReadClient") &&
                result.messages.contains("EntReadClient") &&
                result.messages.contains("Unresolved reference", ignoreCase = true),
            "Expected both removed posture types to be unresolved, got:\n${result.messages}",
        )
    }
}
