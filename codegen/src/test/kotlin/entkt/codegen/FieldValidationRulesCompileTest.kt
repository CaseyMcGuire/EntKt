@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldValidationRulesCompileTest {
    private fun compile(rules: String): JvmCompilationResult {
        val generated = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(Car()), SchemaInput(User())))
            .toCompileTestSources()
        val policy = SourceFile.kotlin(
            "UserPolicy.kt",
            """
            package com.example.app

            import com.example.ent.*
            import entkt.runtime.driver.DatabaseDriver
            import entkt.runtime.privacy.EntityPolicy
            import entkt.runtime.validation.*

            object UserPolicy : EntityPolicy<User, UserPolicyScope> {
                override fun configure(scope: UserPolicyScope) = scope.run {
                    validation {
                        create($rules)
                        updateDerivesFromCreate()
                    }
                }
            }

            fun client(driver: DatabaseDriver): EntClient = EntClient(driver) {
                policies { users(UserPolicy) }
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
    fun `runtime helpers register through the unchanged generated client DSL`() {
        val result = compile(
            """
            minLength(UserWriteCandidate::name, 2),
            maxLength(UserWriteCandidate::name, 64),
            notEmpty(UserWriteCandidate::name),
            matches(UserWriteCandidate::email, Regex(".+@.+")),
            min(UserWriteCandidate::age, 0),
            max(UserWriteCandidate::age, 150),
            positive(UserWriteCandidate::age),
            negative(UserWriteCandidate::age),
            nonNegative(UserWriteCandidate::age),
            UserCreateValidationRule { _, _ -> ValidationDecision.Valid },
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `a rule for another entity cannot register`() {
        val result = compile("minLength(CarWriteCandidate::model, 2)")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    }

    @Test
    fun `string rules reject numeric properties`() {
        val result = compile("minLength(UserWriteCandidate::age, 2)")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    }

    @Test
    fun `numeric bounds require the property numeric type`() {
        val result = compile("min(UserWriteCandidate::age, 1.5)")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    }
}
