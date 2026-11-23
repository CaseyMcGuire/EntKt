@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.fixtures.Car
import entkt.codegen.fixtures.User
import kotlin.test.Test
import kotlin.test.assertEquals

class ConditionalPrivacyRulesCompileTest {
    private fun compile(registration: String): JvmCompilationResult {
        val generated = EntGenerator("com.example.ent", TEST_VIEWER_TYPE)
            .generate(listOf(SchemaInput(Car()), SchemaInput(User())))
            .toCompileTestSources()
        val policy = SourceFile.kotlin(
            "UserPolicy.kt",
            """
            package com.example.app

            import com.example.ent.*
            import com.example.auth.AppViewer
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
        return compileSources(generated + policy)
    }

    @Test
    fun `helpers infer concrete clients and lifecycle items in the unchanged DSL`() {
        val result = compile(
            """
            load(
                denyIf("Authentication required") { context, _ ->
                    context.viewer.userId == null
                },
                allowIf { context, user ->
                    val client: ReadOnlyEntClient = context.client
                    context.viewer.userId == user.id
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
                context.viewer.userId == typed.entity.id
            })
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `helpers for another entity cannot register`() {
        val helpers = listOf(
            "allowIf<AppViewer, ReadOnlyEntClient, Car>",
            "denyIf<AppViewer, ReadOnlyEntClient, Car>(\"denied\")",
        )
        for (helper in helpers) {
            val result = compile("load($helper { _, _ -> true })")
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        }
    }
}
