@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins what application code can do with a `ViewerContext` from outside the runtime module. */
class ViewerContextCompileTest {
    @Test
    fun `application code builds both kinds of context and branches on them exhaustively`() {
        val probe = SourceFile.kotlin(
            "ViewerContextProbe.kt",
            """
            package com.example.app

            import com.example.auth.AppViewer
            import entkt.runtime.privacy.PrivacyDecision
            import entkt.runtime.privacy.PrivacyRule
            import entkt.runtime.privacy.PrivacyRuleContext
            import entkt.runtime.privacy.ViewerContext
            import entkt.runtime.query.QueryInterceptor
            import entkt.runtime.rule.EntRuleClient

            fun contexts(): List<ViewerContext<AppViewer>> = listOf(
                ViewerContext.AsViewer(AppViewer(userId = null)),
                ViewerContext.privacyBypass_DANGEROUS("probe"),
            )

            fun describe(viewerContext: ViewerContext<AppViewer>): String =
                when (viewerContext) {
                    is ViewerContext.AsViewer -> "viewer " + viewerContext.viewer
                    is ViewerContext.PrivacyBypass -> "bypass " + viewerContext.reason
                }

            fun ruleViewers(context: PrivacyRuleContext<AppViewer, EntRuleClient>): List<AppViewer> =
                listOf(context.viewer, context.viewerContext.viewer)

            // Rules and interceptors that ignore the viewer fit slots typed for a specific viewer.
            val anyViewerRule: PrivacyRule<Any, EntRuleClient, Any?> =
                PrivacyRule { _, _ -> PrivacyDecision.Continue }
            val typedRule: PrivacyRule<AppViewer, EntRuleClient, String> = anyViewerRule
            val anyViewerInterceptor: QueryInterceptor<Any, String> = QueryInterceptor { _, _ -> }
            val typedInterceptor: QueryInterceptor<AppViewer, String> = anyViewerInterceptor
            """.trimIndent(),
        )

        val result = compileSources(listOf(probe))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `application code cannot construct a bypass directly, read a viewer without branching, or mix viewer types`() {
        val invalidSources = mapOf(
            "DirectBypass" to "fun invalid() = ViewerContext.PrivacyBypass(\"reason\")",
            "CopiedBypass" to
                "fun invalid(bypass: ViewerContext.PrivacyBypass) = bypass.copy(reason = \"other\")",
            "UnbranchedViewer" to "fun invalid(viewerContext: ViewerContext<AppViewer>) = viewerContext.viewer",
            "UnrelatedViewer" to "fun invalid() = typed(ViewerContext.AsViewer(OtherViewer()))",
        )
        val snippets = invalidSources.map { (name, body) ->
            SourceFile.kotlin(
                "$name.kt",
                """
                package com.example.app.${name.lowercase()}

                import com.example.auth.AppViewer
                import entkt.runtime.privacy.ViewerContext

                class OtherViewer

                fun typed(viewerContext: ViewerContext<AppViewer>): ViewerContext<AppViewer> = viewerContext

                $body
                """.trimIndent(),
            )
        }

        val result = compileSources(snippets)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in invalidSources.keys) {
            assertTrue(
                result.messages.lineSequence().any { it.startsWith("e:") && "$name.kt:" in it },
                result.messages,
            )
        }
    }
}
