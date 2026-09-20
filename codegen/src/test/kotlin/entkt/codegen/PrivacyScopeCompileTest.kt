@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.entity.PrivacyGenerator
import entkt.codegen.mutation.ValidationGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivacyScopeCompileTest {
    // Produces EntityPrivacyConfig and EntityPrivacyScope, matching the runtime base names.
    private class Entity : EntSchema("entities", clientName = "entities") {
        override fun id() = EntId.long()
    }

    private fun generatedSources(): List<SourceFile> {
        val schema = Entity()
        return listOf(
            PrivacyGenerator("com.example.ent").generate("Entity", schema),
            ValidationGenerator("com.example.ent").generate("Entity", schema),
        ).toCompileTestSources() + SourceFile.kotlin(
            "PrivacyInputs.kt",
            """
            package com.example.ent

            import entkt.runtime.entity.EntEntity
            import entkt.runtime.rule.EntRuleClient

            data class Entity(override val id: Long) : EntEntity.LongId
            class ReadOnlyEntClient : EntRuleClient
            class EntityUpdateRuleInput
            class EntityDeleteRuleInput
            """.trimIndent(),
        )
    }

    @Test
    fun `generated subclasses retain typed resolution even when their names match the runtime bases`() {
        val probe = SourceFile.kotlin(
            "PrivacyScopeProbe.kt",
            """
            @file:OptIn(entkt.query.EntktInternal::class)
            package com.example.app

            import com.example.ent.EntityCreateBatchPrivacyRule
            import com.example.ent.EntityCreatePrivacyRule
            import com.example.ent.EntityDeleteBatchPrivacyRule
            import com.example.ent.EntityDeletePrivacyRule
            import com.example.ent.EntityLoadBatchPrivacyRule
            import com.example.ent.EntityLoadPrivacyRule
            import com.example.ent.EntityPrivacyConfig
            import com.example.ent.EntityPrivacyScope
            import com.example.ent.EntityUpdateBatchPrivacyRule
            import com.example.ent.EntityUpdatePrivacyRule
            import entkt.runtime.privacy.ContextPrivacyRule
            import entkt.runtime.privacy.ResolvedEntityPrivacyConfig
            import entkt.runtime.rule.EntRuleClient

            object PrivacyScopeProbe {
                @JvmStatic
                fun run(): String {
                    val load = EntityLoadPrivacyRule { _, _ -> error("not evaluated") }
                    val create = EntityCreatePrivacyRule { _, _ -> error("not evaluated") }
                    val update = EntityUpdatePrivacyRule { _, _ -> error("not evaluated") }
                    val delete = EntityDeletePrivacyRule { _, _ -> error("not evaluated") }
                    val shared = ContextPrivacyRule<EntRuleClient> { error("not evaluated") }
                    val config = EntityPrivacyConfig()
                    EntityPrivacyScope(config).apply {
                        load(load)
                        load(shared)
                        create(create)
                        create(shared)
                        update(update)
                        update(shared)
                        delete(delete)
                        delete(shared)
                        updateDerivesFromCreate()
                        deleteDerivesFromCreate()
                    }

                    val resolved: ResolvedEntityPrivacyConfig<
                        EntityLoadBatchPrivacyRule,
                        EntityCreateBatchPrivacyRule,
                        EntityUpdateBatchPrivacyRule,
                        EntityDeleteBatchPrivacyRule,
                    > = config.resolveForInternalUse()
                    check(resolved.loadRules.first() === load)
                    check(resolved.createRules.first() === create)
                    check(resolved.updateRules.first() === update)
                    check(resolved.deleteRules.first() === delete)
                    check(resolved.loadRules.size == 2)
                    check(resolved.createRules.size == 2)
                    check(resolved.updateRules.size == 2)
                    check(resolved.deleteRules.size == 2)
                    check(resolved.updateDerivesFromCreate)
                    check(resolved.deleteDerivesFromCreate)
                    config.loadRules.clear()
                    config.updateDerivesFromCreate = false
                    config.deleteDerivesFromCreate = false
                    check(resolved.loadRules.first() === load)
                    check(resolved.loadRules.size == 2)
                    check(resolved.updateDerivesFromCreate)
                    check(resolved.deleteDerivesFromCreate)
                    return "ok"
                }
            }
            """.trimIndent(),
        )
        val result = compileSources(generatedSources() + probe)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val probeClass = result.classLoader.loadClass("com.example.app.PrivacyScopeProbe")
        assertEquals("ok", probeClass.getMethod("run").invoke(null))
    }
}
