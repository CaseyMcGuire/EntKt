@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

/** Execute generated preparation against final hook states, without a database. */
class CreateConverterCompileTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
    }

    private class Record : EntSchema("records", clientName = "records") {
        override fun id() = EntId.string()
        val title by string("title").immutable()
        val description by string("description").nullable().default("default-description")
        val published by bool("published").default(false)
        val createdAt by time("created_at").defaultNow().immutable()
        val ownerId by long("owner_id").default(42L)
        val owner by belongsTo<Owner>("owner").field(ownerId)
        val reviewerId by long("reviewer_id").nullable().default(43L)
        val reviewer by belongsTo<Owner>("reviewer").field(reviewerId).nullable()
    }

    @Test
    fun `final hook state preserves explicit IDs immutable fields and unset versus null defaults`() {
        val schemas = listOf(Owner(), Record())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent").generate(schemas.map(::SchemaInput))
        val probe = SourceFile.kotlin(
            "CreatePreparationProbe.kt",
            """
            @file:OptIn(entkt.query.EntktInternal::class)
            package com.example.app

            import com.example.ent.EntClient
            import com.example.ent.RecordCreateConverter
            import com.example.ent.RecordCreateDraft
            import entkt.runtime.driver.DatabaseDriver
            import entkt.runtime.driver.NoopDriver
            import entkt.runtime.privacy.Viewer
            import entkt.runtime.privacy.ViewerContext
            import java.time.Instant

            object CreatePreparationProbe {
                @JvmStatic
                fun run(): String {
                    val client = EntClient(NoopDriver)
                    val converter = RecordCreateConverter(NoopDriver, client.hookClientScopeForInternalUse)
                    val viewer = ViewerContext(Viewer.User(7L))
                    val fixedTime = Instant.parse("2000-01-01T00:00:00Z")
                    val draft = RecordCreateDraft("caller-id").apply {
                        title = "original"
                        description = "provided"
                        published = true
                        createdAt = fixedTime
                        ownerId = 7L
                        reviewerId = 8L
                    }
                    val beforeSave = converter.toBeforeSaveState(draft)
                        .unsetDescription().unsetPublished().unsetOwnerId().unsetReviewerId()
                    val beforeCreate = converter.toBeforeCreateState(viewer, draft, beforeSave)
                    val state = beforeCreate.setTitle("hooked").unsetCreatedAt()

                    check(converter.requiredInputViolations(state).isEmpty())
                    val prepared = converter.resolve(draft, state)
                    check(prepared.values["id"] == "caller-id")
                    check(prepared.candidate.title == "hooked")
                    check(prepared.candidate.description == "default-description")
                    check(prepared.candidate.published == false)
                    check(prepared.candidate.ownerId == 42L)
                    check(prepared.candidate.reviewerId == 43L)
                    check(prepared.candidate.createdAt != fixedTime)
                    check(prepared.values["created_at"] === prepared.candidate.createdAt)

                    val clearedState = state.setDescription(null).setReviewerId(null).setCreatedAt(fixedTime)
                    check(converter.requiredInputViolations(clearedState).isEmpty())
                    val cleared = converter.resolve(draft, clearedState)
                    check(cleared.candidate.description == null && cleared.values["description"] == null)
                    check(cleared.candidate.reviewerId == null && cleared.values["reviewer_id"] == null)
                    check(cleared.candidate.createdAt == fixedTime)

                    check(converter.requiredInputViolations(state.setPublished(null)).single().field == "published")
                    check(converter.requiredInputViolations(state.setOwnerId(null)).single().field == "ownerId")
                    check(converter.requiredInputViolations(state.unsetTitle()).single().field == "title")
                    check(converter.requiredInputViolations(state.setTitle(null)).single().field == "title")

                    // Immutable fields bypass beforeSave but still come from the original draft.
                    val unchanged = converter.resolve(draft, beforeCreate)
                    check(unchanged.candidate.title == "original")
                    check(unchanged.candidate.createdAt == fixedTime)
                    check(draft.title == "original" && draft.description == "provided")
                    check(draft.published == true && draft.createdAt == fixedTime)
                    check(draft.ownerId == 7L && draft.reviewerId == 8L)

                    // The public scalar path also retains the ID while preparing hook replacements.
                    val rows = mutableListOf<Map<String, Any?>>()
                    val driver = object : DatabaseDriver by NoopDriver {
                        override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
                            rows += values
                            return values
                        }
                    }
                    val hookedClient = EntClient(driver) {
                        hooks { records { beforeCreate { it.setTitle("public-hook") } } }
                    }
                    hookedClient.records.create("public-id") { title = "public-draft" }
                        .save(ViewerContext.privacyBypass_DANGEROUS("create-preparation-test"))
                        .getOrThrow()
                    check(rows.single()["id"] == "public-id")
                    check(rows.single()["title"] == "public-hook")
                    return "ok"
                }
            }
            """.trimIndent(),
        )
        val result = KotlinCompilation().apply {
            sources = generated.toCompileTestSources() + probe
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val probeClass = result.classLoader.loadClass("com.example.app.CreatePreparationProbe")
        assertEquals("ok", probeClass.getMethod("run").invoke(null))
    }
}
