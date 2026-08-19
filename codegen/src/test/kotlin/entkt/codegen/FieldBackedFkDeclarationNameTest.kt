package entkt.codegen

import entkt.codegen.metadata.computeEdgeFks
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end coverage for declaration-name capture's field-backed FK declaration-name
 * capture. Exercises both the codegen wiring (`EdgeFk.propertyName`
 * uses the captured declaration name) and the validation diagnostic
 * (schemas whose backing field's declaration name can't be
 * captured are rejected with an actionable message).
 */
class FieldBackedFkDeclarationNameTest {

    private class Target : EntSchema("targets", clientName = "targets") {
        override fun id() = EntId.long()
    }

    private fun finalize(vararg schemas: EntSchema): Map<EntSchema, String> {
        val byClass = schemas.associate { it::class to it }
        schemas.forEach { it.finalize(byClass) }
        return schemas.associateWith { it::class.simpleName!! }
    }

    private fun validate(vararg pairs: Pair<String, EntSchema>): List<String> =
        SchemaInspector.validate(pairs.map { SchemaInput(it.second) }).errors

    // ──────────────────────────────────────────────────────────────
    // FK property name uses the captured declaration name
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `field-backed FK property name comes from the Kotlin val, not the column`() {
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            // Backing column is `author_id`; val name is `writer`.
            // Pre-contract-06 the FK property would have been `authorId`;
            // after declaration-name capture it's `writer`.
            val writer by long("author_id")
            val author by belongsTo<Target>("author").field(writer)
        }
        val target = Target()
        val post = Post()
        val schemaNames = finalize(target, post)

