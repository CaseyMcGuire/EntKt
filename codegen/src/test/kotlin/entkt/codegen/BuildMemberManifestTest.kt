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
                "GeneratedEntityMapping" to GeneratedMemberKind.NESTED_TYPE,
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

        // Mutation interface — just the mutable scalar (no FK on this schema)
        assertEquals(setOf("title"), byArtifact["NotebookMutation"])

        // Create drafts contain mutable state plus assignment inspection.
        assertEquals(
            setOf("title", "assignedFields", "isSet"),
            byArtifact["NotebookCreateDraft"],
        )

        // Update builder — title setter + fixed members including id +
        // consistency, the canonical save/saveAndLoad pair, and the
        // private execution member executeSave. NOT unsetTitle — that
        // lives only on NotebookUpdateMutationView (the hook-facing
        // interface), never on the public builder.
        assertNotNull(byArtifact["NotebookUpdate"])
        assertTrue("title" in byArtifact["NotebookUpdate"]!!)
        assertTrue("id" in byArtifact["NotebookUpdate"]!!)
        assertTrue("save" in byArtifact["NotebookUpdate"]!!)
        assertTrue("saveAndLoad" in byArtifact["NotebookUpdate"]!!)
        assertTrue("executeSave" in byArtifact["NotebookUpdate"]!!)
        assertTrue("consistency" in byArtifact["NotebookUpdate"]!!)
        assertTrue("dirtyFields" in byArtifact["NotebookUpdate"]!!)
        assertTrue(
            "unsetTitle" !in byArtifact["NotebookUpdate"]!!,
            "unset methods must NOT appear on the public Update builder; they live on UpdateMutationView only",
        )

        // Update mutation view — title + unsetTitle + pendingEdges.
        // pendingEdges is unconditionally emitted by MutationGenerator
        // even on schemas without helper-eligible M2M (the property
        // exists as an empty-shape `${name}PendingEdgeOps`), so the
        // manifest must register it unconditionally too — otherwise
        // a `val pendingEdges = string(...)` on a non-M2M schema
        // slips past the collision check.
        assertNotNull(byArtifact["NotebookUpdateMutationView"])
        assertTrue("title" in byArtifact["NotebookUpdateMutationView"]!!)
        assertTrue("unsetTitle" in byArtifact["NotebookUpdateMutationView"]!!)
        assertTrue("pendingEdges" in byArtifact["NotebookUpdateMutationView"]!!)
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

        // Mutation interface: only mutable scalars + mutable FKs.
        // createdAt is immutable, so it should NOT be on Mutation.
        val mutation = byArtifact["ArticleMutation"]!!
        assertTrue("title" in mutation)
        assertTrue("authorId" in mutation)
        assertTrue("createdAt" !in mutation, "immutable scalar should not appear on Mutation")

        // Create view: adds the immutable createdAt (create-only writable).
        val createView = byArtifact["ArticleCreateMutationView"]!!
        assertTrue("createdAt" in createView)

        // Update mutation view: title + unsetTitle, authorId + unsetAuthorId.
        // createdAt absent (immutable).
        val updateView = byArtifact["ArticleUpdateMutationView"]!!
        assertTrue("title" in updateView)
        assertTrue("unsetTitle" in updateView)
        assertTrue("authorId" in updateView)
        assertTrue("unsetAuthorId" in updateView)
        assertTrue("createdAt" !in updateView)
    }

    // Helper-eligible M2M coverage is exercised end-to-end in
    // with real `throughLink` schemas — synthesizing a
    // `HelperEligibleM2M` here would require constructing a fake
    // `Edge` with full junction metadata, which the real edge
    // resolver builds during validate().
}
