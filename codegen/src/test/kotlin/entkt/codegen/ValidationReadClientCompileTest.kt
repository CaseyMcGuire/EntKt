@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.fixtures.Car
import entkt.codegen.fixtures.User
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compile-time proof that validation contexts expose `ReadOnlyEntClient`
 * and support query terminals, ID lookups, and index helpers using the
 * validation context's read viewer.
 *
 * Forbidden write members on the shared client are covered by
 * [PrivacyReadClientCompileTest]. [ReadOnlyEntClientCompileTest] verifies
 * that privacy and validation contexts share the same client type.
 */
class ValidationReadClientCompileTest {

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

    private fun validatorSnippet(body: String): SourceFile = SourceFile.kotlin(
        "ValidatorSnippet.kt",
        """
        package com.example.app

        import com.example.ent.CarCreateValidationRule
        import com.example.ent.ReadOnlyEntClient
        import entkt.runtime.result.visibleOrNull
        import entkt.runtime.validation.ValidationDecision
        import java.util.UUID

        val rule = CarCreateValidationRule { ctx, _ ->
            $body
            ValidationDecision.Valid
        }
        """.trimIndent(),
    )

    @Test
    fun `validator can read - query terminals, byId family, and index helpers`() {
        val result = compileSources(
            generatedSources() + validatorSnippet(
                """
                val concrete: ReadOnlyEntClient = ctx.client
                ctx.client.cars.query { }.all(ctx.readViewerContext)
                ctx.client.cars.query { }.firstOrNull(ctx.readViewerContext)
                ctx.client.users.findById(ctx.readViewerContext, UUID.randomUUID()).getOrThrow()
                ctx.client.users.findById(ctx.readViewerContext, UUID.randomUUID()).visibleOrNull()
                ctx.client.users.indexes.email("a@b.c").find(ctx.readViewerContext)
                ctx.client.users.indexes.name("n").email("a@b.c").find(ctx.readViewerContext).getOrThrow()
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the read-only validator snippet to compile, got:\n${result.messages}",
        )
    }
}
