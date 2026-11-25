@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.mutation.ValidationGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationScopeCompileTest {
    // Produces EntityValidationScope, matching the runtime base name.
    private class Entity : EntSchema("entities", clientName = "entities") {
        override fun id() = EntId.long()
        val name by string("name")
    }

    private fun generatedSources(): List<SourceFile> =
        listOf(ValidationGenerator("com.example.ent").generate("Entity", Entity()))
            .toCompileTestSources() + SourceFile.kotlin(
            "ValidationInputs.kt",
            """
            package com.example.ent

            import entkt.runtime.rule.EntRuleClient

            class ReadOnlyEntClient : EntRuleClient
            class EntityWriteCandidate
            class EntityUpdateRuleInput
            class EntityDeleteRuleInput
            """.trimIndent(),
        )

    @Test
    fun `a generated scope keeps its types even when its name matches the runtime class`() {
        val probe = SourceFile.kotlin(
            "ValidationScopeProbe.kt",
            """
            @file:OptIn(entkt.query.EntktInternal::class)
            package com.example.app

            import com.example.ent.EntityCreateBatchValidationRule
            import com.example.ent.EntityCreateValidationRule
            import com.example.ent.EntityDeleteBatchValidationRule
            import com.example.ent.EntityDeleteValidationRule
            import com.example.ent.EntityUpdateBatchValidationRule
            import com.example.ent.EntityUpdateValidationRule
            import com.example.ent.EntityValidationScope
            import entkt.runtime.validation.EntityValidationConfig

            object ValidationScopeProbe {
                @JvmStatic
                fun run(): String {
                    val create = EntityCreateValidationRule { _, _ -> error("not evaluated") }
                    val update = EntityUpdateValidationRule { _, _ -> error("not evaluated") }
                    val delete = EntityDeleteValidationRule { _, _ -> error("not evaluated") }
                    val config = EntityValidationConfig<
                        EntityCreateBatchValidationRule,
                        EntityUpdateBatchValidationRule,
                        EntityDeleteBatchValidationRule,
                    >()
                    EntityValidationScope(config).apply {
                        create(create)
                        update(update)
                        delete(delete)
                        updateDerivesFromCreate()
                    }

                    val copy = config.deepCopy()
                    val createRules: List<EntityCreateBatchValidationRule> = copy.createRules
                    val updateRules: List<EntityUpdateBatchValidationRule> = copy.updateRules
                    val deleteRules: List<EntityDeleteBatchValidationRule> = copy.deleteRules
                    check(createRules.single() === create)
                    check(updateRules.single() === update)
                    check(deleteRules.single() === delete)
                    check(copy.updateDerivesFromCreate)
                    config.createRules.clear()
                    config.updateDerivesFromCreate = false
                    check(copy.createRules.single() === create)
                    check(copy.updateDerivesFromCreate)
                    return "ok"
                }
            }
            """.trimIndent(),
        )
        val result = compileSources(generatedSources() + probe)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val probeClass = result.classLoader.loadClass("com.example.app.ValidationScopeProbe")
        assertEquals("ok", probeClass.getMethod("run").invoke(null))
    }

    @Test
    fun `inherited validation API still rejects load and delete derivation`() {
        val invalidSources = mapOf(
            "LoadValidation" to "fun invalid(scope: EntityValidationScope) { scope.load() }",
            "DeleteDerivationScope" to "fun invalid(scope: EntityValidationScope) { scope.deleteDerivesFromCreate() }",
            "DeleteDerivationConfig" to
                "fun invalid(config: EntityValidationConfig<*, *, *>) { config.deleteDerivesFromCreate = true }",
        )
        val snippets = invalidSources.map { (name, body) ->
            SourceFile.kotlin(
                "$name.kt",
                """
                package com.example.app

                import com.example.ent.EntityValidationScope
                import entkt.runtime.validation.EntityValidationConfig

                $body
                """.trimIndent(),
            )
        }
        val result = compileSources(generatedSources() + snippets)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in invalidSources.keys) {
            assertTrue(
                result.messages.lineSequence().any { it.startsWith("e:") && "$name.kt:" in it },
                result.messages,
            )
        }
    }
}
