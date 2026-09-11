@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

class ConditionalPrivacyRulesCompileTest {
    private fun compile(registration: String): JvmCompilationResult {
        val generated = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(Car()), SchemaInput(User())))
            .toCompileTestSources()
        val policy = SourceFile.kotlin(
            "UserPolicy.kt",
            """
            package com.example.app

            import com.example.ent.*
            import entkt.runtime.privacy.*

            object UserPolicy : EntityPolicy<User, UserPolicyScope> {
                override fun configure(scope: UserPolicyScope) = scope.run {
                    privacy {
                        $registration
                    }
                }
            }
            """.trimIndent(),
        )
        return KotlinCompilation().apply {
            sources = generated + policy
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()
    }

    @Test
    fun `helpers infer concrete clients and lifecycle items in the unchanged DSL`() {
        val result = compile(
            """
            load(
                denyIf("Authentication required") { context, _ ->
                    context.viewerContext.viewer is Viewer.Anonymous
                },
                allowIf { context, user ->
                    val client: ReadOnlyEntClient = context.client
                    context.viewerContext.userIdOrNull() == user.id
                },
            )
            create(allowIf { context, candidate ->
                val client: ReadOnlyEntClient = context.client
                val typed: UserWriteCandidate = candidate
                typed.name.isNotBlank()
            })
            update(denyIf("Name cannot be blank") { context, input ->
                val client: ReadOnlyEntClient = context.client
                val typed: UserUpdateRuleInput = input
                typed.candidate.name.isBlank()
            })
            delete(allowIf { context, input ->
                val client: ReadOnlyEntClient = context.client
                val typed: UserDeleteRuleInput = input
                context.viewerContext.userIdOrNull() == typed.entity.id
            })
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `helpers for another entity cannot register`() {
        val helpers = listOf(
            "allowIf<ReadOnlyEntClient, Car>",
            "denyIf<ReadOnlyEntClient, Car>(\"denied\")",
        )
        for (helper in helpers) {
            val result = compile("load($helper { _, _ -> true })")
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        }
    }
}
