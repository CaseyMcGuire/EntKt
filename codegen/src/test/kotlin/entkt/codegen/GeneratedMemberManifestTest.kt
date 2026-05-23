package entkt.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1 verification for the RFC 07 manifest model. Pins the
 * grouping contract (`(artifact, name)` regardless of kind),
 * ordering stability, and the single-vs-multiple-source split.
 * Phase 5 covers end-to-end collision detection against real
 * schemas.
 */
class GeneratedMemberManifestTest {

    @Test
    fun `empty manifest reports no collisions`() {
        val manifest = GeneratedMemberManifest("Post")
        assertTrue(manifest.findCollisions().isEmpty())
    }

    @Test
    fun `same name on different artifacts is not a collision`() {
        // Per the RFC §"Artifact Namespaces": a field property
        // can appear on both the entity class and a mutation
        // interface independently. The collision is per-artifact.
        val manifest = GeneratedMemberManifest("Post")
        manifest.add("Post", "title", GeneratedMemberKind.PROPERTY, source = "field 'title'")
        manifest.add("PostUpdate", "title", GeneratedMemberKind.PROPERTY, source = "field 'title' setter")
        assertTrue(manifest.findCollisions().isEmpty())
    }

    @Test
    fun `duplicate (artifact, name) is a collision`() {
        val manifest = GeneratedMemberManifest("Post")
        manifest.add("PostUpdateMutationView", "unsetAuthor", GeneratedMemberKind.FUNCTION, source = "unset for FK 'author'")
        manifest.add("PostUpdateMutationView", "unsetAuthor", GeneratedMemberKind.PROPERTY, source = "field 'unset_author'")

        val collisions = manifest.findCollisions()
        assertEquals(1, collisions.size)
        val c = collisions.single()
        assertEquals("Post", c.schema)
        assertEquals("PostUpdateMutationView", c.artifact)
        assertEquals("unsetAuthor", c.name)
        assertEquals(2, c.sources.size)
    }

    @Test
    fun `property-vs-function collision still triggers (V1 rejects regardless of kind)`() {
        // RFC §"Design": "a property and a function with the same
        // generated name in the same artifact are a collision, even
        // if Kotlin might distinguish some callable forms by
        // signature."
        val manifest = GeneratedMemberManifest("Article")
        manifest.add("Article", "copy", GeneratedMemberKind.PROPERTY, source = "field 'copy'")
        manifest.add("Article", "copy", GeneratedMemberKind.FUNCTION, source = "data-class synthesized copy")
        assertEquals(1, manifest.findCollisions().size)
    }

    @Test
    fun `findCollisions sorts by (artifact, name) for determinism`() {
        val manifest = GeneratedMemberManifest("Post")
        // Add out of order; expect sorted output.
        manifest.add("PostUpdate", "zeta", GeneratedMemberKind.PROPERTY, "a")
        manifest.add("PostUpdate", "zeta", GeneratedMemberKind.FUNCTION, "b")
        manifest.add("PostCreate", "alpha", GeneratedMemberKind.PROPERTY, "a")
        manifest.add("PostCreate", "alpha", GeneratedMemberKind.PROPERTY, "b")
        manifest.add("PostUpdate", "alpha", GeneratedMemberKind.PROPERTY, "a")
        manifest.add("PostUpdate", "alpha", GeneratedMemberKind.PROPERTY, "b")

        val collisions = manifest.findCollisions()
        assertEquals(3, collisions.size)
        // (artifact, name) ordering: PostCreate/alpha, PostUpdate/alpha, PostUpdate/zeta
        assertEquals("PostCreate" to "alpha", collisions[0].artifact to collisions[0].name)
        assertEquals("PostUpdate" to "alpha", collisions[1].artifact to collisions[1].name)
        assertEquals("PostUpdate" to "zeta", collisions[2].artifact to collisions[2].name)
    }

    @Test
    fun `three sources on the same name produce one collision with all three listed`() {
        // Sanity check that the validator's diagnostic gets every
        // contributing source, not just the first pair.
        val manifest = GeneratedMemberManifest("Post")
        manifest.add("PostUpdateMutationView", "unsetName", GeneratedMemberKind.FUNCTION, "unset for field 'name'")
        manifest.add("PostUpdateMutationView", "unsetName", GeneratedMemberKind.FUNCTION, "unset for FK 'name'")
        manifest.add("PostUpdateMutationView", "unsetName", GeneratedMemberKind.PROPERTY, "field 'unset_name'")

        val collisions = manifest.findCollisions()
        assertEquals(1, collisions.size)
        assertEquals(3, collisions.single().sources.size)
    }

    @Test
    fun `snapshot returns every added entry`() {
        val manifest = GeneratedMemberManifest("Post")
        manifest.add("Post", "id", GeneratedMemberKind.PROPERTY, "fixed entity id")
        manifest.add("Post", "edges", GeneratedMemberKind.PROPERTY, "fixed entity edges")
        assertEquals(2, manifest.snapshot().size)
    }
}
