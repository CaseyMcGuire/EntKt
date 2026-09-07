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
                import entkt.runtime.privacy.PrivacyRuleContext
                import entkt.runtime.privacy.ViewerContext
                import entkt.runtime.rule.EntRuleClient
                import entkt.runtime.validation.ValidationDecision
                import entkt.runtime.validation.ValidationRuleContext

                fun <Client : EntRuleClient> clientFrom(context: PrivacyRuleContext<Client>): Client =
                    context.client

                fun <Client : EntRuleClient> clientFrom(context: ValidationRuleContext<Client>): Client =
                    context.client

                fun anyCarExists(client: ReadOnlyEntClient, viewerContext: ViewerContext): Boolean =
                    client.cars.query { }.firstOrNull(viewerContext).getOrThrow() != null

                val validation = CarCreateValidationRule { ctx, _ ->
                    val client: ReadOnlyEntClient = clientFrom(ctx)
                    anyCarExists(client, ctx.readViewerContext)
                    ValidationDecision.Valid
                }
                val privacy = CarLoadPrivacyRule { ctx, _ ->
                    val client: ReadOnlyEntClient = clientFrom(ctx)
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
    fun `rule client bounds reject unrelated types and the writable client`() {
        val invalidTypes = mapOf(
            "Repository" to "GeneratedIdRepository<Car, Int, CarCreateDraft, CarUpdateDraft, CarQuery, Any>",
            "ExplicitRepository" to "ExplicitIdRepository<Car, Int, CarCreateDraft, CarUpdateDraft, CarQuery, Any>",
            "Operation" to "MutationOperation<Any, Unit, Unit>",
            "LoadEvaluator" to "LoadPrivacyEvaluator<Any, Car>",
            "PrivacyEvaluator" to "MutationPrivacyEvaluator<Any, Unit>",
            "ValidationEvaluator" to "MutationValidationEvaluator<Any, Unit>",
            "PrivacyContext" to "PrivacyRuleContext<Any>",
            "ValidationContext" to "ValidationRuleContext<Any>",
            "PrivacyRule" to "BatchPrivacyRule<Any, Car>",
            "ValidationRule" to "BatchValidationRule<Any, Car>",
            "WritableClient" to "PrivacyRuleContext<EntClient>",
        )
        val probes = invalidTypes.map { (name, type) ->
            SourceFile.kotlin(
                "$name.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)
                package com.example.app

                import com.example.ent.*
                import entkt.runtime.mutation.execution.MutationOperation
                import entkt.runtime.privacy.*
                import entkt.runtime.repository.*
                import entkt.runtime.validation.*

                fun accept$name(value: $type) {}
                """.trimIndent(),
            )
        }
        val result = compile(generatedSources() + probes)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in invalidTypes.keys) {
            assertTrue(
                result.messages.lineSequence().any { it.contains("$name.kt:") && it.contains("EntRuleClient") },
                "Expected $name to reject the unmarked client, got:\n${result.messages}",
            )
        }
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
