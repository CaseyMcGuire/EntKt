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
 * Compile-time proof of the read-only privacy client's contract:
 * **privacy rule code cannot write, open transactions, or mutate client
 * configuration**, and the client exposes no ambient re-scoping or
 * client-level bypass helpers. Each read terminal still accepts an explicit
 * `ViewerContext`, including a deliberately selected bypass context. Privacy
 * contexts expose `ReadOnlyEntClient`, so every such member is an unresolved reference.
 *
 * Also pins removal of the former rule-client factories: they remain
 * unresolved even from same-module code carrying `@OptIn(EntktInternal)`.
 *
 * The validation-context twin lives in [ValidationReadClientCompileTest];
 * both contexts expose the same client type, so the exhaustive member probes
 * live here and the validation test keeps its original write-surface coverage.
 * Shared-type coverage lives in [ReadOnlyEntClientCompileTest].
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
            .generate(listOf(SchemaInput(car), SchemaInput(user)))
            .toCompileTestSources()
    }

    private fun ruleSnippet(body: String, ruleType: String = "CarLoadPrivacyRule"): SourceFile = SourceFile.kotlin(
        "PrivacyRuleSnippet.kt",
        """
        package com.example.app

        import com.example.ent.$ruleType
        import com.example.ent.ReadOnlyEntClient
        import entkt.runtime.privacy.PrivacyDecision
        import entkt.runtime.privacy.ViewerContext
        import entkt.runtime.result.visibleOrNull
        import java.util.UUID

        val rule = $ruleType { ctx, _ ->
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
    fun `privacy rule can read - LOAD-checked terminals, byId family, and index helpers`() {
        // The positive twin: the viewer-scoped read surface promised to
        // rules. If this test stops compiling, the negatives below prove
        // nothing.
        val result = compile(
            generatedSources() + ruleSnippet(
                """
                val concrete: ReadOnlyEntClient = ctx.client
                ctx.client.cars.query { }.firstOrNull(ctx.viewerContext)
                ctx.client.cars.query { }.all(ctx.viewerContext).getOrThrow()
                ctx.client.users.findById(ctx.viewerContext, UUID.randomUUID()).getOrThrow()
                ctx.client.users.findById(ctx.viewerContext, UUID.randomUUID()).visibleOrNull()
                ctx.client.users.indexes.email("a@b.c").find(ctx.viewerContext)
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
    fun `mutation execution requires the operation's concrete read-client type`() {
        for (owned in listOf(false, true)) {
            for (clientType in listOf("ReadOnlyEntClient", "EntClient")) {
                val method = if (owned) "executeInOwnedTransactionForInternalUse" else "execute"
                val capture = if (owned) "completionCapture = MutationCompletionCapture()," else ""
                val result = compile(
                    generatedSources() + SourceFile.kotlin(
                        "MutationRuleClientSnippet.kt",
                        """
                        @file:OptIn(entkt.query.EntktInternal::class)

                        package com.example.app

                        import com.example.ent.EntClient
                        import com.example.ent.ReadOnlyEntClient
                        import entkt.runtime.mutation.execution.MutationCompletionCapture
                        import entkt.runtime.mutation.execution.MutationExecutor
                        import entkt.runtime.mutation.execution.MutationOperation

                        fun execute(
                            executor: MutationExecutor,
                            operation: MutationOperation<ReadOnlyEntClient, Unit, Unit>,
                            client: $clientType,
                        ) = executor.$method(
                            operation = operation,
                            input = Unit,
                            ruleClient = client,
                            $capture
                        )
                        """.trimIndent(),
                    ),
                )
                val expected = if (clientType == "ReadOnlyEntClient") {
                    KotlinCompilation.ExitCode.OK
                } else {
                    KotlinCompilation.ExitCode.COMPILATION_ERROR
                }
                assertEquals(expected, result.exitCode, "$method with $clientType:\n${result.messages}")
                if (expected == KotlinCompilation.ExitCode.COMPILATION_ERROR) {
                    assertTrue(result.messages.contains("ReadOnlyEntClient"), result.messages)
                }
            }
        }
    }

    @Test
    fun `privacy rule can explicitly select a bypass context for a terminal`() {
        val result = compile(
            generatedSources() + ruleSnippet(
                """
                ctx.client.cars.query().all(
                    ViewerContext.privacyBypass_DANGEROUS("rule read"),
                )
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected an explicit terminal-level bypass context to compile, got:\n${result.messages}",
        )
    }

    @Test
    fun `all four privacy contexts expose the read-only client`() {
        // Type pins for every operation context — a regression returning
        // EntClient or another capability surface breaks the assignment.
        val result = compile(
            generatedSources() + SourceFile.kotlin(
                "AllContextsSnippet.kt",
                """
                package com.example.app

                import com.example.ent.CarLoadPrivacyRule
                import com.example.ent.CarCreatePrivacyRule
                import com.example.ent.CarUpdatePrivacyRule
                import com.example.ent.CarDeletePrivacyRule
                import com.example.ent.ReadOnlyEntClient
                import entkt.runtime.privacy.PrivacyDecision

                val load = CarLoadPrivacyRule { ctx, _ ->
                    val t: ReadOnlyEntClient = ctx.client
                    PrivacyDecision.Continue
                }
                val create = CarCreatePrivacyRule { ctx, _ ->
                    val t: ReadOnlyEntClient = ctx.client
                    PrivacyDecision.Continue
                }
                val update = CarUpdatePrivacyRule { ctx, _ ->
                    val t: ReadOnlyEntClient = ctx.client
                    PrivacyDecision.Continue
                }
                val delete = CarDeletePrivacyRule { ctx, _ ->
                    val t: ReadOnlyEntClient = ctx.client
                    PrivacyDecision.Continue
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected all four privacy contexts to expose ReadOnlyEntClient, got:\n${result.messages}",
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
    fun `privacy read client does not expose withViewerContext`() {
        assertUnresolved("withViewerContext", "ctx.client.withViewerContext(ctx.viewerContext) { }")
    }

    @Test
    fun `privacy read client does not expose client-level bypass helper`() {
        assertUnresolved("bypassPrivacy_DANGEROUS", "ctx.client.bypassPrivacy_DANGEROUS(\"x\") { }")
    }

    @Test
    fun `privacy rule cannot mutate client configuration`() {
        // Same-module `internal var`s on the full client today; absent
        // members on the read client, so each probe fails the same
        // unresolved-reference way.
        assertUnresolved("transactionRequirement", "ctx.client.transactionRequirement = null")
        assertUnresolved("viewerContextProvider", "ctx.client.viewerContextProvider = { ctx.viewerContext }")
        assertUnresolved("defaultUpdateConsistency", "ctx.client.defaultUpdateConsistency = null")
        assertUnresolved("defaultRelationshipLocking", "ctx.client.defaultRelationshipLocking = null")
    }

    // ---- Removed rule-client factories stay absent ----

    private fun mintSnippet(call: String): SourceFile = SourceFile.kotlin(
        "MintSnippet.kt",
        """
            package com.example.app

            import com.example.ent.EntClient
            import entkt.runtime.privacy.ViewerContext
            import entkt.runtime.privacy.Viewer

            fun mint(client: EntClient) {
                client.$call
            }
        """.trimIndent(),
    )

    private fun assertAdapterRemoved(adapter: String, call: String) {
        val result = compile(generatedSources() + mintSnippet(call))
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected removed $adapter to fail compilation but it succeeded",
        )
        assertTrue(
            result.messages.contains("nresolved reference") && result.messages.contains(adapter),
            "Expected an unresolved-reference error for $adapter, got:\n${result.messages}",
        )
    }

    @Test
    fun `validation read client factory is absent`() {
        assertAdapterRemoved(
            "asValidationReadClientForInternalUse",
            "asValidationReadClientForInternalUse()",
        )
    }

    @Test
    fun `privacy read client factory is absent`() {
        assertAdapterRemoved(
            "asPrivacyReadClientForInternalUse",
            "asPrivacyReadClientForInternalUse(ViewerContext(Viewer.Anonymous))",
        )
    }
}
