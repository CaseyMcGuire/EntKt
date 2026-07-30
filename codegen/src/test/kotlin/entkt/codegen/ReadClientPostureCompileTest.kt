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

/**
 * Compile-time proof of the posture split's central promise: the two
 * concrete read clients are **distinct types**, so a helper that names
 * one posture rejects the other at compile time, while a helper that
 * accepts the shared `EntReadClient` interface works with both.
 *
 * The rejections fail as *type mismatches*, not unresolved references
 * — both postures exist and resolve; they simply do not unify. That is
 * exactly what type aliases could not provide, and why the RFC requires
 * real classes. Each rejection snippet contains exactly one helper
 * call — the cross-posture one — so the mismatch diagnostic can only
 * come from that call; the matching-posture acceptance is proved
 * separately by the concrete `ctx.client` pins in
 * [ValidationReadClientCompileTest] / [PrivacyReadClientCompileTest]
 * and by the positive test below.
 *
 * The write-surface and opt-in-gate coverage lives in
 * [ValidationReadClientCompileTest] and [PrivacyReadClientCompileTest].
 */
class ReadClientPostureCompileTest {

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
            .generate(listOf(SchemaInput("Car", car), SchemaInput("User", user)))
            .map { SourceFile.kotlin("${it.name}.kt", it.toString()) }
    }

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun assertPostureMismatch(snippet: SourceFile, supplied: String, expected: String) {
        val result = compile(generatedSources() + snippet)
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected passing $supplied to a helper requiring $expected to fail compilation but it succeeded",
        )
        // K1 spells it `type mismatch: inferred type is X but Y was
        // expected`; K2 spells it `argument type mismatch: actual type is
        // 'X', but 'Y' was expected` — match the shared core plus both
        // type names.
        assertTrue(
            result.messages.contains("mismatch") &&
                result.messages.contains(supplied) &&
                result.messages.contains(expected),
            "Expected a type mismatch between $supplied and $expected, got:\n${result.messages}",
        )
    }

    @Test
    fun `a helper requiring the privacy posture rejects a validation context's client`() {
        assertPostureMismatch(
            SourceFile.kotlin(
                "CrossPostureSnippet.kt",
                """
                package com.example.app

                import com.example.ent.CarCreateValidationRule
                import com.example.ent.EntPrivacyReadClient
                import entkt.runtime.validation.ValidationDecision

                fun requiresPrivacyReader(client: EntPrivacyReadClient) = Unit

                val rule = CarCreateValidationRule { ctx ->
                    requiresPrivacyReader(ctx.client)
                    ValidationDecision.Valid
                }
                """.trimIndent(),
            ),
            supplied = "EntValidationReadClient",
            expected = "EntPrivacyReadClient",
        )
    }

    @Test
    fun `a helper requiring the validation posture rejects a privacy context's client`() {
        assertPostureMismatch(
            SourceFile.kotlin(
                "CrossPostureSnippet.kt",
                """
                package com.example.app

                import com.example.ent.CarLoadPrivacyRule
                import com.example.ent.EntValidationReadClient
                import entkt.runtime.privacy.PrivacyDecision

                fun requiresValidationReader(client: EntValidationReadClient) = Unit

                val rule = CarLoadPrivacyRule { ctx ->
                    requiresValidationReader(ctx.client)
                    PrivacyDecision.Continue
                }
                """.trimIndent(),
            ),
            supplied = "EntPrivacyReadClient",
            expected = "EntValidationReadClient",
        )
    }

    @Test
    fun `a posture-agnostic helper accepts either context's client`() {
        // The interface erases the posture on purpose — for code written
        // against the LOAD-checked surface either reader satisfies. The
        // helper body exercises a repo accessor through the interface so
        // the test also pins that the shared repos live on it.
        val result = compile(
            generatedSources() + SourceFile.kotlin(
                "AgnosticHelperSnippet.kt",
                """
                package com.example.app

                import com.example.ent.CarCreateValidationRule
                import com.example.ent.CarLoadPrivacyRule
                import com.example.ent.EntReadClient
                import entkt.runtime.privacy.PrivacyDecision
                import entkt.runtime.validation.ValidationDecision

                fun anyCarExists(client: EntReadClient): Boolean =
                    client.cars.query { }.visibleExists()

                val validation = CarCreateValidationRule { ctx ->
                    anyCarExists(ctx.client)
                    ValidationDecision.Valid
                }
                val privacy = CarLoadPrivacyRule { ctx ->
                    anyCarExists(ctx.client)
                    PrivacyDecision.Continue
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected an EntReadClient helper to accept both postures, got:\n${result.messages}",
        )
    }
}
