@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.codegen.metadata.columnMetadataFor
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.resolveEdgeJoin
import entkt.codegen.metadata.resolveM2MEdgeJoin
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class BelongsToColumnTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
        val records by hasMany<Record>("records")
        val featuredRecord by hasOne<Record>("featured_record")
    }

    private class Record : EntSchema("records", clientName = "records") {
        override fun id() = EntId.long()
        val owner by belongsTo<Owner>(column = "owner_ref").inverse(Owner::records)
        val featuredOwner by belongsTo<Owner>("featured_key").nullable().unique().inverse(Owner::featuredRecord)
        val problemLanguage by belongsTo<Owner>("problem_language_id")
        val raw by belongsTo<Owner>("plain")
        val unusual by belongsTo<Owner>("legacy_id_id").nullable()
        val writer by long("writer_key")
        val author by belongsTo<Owner>("writer_key").field(writer)
        val byOwnerAndLanguage by index("idx_owner_language", owner.fk, problemLanguage.fk)
        val tags by manyToMany<Tag>("tags").throughLink<RecordTag>(RecordTag::record, RecordTag::tag)
    }

    private class Tag : EntSchema("tags", clientName = "tags") {
        override fun id() = EntId.uuid()
    }

    private class RecordTag : EntSchema("record_tags", clientName = "recordTags") {
        override fun id() = EntId.long()
        val record by belongsTo<Record>("record_ref").onDelete(OnDelete.CASCADE)
        val tag by belongsTo<Tag>("tag_key").onDelete(OnDelete.CASCADE)
        val byPair by index("uq_record_tag", record.fk, tag.fk).unique()
    }

    private fun schemas(): List<EntSchema> = listOf(Owner(), Record(), Tag(), RecordTag()).also { schemas ->
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
    }

    @Test
    fun `columns indexes and all relationship joins use literal FK storage names`() {
        val schemas = schemas()
        val names = schemas.associateWith { it::class.simpleName!! }
        val owner = schemas.filterIsInstance<Owner>().single()
        val record = schemas.filterIsInstance<Record>().single()
        val expected = mapOf(
            "ownerId" to "owner_ref", "featuredOwnerId" to "featured_key",
            "problemLanguageId" to "problem_language_id", "rawId" to "plain",
            "unusualId" to "legacy_id_id", "writer" to "writer_key",
        )
        assertEquals(expected, computeEdgeFks(record, names).associate { it.propertyName to it.columnName })
        assertEquals(setOf("id") + expected.values, columnMetadataFor(record, names).map { it.name }.toSet())
        assertEquals(listOf("owner_ref", "problem_language_id"), record.indexes().single().fields)

        val owningEdge = record.edges().single { it.declarationName == "owner" }
        assertEquals("owner_ref", resolveEdgeJoin(owningEdge, record)!!.sourceColumn)
        val inverseEdge = owner.edges().single { it.declarationName == "records" }
        assertEquals("owner_ref", resolveEdgeJoin(inverseEdge, owner)!!.targetColumn)
        val uniqueInverseEdge = owner.edges().single { it.declarationName == "featuredRecord" }
        assertEquals("featured_key", resolveEdgeJoin(uniqueInverseEdge, owner)!!.targetColumn)

        val m2m = resolveM2MEdgeJoin(record.edges().single { it.declarationName == "tags" }, record, names)!!
        assertEquals("record_ref", m2m.junctionSourceColumn)
        assertEquals("tag_key", m2m.junctionTargetColumn)

        val explained = SchemaInspector.explain(schemas.map(::SchemaInput)).schemas.single { it.schemaName == "Record" }
        assertEquals(expected, explained.foreignKeys.associate { it.propertyName to it.column })
    }

    @Test
    fun `generated reads writes and named index helpers preserve Kotlin ID names with literal columns`() {
        val generated = EntGenerator("com.example.ent").generate(schemas().map(::SchemaInput))
        val result = KotlinCompilation().apply {
            sources = generated.toCompileTestSources() + SourceFile.kotlin(
                "Application.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)
                package com.example.app
                import com.example.ent.*
                import entkt.runtime.driver.NoopDriver
                import entkt.runtime.privacy.ViewerContext
                import java.util.UUID

                fun exercise() {
                    val client = EntClient(NoopDriver)
                    val converter = RecordCreateConverter(NoopDriver, client.hookClientScopeForInternalUse)
                    val draft = RecordCreateDraft().apply {
                        ownerId = 1L
                        problemLanguageId = 2L
                        rawId = 3L
                        writer = 4L
                    }
                    val viewer = ViewerContext.privacyBypass_DANGEROUS("literal-fk-test")
                    val shared = converter.toBeforeSaveState(draft)
                    val state = converter.toBeforeCreateState(viewer, draft, shared)
                    val prepared = converter.resolve(draft, state)
                    check(prepared.values["owner_ref"] == 1L)
                    check(prepared.values["problem_language_id"] == 2L)
                    check(prepared.values["plain"] == 3L)
                    check(prepared.values["writer_key"] == 4L)
                    check("owner_ref_id" !in prepared.values)
                    check("problem_language_id_id" !in prepared.values)
                    client.records.update(42L) { ownerId = 5L; tags.add(UUID.randomUUID()) }
                    client.records.indexes.byOwnerAndLanguage(ownerId = 1L, problemLanguageId = 2L).query()
                    client.recordTags.indexes.byPair(recordId = 42L, tagId = UUID.randomUUID()).find(viewer)
                    client.records.query().queryOwner()
                    client.owners.query().queryRecords()
                    client.owners.query().queryFeaturedRecord()
                    client.records.query().queryTags()
                }
                """.trimIndent(),
            )
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        result.classLoader.loadClass("com.example.app.ApplicationKt").getMethod("exercise").invoke(null)
        val updates = generated.single { it.name == "RecordUpdateDraft" }.toString()
        assertContains(updates, "\"owner_ref\"")
        assertContains(updates, "\"problem_language_id\"")
    }
}
