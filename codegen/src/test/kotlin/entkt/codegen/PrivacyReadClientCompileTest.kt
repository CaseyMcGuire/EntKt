@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Compile-time proof of the read-only privacy client's contract:
 * **privacy rule code cannot write, open transactions, re-scope, or
 * mutate client configuration**. Privacy contexts expose
 * `EntReadClient`, so every such member is an unresolved reference.
 *
 * Also pins the adapter's opt-in gate: kctfork compiles the snippets
 * into the same module as the generated output, so `internal` is
 * satisfied either way and `@EntktInternal` is the boundary actually
 * being tested — calling `asReadClientForInternalUse` without opt-in
 * must fail on the opt-in diagnostic (not an unresolved reference),
 * and the same call under `@OptIn(EntktInternal::class)` must compile.
 *
 * The validation-context twin lives in [ValidationReadClientCompileTest];
 * both contexts expose the same `EntReadClient`, so the exhaustive
 * member probes live here and the validation test keeps its original
 * write-surface coverage.
 */
class PrivacyReadClientCompileTest {

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

    private fun ruleSnippet(body: String): SourceFile = SourceFile.kotlin(
        "PrivacyRuleSnippet.kt",
        """
        package com.example.app

        import com.example.ent.CarLoadPrivacyRule
        import com.example.ent.EntReadClient
        import entkt.runtime.privacy.PrivacyDecision
        import java.util.UUID

        val rule = CarLoadPrivacyRule { ctx ->
            $body
            PrivacyDecision.Continue
        }
        """.trimIndent(),
    )

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun assertUnresolved(member: String, body: String) {
        val result = compile(generatedSources() + ruleSnippet(body))
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected privacy rule use of '$member' to fail compilation but it succeeded",
        )
        assertTrue(
            result.messages.contains("nresolved reference") && result.messages.contains(member),
            "Expected an unresolved-reference error on '$member', got:\n${result.messages}",
        )
    }

    @Test
    fun `privacy rule can read - query terminals, byId family, index helpers, explain`() {
        // The positive twin: the read surface the RFC promises rules. If
        // this stops compiling, the negatives below prove nothing.
        val result = compile(
            generatedSources() + ruleSnippet(
                """
                val typed: EntReadClient = ctx.client
                ctx.client.cars.query { }.rawCount()
                ctx.client.cars.query { }.rawExists()
                ctx.client.users.byIdOrNull(UUID.randomUUID())
                ctx.client.users.visibleByIdOrNull(UUID.randomUUID())
                ctx.client.users.indexes.email("a@b.c").orNull()
                ctx.client.cars.explainByIdOrNull(1)
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the read-only privacy rule snippet to compile, got:\n${result.messages}",
        )
    }

    @Test
    fun `privacy rule cannot create`() {
        assertUnresolved("create", "ctx.client.cars.create { }")
    }

    @Test
    fun `privacy rule cannot update`() {
        assertUnresolved("update", "ctx.client.cars.update(1) { }")
    }

    @Test
    fun `privacy rule cannot delete`() {
        assertUnresolved("deleteByIdOrError", "ctx.client.cars.deleteByIdOrError(1)")
    }

    @Test
    fun `privacy rule cannot bulk delete`() {
        assertUnresolved("deleteMany", "ctx.client.cars.deleteMany()")
    }

    @Test
    fun `privacy rule cannot open a transaction`() {
        assertUnresolved("withTransaction", "ctx.client.withTransaction { }")
    }

    @Test
    fun `privacy rule cannot open a structured-result transaction`() {
        assertUnresolved("withTransactionOrError", "ctx.client.withTransactionOrError { }")
    }

    @Test
    fun `privacy rule cannot re-scope to another viewer`() {
        assertUnresolved("withPrivacyContext", "ctx.client.withPrivacyContext(ctx.privacy) { }")
    }

    @Test
    fun `privacy rule cannot bypass privacy`() {
        assertUnresolved("bypassPrivacy_DANGEROUS", "ctx.client.bypassPrivacy_DANGEROUS(\"x\") { }")
    }

    @Test
    fun `privacy rule cannot mutate client configuration`() {
        // Same-module `internal var`s on the full client today; absent
        // members on the read client, so each probe fails the same
        // unresolved-reference way.
        assertUnresolved("transactionRequirement", "ctx.client.transactionRequirement = null")
        assertUnresolved("privacyContextProvider", "ctx.client.privacyContextProvider = { ctx.privacy }")
        assertUnresolved("defaultUpdateConsistency", "ctx.client.defaultUpdateConsistency = null")
        assertUnresolved("defaultRelationshipLocking", "ctx.client.defaultRelationshipLocking = null")
    }

    // ---- The adapter's opt-in gate ----

    private fun mintSnippet(optIn: Boolean): SourceFile {
        val header = if (optIn) "@file:OptIn(entkt.query.EntktInternal::class)\n\n" else ""
        return SourceFile.kotlin(
            "MintSnippet.kt",
            header +
                """
                package com.example.app

                import com.example.ent.EntClient
                import entkt.runtime.privacy.PrivacyContext
                import entkt.runtime.privacy.Viewer

                fun mint(client: EntClient) {
                    client.asReadClientForInternalUse(PrivacyContext(Viewer.Anonymous))
                }
                """.trimIndent(),
        )
    }

    @Test
    fun `minting a read client without opt-in fails on the opt-in diagnostic`() {
        val result = compile(generatedSources() + mintSnippet(optIn = false))
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected asReadClientForInternalUse without opt-in to fail compilation but it succeeded",
        )
        // The failure must be the opt-in requirement, not visibility or
        // resolution: the snippet compiles same-module, so `internal` is
        // satisfied and the member resolves — the marker is the gate.
        assertTrue(
            result.messages.contains("EntktInternal"),
            "Expected the opt-in diagnostic to name EntktInternal, got:\n${result.messages}",
        )
        assertFalse(
            result.messages.contains("nresolved reference"),
            "Expected an opt-in failure, not an unresolved reference:\n${result.messages}",
        )
    }

    @Test
    fun `minting a read client with explicit opt-in compiles`() {
        val result = compile(generatedSources() + mintSnippet(optIn = true))
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected asReadClientForInternalUse under @OptIn(EntktInternal::class) to compile, got:\n${result.messages}",
        )
    }
}
