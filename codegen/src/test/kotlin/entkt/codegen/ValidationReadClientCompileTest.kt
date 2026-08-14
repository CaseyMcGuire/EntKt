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
 * Compile-time proof of the read-only validation client's core contract:
 * **validator code cannot write**. Validation contexts expose
 * `EntValidationReadClient` (implementing the shared `EntReadClient`
 * interface), whose repos simply have no write members — so
 * `ctx.client.<repo>.create/update/delete*` and
 * `ctx.client.withTransaction` are unresolved references, not runtime
 * errors.
 *
 * Each negative compiles the full generated output for the shared
 * Car/User fixture schemas plus a validator snippet exercising one
 * write entry point, and asserts a compilation error naming that
 * member. The positive twin compiles a validator that uses the read
 * surface (query DSL + terminal, byId family, index helpers, explain)
 * — proving the negatives fail on the missing write surface, not a
 * broken harness.
 *
 * Edge mutators need no separate probe: they exist only inside the
 * create/update builders (`update(id) { user.set(...) }`), so with
 * `update`/`create` unresolved the edge-mutation surface is
 * transitively unreachable from a validator.
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
            .generate(listOf(SchemaInput("Car", car), SchemaInput("User", user)))
            .toCompileTestSources()
    }

    private fun validatorSnippet(body: String): SourceFile = SourceFile.kotlin(
        "ValidatorSnippet.kt",
        """
        package com.example.app

        import com.example.ent.CarCreateValidationRule
        import com.example.ent.EntReadClient
        import com.example.ent.EntValidationReadClient
        import entkt.runtime.result.visibleOrNull
        import entkt.runtime.validation.ValidationDecision
        import java.util.UUID

        val rule = CarCreateValidationRule { ctx ->
            $body
            ValidationDecision.Valid
        }
        """.trimIndent(),
    )

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            // kctfork bundles its own kotlinc, which may lag the project's
            // compiler; the contract under test (member resolution on the
            // generated types) is independent of the metadata version.
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun assertUnresolved(member: String, body: String) {
        val result = compile(generatedSources() + validatorSnippet(body))
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected validator write via '$member' to fail compilation but it succeeded",
        )
        assertTrue(
            // K1 spells it `unresolved reference: create`; K2 spells it
            // `Unresolved reference 'create'` — match the shared core.
            result.messages.contains("nresolved reference") && result.messages.contains(member),
            "Expected an unresolved-reference error on '$member', got:\n${result.messages}",
        )
    }

    @Test
    fun `validator can read - query terminals, byId family, index helpers, explain`() {
        // The positive twin: every read-surface family the RFC promises
        // validators, in one snippet. If this stops compiling, the
        // negatives below prove nothing.
        val result = compile(
            generatedSources() + validatorSnippet(
                """
                val concrete: EntValidationReadClient = ctx.client
                val typed: EntReadClient = ctx.client
                ctx.client.cars.query { }.rawCount().getOrThrow()
                ctx.client.cars.query { }.rawExists()
                ctx.client.cars.query { }.all()
                ctx.client.cars.query { }.firstOrNull()
                ctx.client.users.findById(UUID.randomUUID()).getOrThrow()
                ctx.client.users.findById(UUID.randomUUID()).visibleOrNull()
                ctx.client.users.indexes.email("a@b.c").find()
                ctx.client.users.indexes.name("n").email("a@b.c").find().getOrThrow()
                ctx.client.cars.explainFindById(1)
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the read-only validator snippet to compile, got:\n${result.messages}",
        )
    }

    @Test
    fun `validator cannot create`() {
        assertUnresolved("create", "ctx.client.cars.create { }")
    }

    @Test
    fun `validator cannot update`() {
        assertUnresolved("update", "ctx.client.cars.update(1) { }")
    }

    @Test
    fun `validator cannot delete`() {
        assertUnresolved("deleteById", "ctx.client.cars.deleteById(1)")
    }

    @Test
    fun `validator cannot bulk delete`() {
        assertUnresolved("deleteMany", "ctx.client.cars.deleteMany()")
    }

    @Test
    fun `validator cannot bulk create`() {
        assertUnresolved("createMany", "ctx.client.cars.createMany({ })")
    }

    @Test
    fun `validator cannot open a transaction`() {
        assertUnresolved("withTransaction", "ctx.client.withTransaction { }")
    }
}
