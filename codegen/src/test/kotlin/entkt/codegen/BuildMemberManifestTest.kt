package entkt.codegen

import entkt.codegen.manifest.buildMemberManifest
import entkt.codegen.manifest.GeneratedMemberKind
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * smoke test for generated-member collision checks's manifest builder. Verifies that
 * representative schemas produce manifest entries on every V1
 * artifact (entity / companion / mutation interface / create /
 * update / create-view / update-view). The end-to-end collision
 * detection over real schemas lands in.
 */
class BuildMemberManifestTest {

    /** Minimal schema with one mutable scalar field. */
    private class Notebook : EntSchema("notebooks", clientName = "notebooks") {
        override fun id() = EntId.long()
        val title by string("title")
    }

    /** Schema with mutable + immutable scalars + an FK. */
    private class Article : EntSchema("articles", clientName = "articles") {
        override fun id() = EntId.long()
        val createdAt by time("created_at").immutable()
        val title by string("title")
        val author by belongsTo<Author>("author")
    }

    private class Author : EntSchema("authors", clientName = "authors") {
        override fun id() = EntId.long()
        val name by string("name")
    }

    private fun finalized(vararg schemas: EntSchema): Map<EntSchema, String> {
        val byClass = schemas.associate { it::class to it }
        schemas.forEach { it.finalize(byClass) }
        return schemas.associateWith { it::class.simpleName!! }
    }

    @Test
    fun `minimal schema populates every V1 artifact with the expected fixed members`() {
        val schema = Notebook()
        val schemaNames = finalized(schema)

        val manifest = buildMemberManifest("Notebook", schema, schemaNames)
        val entries = manifest.snapshot()
        val byArtifact = entries.groupBy { it.artifact }.mapValues { (_, ms) -> ms.map { it.name }.toSet() }

        // Entity class — id, edges, copy, equals, hashCode, toString,
        // component1..component2 (id + 1 scalar), and the title property.
        assertNotNull(byArtifact["Notebook"])
        assertTrue("id" in byArtifact["Notebook"]!!)
        assertTrue("edges" in byArtifact["Notebook"]!!)
        assertTrue("copy" in byArtifact["Notebook"]!!)
        assertTrue("title" in byArtifact["Notebook"]!!)
        assertTrue("component1" in byArtifact["Notebook"]!!)
        assertTrue("component2" in byArtifact["Notebook"]!!)

        assertEquals(
            mapOf(
                "client" to GeneratedMemberKind.PROPERTY,
                "driver" to GeneratedMemberKind.PROPERTY,
                "entityQuerySource" to GeneratedMemberKind.PROPERTY,
                "orderFields" to GeneratedMemberKind.PROPERTY,
                "predicates" to GeneratedMemberKind.PROPERTY,
                "queryLimit" to GeneratedMemberKind.PROPERTY,
                "queryOffset" to GeneratedMemberKind.PROPERTY,
                "self" to GeneratedMemberKind.PROPERTY,
                "all" to GeneratedMemberKind.FUNCTION,
                "captureEntityQuery" to GeneratedMemberKind.FUNCTION,
                "combinedPredicate" to GeneratedMemberKind.FUNCTION,
                "compileEntityQuery" to GeneratedMemberKind.FUNCTION,
                "firstOrNull" to GeneratedMemberKind.FUNCTION,
                "limit" to GeneratedMemberKind.FUNCTION,
                "offset" to GeneratedMemberKind.FUNCTION,
                "orderBy" to GeneratedMemberKind.FUNCTION,
                "readRootQuery" to GeneratedMemberKind.FUNCTION,
                "setEntityQuerySource" to GeneratedMemberKind.FUNCTION,
                "where" to GeneratedMemberKind.FUNCTION,
            ),
            entries
                .filter { it.artifact == "NotebookQuery" }
                .associate { it.name to it.kind },
        )

        // Companion — fixed (fromRow/TABLE/SCHEMA) plus column refs
        // for id + every scalar field.
        assertEquals(
            setOf("fromRow", "TABLE", "SCHEMA", "id", "title"),
            byArtifact["Notebook.Companion"],
        )

        // Create drafts contain mutable state plus assignment inspection.
        assertEquals(
            setOf("title", "assignedFields", "isSet"),
            byArtifact["NotebookCreateDraft"],
        )

        // Update drafts contain only caller-configurable mutation state.
        // Execution settings and terminals live on the generic runtime
        // PendingUpdateMutation wrapper.
        assertNotNull(byArtifact["NotebookUpdateDraft"])
        assertEquals(setOf("title", "dirtyFields"), byArtifact["NotebookUpdateDraft"])
        assertTrue(
            "unsetTitle" !in byArtifact["NotebookUpdateDraft"]!!,
            "functional hook transformations must not appear on the mutable update draft",
        )

        assertEquals(
            setOf("title", "setTitle", "unsetTitle"),
            byArtifact["NotebookBeforeSaveState"],
        )
        assertEquals(
            setOf("client", "viewerContext", "title", "setTitle", "unsetTitle"),
            byArtifact["NotebookBeforeCreateState"],
        )
        assertEquals(
            setOf(
                "client", "viewerContext", "before", "pendingEdges",
                "title", "setTitle", "unsetTitle",
            ),
            byArtifact["NotebookBeforeUpdateState"],
        )
    }

    @Test
    fun `schema with immutable field and FK exposes them correctly across artifacts`() {
        val author = Author()
        val article = Article()
        val schemaNames = finalized(author, article)

        val manifest = buildMemberManifest("Article", article, schemaNames)
        val byArtifact = manifest.snapshot().groupBy { it.artifact }
            .mapValues { (_, ms) -> ms.map { it.name }.toSet() }

        // Entity class carries scalars AND the implicit FK ("authorId").
        val entity = byArtifact["Article"]!!
        assertTrue("createdAt" in entity)
        assertTrue("title" in entity)
        assertTrue("authorId" in entity, "implicit FK property should land on entity as authorId")

        val beforeCreate = byArtifact["ArticleBeforeCreateState"]!!
        assertTrue("createdAt" in beforeCreate)
        assertTrue("setCreatedAt" in beforeCreate)

        val beforeUpdate = byArtifact["ArticleBeforeUpdateState"]!!
        assertTrue("title" in beforeUpdate)
        assertTrue("unsetTitle" in beforeUpdate)
        assertTrue("authorId" in beforeUpdate)
        assertTrue("unsetAuthorId" in beforeUpdate)
        assertTrue("createdAt" !in beforeUpdate)
    }

    // Helper-eligible M2M coverage is exercised end-to-end in
    // with real `throughLink` schemas — synthesizing a
    // `HelperEligibleM2M` here would require constructing a fake
    // `Edge` with full junction metadata, which the real edge
    // resolver builds during validate().
}
