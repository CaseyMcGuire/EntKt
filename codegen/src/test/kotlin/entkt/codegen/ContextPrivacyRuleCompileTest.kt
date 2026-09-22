@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.fixtures.Car
import entkt.codegen.fixtures.User
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextPrivacyRuleCompileTest {
    private fun compile(vararg application: SourceFile): JvmCompilationResult {
        val generated = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(Car()), SchemaInput(User())))
            .toCompileTestSources()
        return compileSources(generated + application)
    }

    private fun contextRulesSource(body: String): SourceFile =
        SourceFile.kotlin(
            "ContextRules.kt",
            """
            package com.example.app

            import com.example.ent.*
            import entkt.runtime.privacy.*
            import entkt.runtime.rule.EntRuleClient

            $body
            """.trimIndent(),
        )

    @Test
    fun `one context rule class can register across entities and all four operations`() {
        val source = contextRulesSource(
            """
            class AuthenticatedRule : ContextPrivacyRule<ReadOnlyEntClient> {
                override fun run(context: PrivacyRuleContext<ReadOnlyEntClient>): PrivacyDecision {
                    val client: ReadOnlyEntClient = context.client
                    return if (context.viewerContext.viewer is Viewer.Anonymous) {
                        PrivacyDecision.Deny("authentication required")
                    } else {
                        PrivacyDecision.Allow
                    }
                }
            }

            private val shared = AuthenticatedRule()

            object UserPolicy : EntityPolicy<User, UserPolicyScope> {
                override fun configure(scope: UserPolicyScope) = scope.run {
                    privacy {
                        load(shared)
                        create(shared)
                        update(shared)
                        delete(shared)
                    }
                }
            }

            object CarPolicy : EntityPolicy<Car, CarPolicyScope> {
                override fun configure(scope: CarPolicyScope) = scope.run {
                    privacy {
                        load(shared)
                        create(shared)
                        update(shared)
                        delete(shared)
                    }
                }
            }
            """.trimIndent(),
        )

        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `context scalar and batch rules mix in one call with client and item inference`() {
        val source = contextRulesSource(
            """
            private val general = ContextPrivacyRule<EntRuleClient> { PrivacyDecision.Continue }

            object UserPolicy : EntityPolicy<User, UserPolicyScope> {
                override fun configure(scope: UserPolicyScope) = scope.run {
                    privacy {
                        load(
                            general,
                            ContextPrivacyRule { context ->
                                val client: ReadOnlyEntClient = context.client
                                PrivacyDecision.Continue
                            },
                            allowIf { context, user ->
                                val client: ReadOnlyEntClient = context.client
                                context.viewerContext.userIdOrNull() == user.id
                            },
                            batchPrivacyRule { _, items -> items.allowAll() },
                        )
                        create(
                            general,
                            ContextPrivacyRule { context ->
                                val client: ReadOnlyEntClient = context.client
                                PrivacyDecision.Continue
                            },
                            denyIf("blank name") { _, candidate -> candidate.name.isBlank() },
                            batchPrivacyRule { _, items -> items.allowAll() },
                        )
                        update(
                            general,
                            allowIf { _, input -> input.candidate.name.isNotBlank() },
                            batchPrivacyRule { _, items -> items.allowAll() },
                        )
                        delete(
                            general,
                            allowIf { context, input -> context.viewerContext.userIdOrNull() == input.entity.id },
                            batchPrivacyRule { _, items -> items.allowAll() },
                        )

                        val contexts = arrayOf(general, general)
                        load(*contexts)
                        create(*contexts)
                        update(*contexts)
                        delete(*contexts)
                    }
                }
            }
            """.trimIndent(),
        )

        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `context rules requiring an unrelated client cannot register`() {
        val source = contextRulesSource(
            """
            class OtherClient : EntRuleClient
            val rule = ContextPrivacyRule<OtherClient> { PrivacyDecision.Allow }

            object UserPolicy : EntityPolicy<User, UserPolicyScope> {
                override fun configure(scope: UserPolicyScope) = scope.run {
                    privacy { create(rule) }
                }
            }
            """.trimIndent(),
        )

        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    }

    @Test
    fun `context rules cannot write through their read-only client`() {
        val source = contextRulesSource(
            """
            val rule = ContextPrivacyRule<ReadOnlyEntClient> { context ->
                context.client.users.create { name = "not allowed" }
                PrivacyDecision.Allow
            }
            """.trimIndent(),
        )

        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    }

    @Test
    fun `Java can mix context scalar and batch rules through the same vararg methods`() {
        val result = compile(
            SourceFile.java(
                "ContextRuleJava.java",
                """
                package com.example.app;

                import com.example.ent.ReadOnlyEntClient;
                import com.example.ent.User;
                import com.example.ent.UserPrivacyScope;
                import entkt.runtime.privacy.BatchPrivacyRule;
                import entkt.runtime.privacy.ContextPrivacyRule;
                import entkt.runtime.privacy.PrivacyDecision;
                import entkt.runtime.privacy.PrivacyRule;
                import entkt.runtime.privacy.PrivacyRuleContext;

                public final class ContextRuleJava implements ContextPrivacyRule<ReadOnlyEntClient> {
                    @Override
                    public PrivacyDecision run(PrivacyRuleContext<ReadOnlyEntClient> context) {
                        ReadOnlyEntClient client = context.getClient();
                        return PrivacyDecision.Allow.INSTANCE;
                    }

                    public static void register(UserPrivacyScope scope) {
                        ContextRuleJava shared = new ContextRuleJava();
                        ContextPrivacyRule<ReadOnlyEntClient> lambda = context -> PrivacyDecision.Allow.INSTANCE;
                        PrivacyRule<ReadOnlyEntClient, User> scalar = (context, item) -> PrivacyDecision.Allow.INSTANCE;
                        BatchPrivacyRule<ReadOnlyEntClient, User> batch = (context, items) ->
                            items.decideEach(item -> PrivacyDecision.Allow.INSTANCE);
                        scope.load(shared, scalar, batch, lambda);
                        scope.create(shared, lambda);
                        scope.update(shared, lambda);
                        scope.delete(shared, lambda);
                    }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `an entity named ContextPrivacyRule does not collide with the runtime contract`() {
        val generated = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(ContextPrivacyRule())))
            .toCompileTestSources()
        val result = compileSources(generated)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    private class ContextPrivacyRule : EntSchema("context_rules", clientName = "contextRules") {
        override fun id() = EntId.long()
    }
}
