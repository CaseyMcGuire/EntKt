@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.fixtures.Car
import entkt.codegen.fixtures.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins how generated code is bound to the application's viewer type, the one named by `viewerType`. */
class ApplicationViewerCompileTest {
    private fun compile(
        vararg application: SourceFile,
        viewerType: String = TEST_VIEWER_TYPE,
    ): JvmCompilationResult {
        val generated = EntGenerator("com.example.ent", viewerType)
            .generate(listOf(SchemaInput(Car()), SchemaInput(User())))
            .toCompileTestSources()
        return compileSources(generated + application)
    }

    private fun assertOnlyFails(result: JvmCompilationResult, fileName: String) {
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        val errors = result.messages.lineSequence().filter { line -> line.startsWith("e:") }.toList()
        assertTrue(errors.isNotEmpty(), result.messages)
        assertTrue(errors.all { line -> "$fileName:" in line }, result.messages)
    }

    @Test
    fun `generated terminals accept the application's viewer and a privacy bypass`() {
        val source = SourceFile.kotlin(
            "Terminals.kt",
            """
            package com.example.app

            import com.example.auth.AppViewer
            import com.example.ent.EntClient
            import entkt.runtime.privacy.ViewerContext

            fun readAsViewer(client: EntClient, viewer: AppViewer) =
                client.users.query { }.all(ViewerContext.AsViewer(viewer))

            fun readWithBypass(client: EntClient) =
                client.users.query { }.all(ViewerContext.privacyBypass_DANGEROUS("probe"))

            fun readWithEither(client: EntClient, viewerContext: ViewerContext<AppViewer>) =
                client.users.query { }.all(viewerContext)
            """.trimIndent(),
        )

        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `a generated terminal rejects a context built for another viewer type`() {
        val source = SourceFile.kotlin(
            "OtherViewerContext.kt",
            """
            package com.example.app

            import com.example.ent.EntClient
            import entkt.runtime.privacy.ViewerContext

            class OtherViewer

            fun invalid(client: EntClient) =
                client.users.query { }.all(ViewerContext.AsViewer(OtherViewer()))
            """.trimIndent(),
        )

        val result = compile(source)

        assertOnlyFails(result, "OtherViewerContext.kt")
    }

    @Test
    fun `a rule compares the viewer's id with a field of the same type`() {
        val source = SourceFile.kotlin(
            "OwnerRule.kt",
            """
            package com.example.app

            import com.example.auth.AppViewer
            import com.example.ent.ReadOnlyEntClient
            import com.example.ent.User
            import com.example.ent.UserLoadPrivacyRule
            import entkt.runtime.privacy.PrivacyDecision
            import entkt.runtime.privacy.PrivacyRuleContext

            class AllowSelf : UserLoadPrivacyRule {
                override fun run(
                    context: PrivacyRuleContext<AppViewer, ReadOnlyEntClient>,
                    item: User,
                ): PrivacyDecision =
                    if (context.viewer.userId == item.id) {
                        PrivacyDecision.Allow
                    } else {
                        PrivacyDecision.Continue
                    }
            }
            """.trimIndent(),
        )

        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `comparing the viewer's id with a field of an unrelated type does not compile`() {
        val source = SourceFile.kotlin(
            "MismatchedOwnerRule.kt",
            """
            package com.example.app

            import com.example.auth.AppViewer
            import com.example.ent.ReadOnlyEntClient
            import com.example.ent.User
            import com.example.ent.UserLoadPrivacyRule
            import entkt.runtime.privacy.PrivacyDecision
            import entkt.runtime.privacy.PrivacyRuleContext

            class AllowSelf : UserLoadPrivacyRule {
                override fun run(
                    context: PrivacyRuleContext<AppViewer, ReadOnlyEntClient>,
                    item: User,
                ): PrivacyDecision =
                    if (context.viewer.userId == item.name) {
                        PrivacyDecision.Allow
                    } else {
                        PrivacyDecision.Continue
                    }
            }
            """.trimIndent(),
        )

        val result = compile(source)

        assertOnlyFails(result, "MismatchedOwnerRule.kt")
    }

    @Test
    fun `a viewer type named like a generated type parameter still compiles`() {
        for (name in listOf("Entity", "Result")) {
            val viewerSource = SourceFile.kotlin("$name.kt", "package com.example.named\n\nclass $name\n")

            val result = compile(viewerSource, viewerType = "com.example.named.$name")

            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "viewer $name: ${result.messages}")
        }
    }

    @Test
    fun `a public interface with an internal implementation works as the viewer type`() {
        val source = SourceFile.kotlin(
            "Principal.kt",
            """
            package com.example.hidden

            import com.example.ent.EntClient
            import entkt.runtime.privacy.ViewerContext

            interface Principal {
                val name: String
            }

            internal class SessionPrincipal(override val name: String) : Principal

            fun read(client: EntClient) =
                client.users.query { }.all(ViewerContext.AsViewer(SessionPrincipal("probe")))
            """.trimIndent(),
        )

        val result = compile(source, viewerType = "com.example.hidden.Principal")

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `allowAll and a rule written against a supertype of the viewer register in typed policy slots`() {
        val source = SourceFile.kotlin(
            "StaffPolicy.kt",
            """
            package com.example.staff

            import com.example.ent.User
            import com.example.ent.UserPolicyScope
            import entkt.runtime.privacy.ContextPrivacyRule
            import entkt.runtime.privacy.EntityPolicy
            import entkt.runtime.privacy.PrivacyDecision
            import entkt.runtime.privacy.PrivacyRuleContext
            import entkt.runtime.privacy.allowAll
            import entkt.runtime.rule.EntRuleClient

            interface HasRoles {
                val roles: Set<String>
            }

            data class StaffViewer(override val roles: Set<String>) : HasRoles

            class AllowAdmins : ContextPrivacyRule<HasRoles, EntRuleClient> {
                override fun run(context: PrivacyRuleContext<HasRoles, EntRuleClient>): PrivacyDecision =
                    if ("admin" in context.viewer.roles) {
                        PrivacyDecision.Allow
                    } else {
                        PrivacyDecision.Continue
                    }
            }

            object UserPolicy : EntityPolicy<User, UserPolicyScope> {
                override fun configure(scope: UserPolicyScope) = scope.run {
                    privacy {
                        load(AllowAdmins(), allowAll)
                        create(AllowAdmins())
                        update(AllowAdmins())
                        delete(allowAll)
                    }
                }
            }
            """.trimIndent(),
        )

        val result = compile(source, viewerType = "com.example.staff.StaffViewer")

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
