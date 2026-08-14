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
 * `EntPrivacyReadClient` (implementing the shared `EntReadClient`
 * interface), so every such member is an unresolved reference.
 *
 * Also pins the adapters' opt-in gate: kctfork compiles the snippets
 * into the same module as the generated output, so `internal` is
 * satisfied either way and `@EntktInternal` is the boundary actually
 * being tested — calling `asValidationReadClientForInternalUse` or
 * `asPrivacyReadClientForInternalUse` without opt-in must fail on the
 * opt-in diagnostic (not an unresolved reference), and the same calls
 * under `@OptIn(EntktInternal::class)` must compile.
 *
 * The validation-context twin lives in [ValidationReadClientCompileTest];
 * both wrappers delegate to the same shared implementation, so the
 * exhaustive member probes live here and the validation test keeps its
 * original write-surface coverage. Cross-posture rejection lives in
 * [ReadClientPostureCompileTest].
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
            .toCompileTestSources()
    }

    private fun ruleSnippet(body: String, ruleType: String = "CarLoadPrivacyRule"): SourceFile = SourceFile.kotlin(
        "PrivacyRuleSnippet.kt",
        """
        package com.example.app

        import com.example.ent.$ruleType
        import com.example.ent.EntPrivacyReadClient
        import com.example.ent.EntReadClient
        import entkt.runtime.privacy.PrivacyDecision
        import entkt.runtime.result.visibleOrNull
        import java.util.UUID

        val rule = $ruleType { ctx ->
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

    private fun assertUnresolved(member: String, body: String, ruleType: String = "CarLoadPrivacyRule") {
        val result = compile(generatedSources() + ruleSnippet(body, ruleType))
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
    fun `privacy rule can read - LOAD-checked terminals, byId family, index helpers, explain`() {
        // The positive twin: the viewer-scoped read surface the RFC
        // promises rules. Raw terminals (rawCount / rawExists / raw
        // aggregates) still compile here — the query type is shared with
        // validation contexts, where they are legitimate — but they are
        // gated at runtime via EntReadRuntime.checkPrivacyBypassingRead,
        // pinned by PrivacyReadClientIntegrationTest. If this test stops
        // compiling, the negatives below prove nothing.
        val result = compile(
            generatedSources() + ruleSnippet(
                """
                val concrete: EntPrivacyReadClient = ctx.client
                val typed: EntReadClient = ctx.client
                ctx.client.cars.query { }.firstOrNull()
                ctx.client.cars.query { }.all().getOrThrow()
                ctx.client.cars.query { }.rawExists()
                ctx.client.users.findById(UUID.randomUUID()).getOrThrow()
                ctx.client.users.findById(UUID.randomUUID()).visibleOrNull()
                ctx.client.users.indexes.email("a@b.c").find()
                ctx.client.cars.explainFindById(1)
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
    fun `all four privacy contexts expose the privacy-posture read client`() {
        // Concrete type pins for every operation context — a regression
        // returning EntClient, the shared interface, or the validation
        // posture to any of them breaks the corresponding assignment. The
        // load rule additionally pins assignability to the shared
        // EntReadClient interface.
        val result = compile(
            generatedSources() + SourceFile.kotlin(
                "AllContextsSnippet.kt",
                """
                package com.example.app

                import com.example.ent.CarLoadPrivacyRule
                import com.example.ent.CarCreatePrivacyRule
                import com.example.ent.CarUpdatePrivacyRule
                import com.example.ent.CarDeletePrivacyRule
                import com.example.ent.EntPrivacyReadClient
                import com.example.ent.EntReadClient
                import entkt.runtime.privacy.PrivacyDecision

                val load = CarLoadPrivacyRule { ctx ->
                    val t: EntPrivacyReadClient = ctx.client
                    val iface: EntReadClient = ctx.client
                    PrivacyDecision.Continue
                }
                val create = CarCreatePrivacyRule { ctx ->
                    val t: EntPrivacyReadClient = ctx.client
                    PrivacyDecision.Continue
                }
                val update = CarUpdatePrivacyRule { ctx ->
                    val t: EntPrivacyReadClient = ctx.client
                    PrivacyDecision.Continue
                }
                val delete = CarDeletePrivacyRule { ctx ->
                    val t: EntPrivacyReadClient = ctx.client
                    PrivacyDecision.Continue
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected all four privacy contexts to expose EntPrivacyReadClient, got:\n${result.messages}",
        )
    }

    @Test
    fun `create rule cannot create`() {
        assertUnresolved("create", "ctx.client.cars.create { }", ruleType = "CarCreatePrivacyRule")
    }

    @Test
    fun `update rule cannot update`() {
        assertUnresolved("update", "ctx.client.cars.update(1) { }", ruleType = "CarUpdatePrivacyRule")
    }

    @Test
    fun `delete rule cannot delete`() {
        assertUnresolved("deleteById", "ctx.client.cars.deleteById(1)", ruleType = "CarDeletePrivacyRule")
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
        assertUnresolved("deleteById", "ctx.client.cars.deleteById(1)")
    }

    @Test
    fun `privacy rule cannot bulk delete`() {
        assertUnresolved("deleteMany", "ctx.client.cars.deleteMany()")
    }

    @Test
    fun `privacy rule cannot bulk create`() {
        assertUnresolved("createMany", "ctx.client.cars.createMany({ })")
    }

    @Test
    fun `privacy rule cannot open a transaction`() {
        assertUnresolved("withTransaction", "ctx.client.withTransaction { }")
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

    // ---- The adapters' opt-in gate ----

    private fun mintSnippet(optIn: Boolean, call: String): SourceFile {
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
                    client.$call
                }
                """.trimIndent(),
        )
    }

    private fun assertOptInGated(adapter: String, call: String) {
        val result = compile(generatedSources() + mintSnippet(optIn = false, call = call))
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected $adapter without opt-in to fail compilation but it succeeded",
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
        val optedIn = compile(generatedSources() + mintSnippet(optIn = true, call = call))
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            optedIn.exitCode,
            "Expected $adapter under @OptIn(EntktInternal::class) to compile, got:\n${optedIn.messages}",
        )
    }

    @Test
    fun `minting a validation read client is gated on the opt-in marker`() {
        assertOptInGated(
            "asValidationReadClientForInternalUse",
            "asValidationReadClientForInternalUse()",
        )
    }

    @Test
    fun `minting a privacy read client is gated on the opt-in marker`() {
        assertOptInGated(
            "asPrivacyReadClientForInternalUse",
            "asPrivacyReadClientForInternalUse(PrivacyContext(Viewer.Anonymous))",
        )
    }
}
