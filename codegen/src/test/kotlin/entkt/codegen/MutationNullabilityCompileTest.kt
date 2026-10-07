@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals

class MutationNullabilityCompileTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
    }

    private class Record : EntSchema("records", clientName = "records") {
        override fun id() = EntId.string()
        val title by string("title")
        val count by int("count")
        val total by long("total")
        val enabled by bool("enabled").default(true)
        val ratio by float("ratio")
        val weight by double("weight")
        val date by date("date")
        val timestamp by instant("timestamp")
        val token by uuid("token")
        val day by enum<DayOfWeek>("day")
        val payload by bytes("payload")
        val labels by json<List<String>>("labels")
        val notes by string("notes").nullable().default("default notes")
        val owner by belongsTo<Owner>("owner_id")
        val writer by long("writer_id").default(42L)
        val author by belongsTo<Owner>("writer_id").field(writer)
        val reviewer by belongsTo<Owner>("reviewer_id").nullable()
    }

    private fun compile(body: String) = run {
        val schemas = listOf(Owner(), Record())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent").generate(schemas.map(::SchemaInput))
        compileSources(
            generated.toCompileTestSources() + SourceFile.kotlin(
                "MutationNullability.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)
                package com.example.app

                import com.example.ent.*
                import entkt.runtime.driver.NoopDriver
                import entkt.runtime.mutation.FieldPatch
                import entkt.runtime.privacy.ViewerContext

                $body
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `drafts and every before hook reject null for non-nullable scalars and foreign keys`() {
        val fields = listOf(
            "title", "count", "total", "enabled", "ratio", "weight", "date",
            "timestamp", "token", "day", "payload", "labels", "ownerId", "writer",
        )
        val receivers = listOf(
            "RecordCreateDraft", "RecordUpdateDraft", "RecordBeforeSaveState",
            "RecordBeforeCreateState", "RecordBeforeUpdateState",
        )
        val body = receivers.joinToString("\n") { receiver ->
            val assignments = fields.joinToString("\n") { field ->
                if (receiver.endsWith("Draft")) {
                    "$field = null"
                } else {
                    "set${field.replaceFirstChar { it.uppercaseChar() }}(null)"
                }
            }
            "fun $receiver.rejectNull() {\n$assignments\n}"
        }

        val result = compile(body)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        val errors = result.messages.lineSequence().filter { it.startsWith("e:") }.toList()
        assertEquals(fields.size * receivers.size, errors.size, result.messages)
        assertEquals(errors.size, errors.count { "Null cannot be a value" in it }, result.messages)
    }

    @Test
    fun `nullable expressions cannot enter required create and update assignments`() {
        val result = compile(
            """
            fun use(client: EntClient, title: String?, owner: Long?) {
                client.records.create("record") { this.title = title; ownerId = owner }
                client.records.update("record") { this.title = title; ownerId = owner }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        val errors = result.messages.lineSequence().filter { it.startsWith("e:") }.toList()
        assertEquals(4, errors.size, result.messages)
        assertEquals(4, errors.count { "type mismatch" in it.lowercase() }, result.messages)
    }

    @Test
    fun `typed drafts retain omission defaults explicit nullable clears and hook replacements`() {
        val result = compile(
            """
            fun verifyDrafts() {
                val client = EntClient(NoopDriver)
                val converter = RecordCreateConverter(NoopDriver, client.hookClientScopeForInternalUse)
                val viewer = ViewerContext.privacyBypass_DANGEROUS("nullability test")
                val draft = RecordCreateDraft("record")
                check(!draft.isSet(Record.title) && !draft.isSet(Record.enabled))
                check(runCatching { draft.title }.exceptionOrNull()?.message == "title is not set in this create")
                check(runCatching { draft.enabled }.exceptionOrNull() is IllegalStateException)
                check(runCatching { draft.ownerId }.exceptionOrNull() is IllegalStateException)
                check(runCatching { draft.writer }.exceptionOrNull() is IllegalStateException)
                check(draft.notes == null && !draft.isSet(Record.notes))

                val emptySave = converter.toBeforeSaveState(draft)
                check(emptySave.title === FieldPatch.Unset && emptySave.enabled === FieldPatch.Unset)
                check(emptySave.ownerId === FieldPatch.Unset && emptySave.writer === FieldPatch.Unset)
                val emptyCreate = converter.toBeforeCreateState(viewer, draft, emptySave)
                check(converter.requiredInputViolations(emptyCreate).single().field == "title")

                draft.title = "assigned"
                draft.enabled = false
                draft.ownerId = 7L
                draft.writer = 8L
                draft.notes = null
                draft.reviewerId = null
                val title: String = draft.title
                val enabled: Boolean = draft.enabled
                val owner: Long = draft.ownerId
                check(title == "assigned" && !enabled && owner == 7L && draft.writer == 8L)
                check(draft.isSet(Record.title) && draft.isSet(Record.notes) && draft.isSet(Record.reviewerId))
                val assigned = converter.toBeforeSaveState(draft)
                val titlePatch: FieldPatch<String> = assigned.title
                val ownerPatch: FieldPatch<Long> = assigned.ownerId
                check(titlePatch == FieldPatch.Set("assigned") && ownerPatch == FieldPatch.Set(7L))
                check(assigned.notes == FieldPatch.Set<String?>(null))
                check(assigned.reviewerId == FieldPatch.Set<Long?>(null))
                val unset = assigned.unsetTitle().unsetEnabled().unsetWriter()
                check(unset.title === FieldPatch.Unset && unset.enabled === FieldPatch.Unset)
                check(unset.writer === FieldPatch.Unset)
                check(assigned.setNotes(null).notes == FieldPatch.Set<String?>(null))

                val update = RecordUpdateDraft()
                check(runCatching { update.title }.exceptionOrNull()?.message == "title is not set in this update")
                check(runCatching { update.notes }.exceptionOrNull() is IllegalStateException)
                check(update._buildBeforeSaveState().title === FieldPatch.Unset)
                update.title = "updated"
                update.count = 0
                update.notes = null
                update.ownerId = 9L
                update.reviewerId = null
                val updatedTitle: String = update.title
                check(updatedTitle == "updated" && update.count == 0 && update.notes == null)
                val updateState = update._buildBeforeSaveState()
                check(updateState.title == FieldPatch.Set("updated"))
                check(updateState.count == FieldPatch.Set(0))
                check(updateState.notes == FieldPatch.Set<String?>(null))
                check(updateState.ownerId == FieldPatch.Set(9L))
                check(updateState.reviewerId == FieldPatch.Set<Long?>(null))
                check(updateState.enabled === FieldPatch.Unset)
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        result.classLoader.loadClass("com.example.app.MutationNullabilityKt")
            .getMethod("verifyDrafts").invoke(null)
    }
}