        val fks = computeEdgeFks(post, schemaNames)
        val fk = fks.single { it.edgeName == "author" }
        assertEquals("writer", fk.propertyName, "FK property name follows the Kotlin val")
        assertEquals("author_id", fk.columnName, "FK column name still tracks the storage column")
        assertTrue(fk.isFieldBacked)
    }

    @Test
    fun `implicit FK is unchanged — only field-backed FKs follow declaration names`() {
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            val author by belongsTo<Target>("author") // no .field(handle)
        }
        val target = Target()
        val post = Post()
        val schemaNames = finalize(target, post)

        val fk = computeEdgeFks(post, schemaNames).single { it.edgeName == "author" }
        // No declaration-name capture path applies — implicit FKs
        // keep their synthesized `${edgeName}Id` derivation.
        assertEquals("authorId", fk.propertyName)
        assertEquals("author_id", fk.columnName)
        assertTrue(!fk.isFieldBacked)
    }

    @Test
    fun `val name matching toCamelCase(column) produces the same FK property as before`() {
        // Backwards-compatibility check: schemas where the val name
        // already equals toCamelCase(column) — the common case for
        // existing code — keep their FK API name unchanged.
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            val authorId by long("author_id")
            val author by belongsTo<Target>("author").field(authorId)
        }
        val target = Target()
        val post = Post()
        val schemaNames = finalize(target, post)

        val fk = computeEdgeFks(post, schemaNames).single { it.edgeName == "author" }
        assertEquals("authorId", fk.propertyName, "val authorId + column author_id keeps `authorId` as the FK API")
    }

    @Test
    fun `chained modifiers on the backing val preserve identity for capture`() {
        // `.nullable().unique()` returns the same builder via the
        // self-cast modifier chain, so identity-based capture still
        // matches.
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            val maybeWriter by long("author_id").nullable()
            val author by belongsTo<Target>("author").field(maybeWriter)
        }
        val target = Target()
        val post = Post()
        val schemaNames = finalize(target, post)

        val fk = computeEdgeFks(post, schemaNames).single { it.edgeName == "author" }
        assertEquals("maybeWriter", fk.propertyName)
    }

    // ──────────────────────────────────────────────────────────────
    // diagnostic rejects unqualifying backing-field shapes
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `private backing field is rejected`() {
        // Two shapes, one rule. `private val x = long(...)` registers a
        // field that names nothing; `private val x by long(...)` is
        // refused at binding because a non-public declaration must not
        // create a public generated member.
        val unbound = kotlin.test.assertFailsWith<IllegalStateException> {
            class Post : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                private val hidden = long("author_id")
                val author by belongsTo<Target>("author").field(hidden)
                init { @Suppress("UNUSED_EXPRESSION") hidden }
            }
            finalize(Target(), Post())
        }
        assertTrue(
            "not bound to a Kotlin property" in unbound.message!! && "author_id" in unbound.message!!,
            "expected an unbound-declaration diagnostic; got: ${unbound.message}",
        )

        val nonPublic = kotlin.test.assertFailsWith<IllegalStateException> {
            object : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                private val hidden by long("author_id")
                init { hidden.fieldName }
            }
        }
        assertTrue(
            "must come from a public `val`" in nonPublic.message!!,
            "expected a visibility diagnostic; got: ${nonPublic.message}",
        )
    }

    @Test
    fun `clean schema without explicit field-handle validates fine`() {
        // Sanity check: the declaration-name capture diagnostic must not false-flag
        // schemas that don't use the field-backed form at all.
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            val title by string("title")
            val author by belongsTo<Target>("author")
        }
        val errors = validate("Target" to Target(), "Post" to Post())
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `clean field-backed schema with capturable val validates fine`() {
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            val writer by long("author_id")
            val author by belongsTo<Target>("author").field(writer)
        }
        val errors = validate("Target" to Target(), "Post" to Post())
        assertEquals(emptyList(), errors)
    }

    // ──────────────────────────────────────────────────────────────
    // Every registered builder binds exactly once, to a public `val`
    // declared on the concrete schema class or an included mixin.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `aliasing one builder to a second property is rejected`() {
        // `val alsoWriter = writer` re-points a second property at an
        // already-bound builder. It never binds, so the schema is
        // rejected rather than codegen picking a name by declaration
        // order.
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            class Post : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                val writer by long("author_id")
                val alsoWriter = writer
                init { @Suppress("UNUSED_EXPRESSION") alsoWriter }
            }
            finalize(Target(), Post())
        }
        assertTrue(
            "'alsoWriter'" in ex.message!! && "never bound" in ex.message!!,
            "expected an alias diagnostic naming the second property; got: ${ex.message}",
        )
    }

    @Test
    fun `binding one builder to two delegated properties is rejected`() {
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            object : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                private val shared = long("author_id")
                val writer by shared
                val alsoWriter by shared
            }
        }
        assertTrue(
            "'writer'" in ex.message!! && "'alsoWriter'" in ex.message!!,
            "expected a double-bind diagnostic naming both properties; got: ${ex.message}",
        )
    }

    @Test
    fun `var-backed FK is rejected`() {
        // A delegated `var` does not compile (builders have no
        // `setValue`), so the reachable shape is `var x = long(...)`,
        // which registers without binding.
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            class Post : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                var mutableBacking: entkt.schema.LongFieldBuilder = long("author_id")
                val author by belongsTo<Target>("author").field(mutableBacking)
            }
            finalize(Target(), Post())
        }
        assertTrue(
            "not bound to a Kotlin property" in ex.message!!,
            "expected an unbound-declaration diagnostic; got: ${ex.message}",
        )
    }

    @Test
    fun `inherited backing field is rejected — binding is direct-only`() {
        // The builder binds (provideDelegate runs on the superclass
        // property), so the unbound check passes. What rejects it is
        // that 'inheritedBacking' is not declared on Post itself —
        // V1 binds only the concrete schema class or an included mixin.
        open class BaseSchema(table: String, client: String) : EntSchema(table, client) {
            override fun id() = EntId.long()
            val inheritedBacking by long("author_id")
        }
        class Post : BaseSchema("posts", "posts") {
            val author by belongsTo<Target>("author").field(inheritedBacking)
        }
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            finalize(Target(), Post())
        }
        assertTrue(
            "'inheritedBacking'" in ex.message!! && "is not declared on" in ex.message!!,
            "expected an inherited-declaration diagnostic; got: ${ex.message}",
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Defense in depth: EntGenerator.generate(...) is a direct codegen
    // entry point. It finalizes the schemas it is handed, so binding
    // validation applies even with no SchemaInspector pass ahead of it.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `EntGenerator generate rejects an unbound field-backed FK directly`() {
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            private val hidden = long("author_id")
            val author by belongsTo<Target>("author").field(hidden)
            init { @Suppress("UNUSED_EXPRESSION") hidden }
        }

        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(
                listOf(SchemaInput(Target()), SchemaInput(Post())),
            )
        }
        assertTrue(
            "Post" in ex.message!! && "author_id" in ex.message!! &&
                "not bound to a Kotlin property" in ex.message!!,
            "expected a per-schema binding diagnostic; got: ${ex.message}",
        )
    }

    @Test
    fun `every field carries a declaration name once the schema is finalized`() {
        // The contract codegen relies on: after finalize, no registered
        // field or edge has a null declaration name.
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            val writer by long("author_id")
            val title by string("legacy_title_txt")
            val author by belongsTo<Target>("author").field(writer)
        }
        val post = Post()
        finalize(Target(), post)
        assertTrue(post.fields().all { it.declarationName != null })
        assertTrue(post.edges().all { it.declarationName != null })
        assertEquals(
            "writer",
            post.fields().single { it.name == "author_id" }.declarationName,
        )
    }
}
