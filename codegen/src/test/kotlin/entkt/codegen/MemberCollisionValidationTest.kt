package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 5 — end-to-end coverage for RFC 07's
 * generated-member-name collision detection. Exercises
 * `SchemaInspector.validate` over schemas that should and should
 * not produce a member-collision diagnostic. Test cases trace
 * back to the RFC's §"Test Requirements" list; each case names
 * which bullet it satisfies.
 *
 * Negative-test pattern: schemas that today pass the existing
 * column-collision check (which fires during finalize()) but
 * trip the new member-collision check.
 */
class MemberCollisionValidationTest {

    private fun validate(vararg schemas: Pair<String, EntSchema>): List<String> {
        val inputs = schemas.map { SchemaInput(it.first, it.second) }
        val result = SchemaInspector.validate(inputs)
        return result.errors
    }

    private fun assertCollision(
        errors: List<String>,
        artifact: String,
        memberName: String,
        message: String = "expected a $artifact.$memberName collision",
    ) {
        val match = errors.firstOrNull { it.contains(artifact) && it.contains("'$memberName'") }
        if (match == null) {
            fail("$message\nGot errors:\n${errors.joinToString("\n") { "  - $it" }}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // RFC test bullet #4: field vs. generated unset{Property}() collision
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `field 'unset_name' collides with the unset method generated for field 'name'`() {
        class S : EntSchema("s") {
            override fun id() = EntId.long()
            val name = string("name")
            val unsetName = bool("unset_name")
        }

        val errors = validate("S" to S())
        // The clash lands on the update mutation view (where unset
        // methods live) and on the update builder.
        assertCollision(errors, "SUpdateMutationView", "unsetName")
    }

    // ──────────────────────────────────────────────────────────────
    // RFC test bullet #10: field named `copy` colliding with
    // data-class synthesized `copy`.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `field 'copy' collides with the data-class synthesized copy()`() {
        class S : EntSchema("s") {
            override fun id() = EntId.long()
            val copy = string("copy")
        }

        val errors = validate("S" to S())
        assertCollision(errors, "S", "copy")
        // Framework-member hint should be present since `copy`
        // is data-class synthesized.
        val matching = errors.first { "S " in it && "'copy'" in it }
        assertTrue("framework member" in matching, "framework hint expected: $matching")
    }

    // ──────────────────────────────────────────────────────────────
    // RFC test bullet #11: field that would generate `component1`
    // colliding with the synthesized component function.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `field 'component1' collides with the data-class synthesized component1()`() {
        class S : EntSchema("s") {
            override fun id() = EntId.long()
            val component1 = string("component1")
            // Some filler to ensure constructor arity reaches >= 1.
            val title = string("title")
        }

        val errors = validate("S" to S())
        assertCollision(errors, "S", "component1")
    }

    // ──────────────────────────────────────────────────────────────
    // RFC test bullet #8: field named `edges` colliding with entity `edges`.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `field 'edges' collides with the fixed entity 'edges' accessor`() {
        class S : EntSchema("s") {
            override fun id() = EntId.long()
            val edges = string("edges")
        }

        val errors = validate("S" to S())
        assertCollision(errors, "S", "edges")
    }

    // ──────────────────────────────────────────────────────────────
    // RFC test bullet #7: field whose generated name matches a
    // fixed builder member (`saveOrError`).
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `field 'save_or_error' collides with the fixed builder saveOrError on Create and Update`() {
        class S : EntSchema("s") {
            override fun id() = EntId.long()
            val saveOrError = bool("save_or_error")
        }

        val errors = validate("S" to S())
        // Lands on both SCreate (immutable-or-mutable-doesn't-matter,
        // both have the setter) and SUpdate.
        assertCollision(errors, "SCreate", "saveOrError")
        assertCollision(errors, "SUpdate", "saveOrError")
    }

    // ──────────────────────────────────────────────────────────────
    // RFC test bullet #9: companion-vs-instance disambiguation —
    // a field named `from_row` should NOT collide with the
    // companion `fromRow` because they live on different artifacts.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `field 'from_row' does NOT collide with the companion 'fromRow' — different artifacts`() {
        class S : EntSchema("s") {
            override fun id() = EntId.long()
            val fromRow = string("from_row")
        }

        val errors = validate("S" to S())
        // No collision diagnostic involving `fromRow` because
        // the entity has no `fromRow` member (it lives on the
        // companion artifact `S.Companion`).
        assertTrue(
            errors.none { it.contains("'fromRow'") && it.contains("S.Companion") },
            "Companion 'fromRow' should not be flagged as colliding with an instance " +
                "property — different artifact namespaces.\nGot:\n${errors.joinToString("\n")}",
        )
    }

    // ──────────────────────────────────────────────────────────────
    // RFC test bullet #12: diagnostic includes the schema name,
    // generated artifact, generated member name, and both sources.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `diagnostic includes schema name, artifact, member name, and both contributing sources`() {
        class S : EntSchema("s") {
            override fun id() = EntId.long()
            val name = string("name")
            val unsetName = bool("unset_name")
        }

        val errors = validate("S" to S())
        val diag = errors.first { it.contains("SUpdateMutationView") && it.contains("'unsetName'") }
        assertTrue("Schema 'S'" in diag, "Diagnostic should name the schema: $diag")
        assertTrue("SUpdateMutationView" in diag, "Diagnostic should name the artifact: $diag")
        assertTrue("'unsetName'" in diag, "Diagnostic should name the member: $diag")
        assertTrue(
            "field 'unset_name'" in diag,
            "Diagnostic should reference the user-declared field: $diag",
        )
        assertTrue(
            "unset method for field 'name'" in diag,
            "Diagnostic should reference the generated unset method source: $diag",
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Negative test: existing valid schemas should not raise false
    // positives. Mirrors the RFC's acceptance criterion
    // "Existing valid schemas continue to pass without renaming."
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `clean schema with scalars + FK + edges produces no collisions`() {
        class Author : EntSchema("authors") {
            override fun id() = EntId.long()
            val name = string("name")
        }
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val title = string("title")
            val createdAt = time("created_at").immutable()
            val author = belongsTo<Author>("author")
        }

        val errors = validate("Author" to Author(), "Post" to Post())
        assertEquals(emptyList(), errors)
    }
}
