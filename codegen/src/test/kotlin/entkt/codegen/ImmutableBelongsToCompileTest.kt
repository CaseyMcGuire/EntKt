@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.squareup.kotlinpoet.TypeSpec
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.metadata.computeEdgeFks
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImmutableBelongsToCompileTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
    }

    private class Record : EntSchema("records", clientName = "records") {
        override fun id() = EntId.string()
        val title by string("title")
        val owner by belongsTo<Owner>("owner_id").immutable()
        val reviewer by belongsTo<Owner>("reviewer_id").immutable().nullable()
        val writer by long("writer_id").default(42L)
        val author by belongsTo<Owner>("writer_id").field(writer).immutable()
        val fixedId by long("fixed_id").immutable()
        val fixed by belongsTo<Owner>("fixed_id").field(fixedId)
        val editor by belongsTo<Owner>("editor_id").nullable()
    }

    private val immutableProperties = listOf("ownerId", "reviewerId", "writer", "fixedId")

    private fun schemas(): List<EntSchema> = listOf(Owner(), Record()).also { schemas ->
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
    }

    @Test
    fun `edge or backing field immutability restricts every generated update surface`() {
        val schemas = schemas()
        val record = schemas.filterIsInstance<Record>().single()
        val names = schemas.associateWith { it::class.simpleName!! }
        val fks = computeEdgeFks(record, names).associateBy { it.propertyName }
        for (name in immutableProperties) {
            assertTrue(fks.getValue(name).immutable, name)
        }
        assertFalse(fks.getValue("editorId").immutable)
        assertEquals("writer_id", fks.getValue("writer").columnName)
        assertEquals(42L, fks.getValue("writer").default)

        val types = EntGenerator("com.example.ent").generate(schemas.map(::SchemaInput))
            .flatMap { it.members.filterIsInstance<TypeSpec>() }
            .associateBy { it.name }
        for (name in immutableProperties) {
            assertContains(types.getValue("RecordCreateDraft").toString(), "var $name: kotlin.Long?")
            assertContains(types.getValue("RecordBeforeCreateState").toString(), "$name: entkt.runtime.mutation.FieldPatch")
            assertContains(types.getValue("RecordUpdateAdapter").toString(), "$name = before.$name")
            for (artifact in listOf("RecordUpdateDraft", "RecordUpdatePatch", "RecordBeforeSaveState", "RecordBeforeUpdateState")) {
                assertFalse(types.getValue(artifact).toString().contains("$name:"), "$artifact exposes $name")
            }
        }
    }

    private fun compile(body: String) = KotlinCompilation().apply {
        sources = EntGenerator("com.example.ent").generate(schemas().map(::SchemaInput)).toCompileTestSources() +
            SourceFile.kotlin(
                "Application.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)
                package com.example.app

                import com.example.ent.*
                import entkt.runtime.driver.NoopDriver
                import entkt.runtime.privacy.ViewerContext

                $body
                """.trimIndent(),
            )
        inheritClassPath = true
        kotlincArguments = listOf("-Xskip-metadata-version-check")
        jvmTarget = "17"
        messageOutputStream = java.io.OutputStream.nullOutputStream()
    }.compile()

    @Test
    fun `create retains immutable FK assignments defaults and beforeCreate replacements`() {
        val result = compile(
            """
            object Probe {
                @JvmStatic
                fun run(): String {
                    val client = EntClient(NoopDriver)
                    val converter = RecordCreateConverter(NoopDriver, client.hookClientScopeForInternalUse)
                    val viewer = ViewerContext.privacyBypass_DANGEROUS("immutable-belongs-to-test")
                    val draft = RecordCreateDraft("record-id").apply {
                        title = "original"
                        ownerId = 7L
                        reviewerId = null
                        fixedId = 8L
                    }
                    val beforeSave = converter.toBeforeSaveState(draft).setTitle("changed")
                    val beforeCreate = converter.toBeforeCreateState(viewer, draft, beforeSave)
                    check(converter.requiredInputViolations(beforeCreate).isEmpty())
                    val prepared = converter.resolve(draft, beforeCreate)
                    check(prepared.values["id"] == "record-id")
                    check(prepared.candidate.title == "changed")
                    check(prepared.candidate.ownerId == 7L && prepared.values["owner_id"] == 7L)
                    check(prepared.candidate.reviewerId == null && prepared.values["reviewer_id"] == null)
                    check(prepared.candidate.writer == 42L && prepared.values["writer_id"] == 42L)
                    check(prepared.candidate.fixedId == 8L)

                    val replaced = converter.resolve(draft, beforeCreate.setOwnerId(9L).setReviewerId(10L).setWriter(11L))
                    check(replaced.candidate.ownerId == 9L)
                    check(replaced.candidate.reviewerId == 10L)
                    check(replaced.candidate.writer == 11L)
                    check(converter.requiredInputViolations(beforeCreate.setOwnerId(null)).single().field == "ownerId")

                    // Other relationships and scalar fields remain writable on update.
                    client.records.update("record-id") { title = "updated"; editorId = 12L }
                    return "ok"
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val probe = result.classLoader.loadClass("com.example.app.Probe")
        assertEquals("ok", probe.getMethod("run").invoke(null))
    }

    @Test
    fun `immutable FK updates and shared or update hook setters do not compile`() {
        val result = compile(
            """
            fun invalid(draft: RecordUpdateDraft, shared: RecordBeforeSaveState, update: RecordBeforeUpdateState) {
                draft.ownerId = 1L
                draft.reviewerId = null
                draft.writer = 2L
                draft.fixedId = 3L
                shared.setOwnerId(1L)
                update.setReviewerId(null)
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in immutableProperties + listOf("setOwnerId", "setReviewerId")) {
            assertContains(result.messages, "Unresolved reference '$name'")
        }
    }

    private class LinkOwner(throughLink: Boolean) : EntSchema("link_owners", clientName = "linkOwners") {
        override fun id() = EntId.long()
        val targets by manyToMany<LinkTarget>("targets").apply {
            if (throughLink) throughLink<Link>(Link::owner, Link::target)
            else throughEntity<Link>(Link::owner, Link::target)
        }
    }

    private class LinkTarget : EntSchema("link_targets", clientName = "linkTargets") {
        override fun id() = EntId.long()
    }

    private class Link(immutableSource: Boolean) : EntSchema("links", clientName = "links") {
        override fun id() = EntId.long()
        val owner by belongsTo<LinkOwner>("owner_id").onDelete(OnDelete.CASCADE).apply {
            if (immutableSource) immutable()
        }
        val target by belongsTo<LinkTarget>("target_id").onDelete(OnDelete.CASCADE).apply {
            if (!immutableSource) immutable()
        }
        val pair = index("uq_links_pair", owner.fk, target.fk).unique()
    }

    @Test
    fun `immutable junction endpoints require throughEntity just like immutable backing fields`() {
        for (immutableSource in listOf(true, false)) {
            val error = assertFailsWith<IllegalStateException> {
                EntGenerator("com.example.ent").generate(
                    listOf(LinkOwner(throughLink = true), LinkTarget(), Link(immutableSource)).map(::SchemaInput),
                )
            }
            assertContains(error.message!!, "${if (immutableSource) "source" else "target"} FK edge")
            assertContains(error.message!!, "is `.immutable()`")
            assertContains(error.message!!, "use throughEntity")

            EntGenerator("com.example.ent").generate(
                listOf(LinkOwner(throughLink = false), LinkTarget(), Link(immutableSource)).map(::SchemaInput),
            )
        }
    }
}
