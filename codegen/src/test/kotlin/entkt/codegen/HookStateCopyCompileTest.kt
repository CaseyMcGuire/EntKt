@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.mutation.MutationGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

class HookStateCopyCompileTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
    }

    private class Record : EntSchema("records", clientName = "records") {
        override fun id() = EntId.long()
        val name by string("name")
        val description by string("description").nullable()
        val createdAt by instant("created_at").immutable()
        val owner by belongsTo<Owner>("owner_id")
    }

    @Test
    fun `generated replacements preserve other fields context and original states`() {
        val record = Record()
        val schemas = listOf(Owner(), record)
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = MutationGenerator("com.example.ent")
            .generate("Record", record, schemas.associateWith { it::class.simpleName!! })
            .toCompileTestSources()
        val probe = SourceFile.kotlin(
            "HookStateCopyProbe.kt",
            """
            @file:OptIn(entkt.query.EntktInternal::class)
            package com.example.ent

            import entkt.runtime.entity.EntEntity
            import entkt.runtime.mutation.FieldPatch
            import entkt.runtime.privacy.Viewer
            import entkt.runtime.privacy.ViewerContext
            import java.time.Instant

            // Only the schema-specific context types are needed to exercise the generated states.
            data class Record(override val id: Long) : EntEntity.LongId
            class EntClientScope
            class RecordPendingEdgeOps

            object HookStateCopyProbe {
                @JvmStatic
                fun run(): String {
                    val name = FieldPatch.Set<String?>("original")
                    val description = FieldPatch.Set<String?>("description")
                    val ownerId = FieldPatch.Set<Long?>(1L)
                    val createdAt = FieldPatch.Set<Instant?>(Instant.parse("2000-01-01T00:00:00Z"))
                    val client = EntClientScope()
                    val viewerContext = ViewerContext(Viewer.User(7L))
                    val before = Record(1L)
                    val pendingEdges = RecordPendingEdgeOps()

                    val save = RecordBeforeSaveState(name, description, ownerId)
                    val changedSave = save.setName("changed").unsetDescription().setOwnerId(2L)
                    check(changedSave.name == FieldPatch.Set("changed"))
                    check(changedSave.description === FieldPatch.Unset)
                    check(changedSave.ownerId == FieldPatch.Set(2L))
                    val clearedSave = changedSave.unsetName().setDescription(null).unsetOwnerId()
                    check(clearedSave.name === FieldPatch.Unset)
                    check(clearedSave.description == FieldPatch.Set<String?>(null))
                    check(clearedSave.ownerId === FieldPatch.Unset)
                    check(save.setName(null).name == FieldPatch.Set<String?>(null))
                    check(save.name === name && save.description === description && save.ownerId === ownerId)
                    check(save.setName("changed").description === description)
                    check(save.unsetName().ownerId === ownerId)

                    val create = RecordBeforeCreateState(
                        client, viewerContext, name, description, createdAt, ownerId,
                    )
                    val changedCreate = create.setName("created").setCreatedAt(null).unsetOwnerId()
                    check(changedCreate.name == FieldPatch.Set("created"))
                    check(changedCreate.createdAt == FieldPatch.Set<Instant?>(null))
                    check(changedCreate.ownerId === FieldPatch.Unset)
                    check(changedCreate.description === description)
                    check(changedCreate.client === client && changedCreate.viewerContext === viewerContext)
                    val resetCreate = changedCreate.unsetCreatedAt()
                    check(resetCreate.createdAt === FieldPatch.Unset)
                    check(resetCreate.name === changedCreate.name)
                    check(resetCreate.client === client && resetCreate.viewerContext === viewerContext)
                    check(create.name === name && create.description === description)
                    check(create.createdAt === createdAt && create.ownerId === ownerId)

                    val update = RecordBeforeUpdateState(
                        client, viewerContext, before, pendingEdges, name, description, ownerId,
                    )
                    val changedUpdate = update.setName("updated").setDescription(null).unsetOwnerId()
                    check(changedUpdate.name == FieldPatch.Set("updated"))
                    check(changedUpdate.description == FieldPatch.Set<String?>(null))
                    check(changedUpdate.ownerId === FieldPatch.Unset)
                    check(changedUpdate.client === client && changedUpdate.viewerContext === viewerContext)
                    check(changedUpdate.before === before && changedUpdate.pendingEdges === pendingEdges)
                    val resetUpdate = changedUpdate.unsetName().unsetDescription().setOwnerId(3L)
                    check(resetUpdate.name === FieldPatch.Unset && resetUpdate.description === FieldPatch.Unset)
                    check(resetUpdate.ownerId == FieldPatch.Set(3L))
                    check(resetUpdate.client === client && resetUpdate.viewerContext === viewerContext)
                    check(resetUpdate.before === before && resetUpdate.pendingEdges === pendingEdges)
                    check(update.name === name && update.description === description && update.ownerId === ownerId)
                    check(update.setName("updated").description === description)
                    check(update.unsetName().ownerId === ownerId)
                    return "ok"
                }
            }
            """.trimIndent(),
        )
        val result = compileSources(generated + probe)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val probeClass = result.classLoader.loadClass("com.example.ent.HookStateCopyProbe")
        assertEquals("ok", probeClass.getMethod("run").invoke(null))
    }
}
