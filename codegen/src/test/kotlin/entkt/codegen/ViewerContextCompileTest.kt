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

            import entkt.runtime.privacy.PrivacyRuleContext
            import entkt.runtime.privacy.Viewer
            import entkt.runtime.privacy.ViewerContext
            import entkt.runtime.rule.EntRuleClient

            fun contexts(): List<ViewerContext> = listOf(
                ViewerContext.AsViewer(Viewer.Anonymous),
                ViewerContext.privacyBypass_DANGEROUS("probe"),
            )

            fun describe(viewerContext: ViewerContext): String =
                when (viewerContext) {
                    is ViewerContext.AsViewer -> "viewer " + viewerContext.viewer
                    is ViewerContext.PrivacyBypass -> "bypass " + viewerContext.reason
                }

            fun ruleViewers(context: PrivacyRuleContext<EntRuleClient>): List<Viewer> =
                listOf(context.viewer, context.viewerContext.viewer)
            """.trimIndent(),
        )

        val result = compileSources(listOf(probe))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `application code cannot construct a bypass directly or read a viewer without branching`() {
        val invalidSources = mapOf(
            "DirectBypass" to "fun invalid() = ViewerContext.PrivacyBypass(\"reason\")",
            "CopiedBypass" to
                "fun invalid(bypass: ViewerContext.PrivacyBypass) = bypass.copy(reason = \"other\")",
            "UnbranchedViewer" to "fun invalid(viewerContext: ViewerContext) = viewerContext.viewer",
        )
        val snippets = invalidSources.map { (name, body) ->
            SourceFile.kotlin(
                "$name.kt",
                """
                package com.example.app.${name.lowercase()}

                import entkt.runtime.privacy.ViewerContext

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
