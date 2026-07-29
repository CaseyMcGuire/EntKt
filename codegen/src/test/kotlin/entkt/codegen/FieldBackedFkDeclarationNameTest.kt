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

    private class Target : EntSchema("targets") {
        override fun id() = EntId.long()
    }

    private fun finalize(vararg schemas: EntSchema): Map<EntSchema, String> {
        val byClass = schemas.associate { it::class to it }
        schemas.forEach { it.finalize(byClass) }
        return schemas.associateWith { it::class.simpleName!! }
    }

    private fun validate(vararg pairs: Pair<String, EntSchema>): List<String> =
        SchemaInspector.validate(pairs.map { SchemaInput(it.first, it.second) }).errors

    // ──────────────────────────────────────────────────────────────
    // FK property name uses the captured declaration name
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `field-backed FK property name comes from the Kotlin val, not the column`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            // Backing column is `author_id`; val name is `writer`.
            // Pre-contract-06 the FK property would have been `authorId`;
            // after declaration-name capture it's `writer`.
            val writer = long("author_id")
            val author = belongsTo<Target>("author").field(writer)
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
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val author = belongsTo<Target>("author") // no .field(handle)
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
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val authorId = long("author_id")
            val author = belongsTo<Target>("author").field(authorId)
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
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val maybeWriter = long("author_id").nullable()
            val author = belongsTo<Target>("author").field(maybeWriter)
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
    fun `private backing field is rejected — V1 only captures public properties`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            private val hidden = long("author_id")
            val author = belongsTo<Target>("author").field(hidden)
            init { @Suppress("UNUSED_EXPRESSION") hidden }
        }

        val errors = validate("Target" to Target(), "Post" to Post())
        val match = errors.firstOrNull {
            it.contains("Post") &&
                it.contains("'author'") &&
                it.contains("author_id") &&
                it.contains("declaration name could not be captured")
        }
        if (match == null) {
            fail("expected declaration-name capture diagnostic for private backing field; got:\n${errors.joinToString("\n") { "  - $it" }}")
        }
        // Diagnostic must suggest the workarounds.
        assertTrue("public val" in match, "diagnostic should mention restructuring to a public val")
        assertTrue("`.field(handle)`" in match, "diagnostic should mention dropping .field(handle) as a fallback")
    }

    @Test
    fun `clean schema without explicit field-handle validates fine`() {
        // Sanity check: the declaration-name capture diagnostic must not false-flag
        // schemas that don't use the field-backed form at all.
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val title = string("title")
            val author = belongsTo<Target>("author")
        }
        val errors = validate("Target" to Target(), "Post" to Post())
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `clean field-backed schema with capturable val validates fine`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val writer = long("author_id")
            val author = belongsTo<Target>("author").field(writer)
        }
        val errors = validate("Target" to Target(), "Post" to Post())
        assertEquals(emptyList(), errors)
    }

    // ──────────────────────────────────────────────────────────────
    // Duplicate-alias rejection (acceptance criterion:
    // "A schema in which two direct properties reference the same
    // FieldBuilder instance fails validateEntSchemas")
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `aliased properties referencing the same builder are rejected`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val writer = long("author_id")
            val alsoWriter = writer  // alias — same FieldBuilder instance
            init { @Suppress("UNUSED_EXPRESSION") alsoWriter }
        }
        val errors = validate("Target" to Target(), "Post" to Post())
        val match = errors.firstOrNull {
            it.contains("Post") &&
                it.contains("'author_id'") &&
                it.contains("'writer'") &&
                it.contains("'alsoWriter'") &&
                it.contains("direct public vals")
        }
        if (match == null) {
            fail("expected duplicate-alias diagnostic; got:\n${errors.joinToString("\n") { "  - $it" }}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Capture scope: var / inherited properties rejected
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `var-backed FK is rejected — only val properties are captured`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            var mutableBacking: entkt.schema.LongFieldBuilder = long("author_id")
            val author = belongsTo<Target>("author").field(mutableBacking)
        }
        val errors = validate("Target" to Target(), "Post" to Post())
        // The backing field exists in _fields (uuid("...") was
        // invoked during init), but the capture pass skips the
        // var property — so declarationName stays null and the
        // declaration-name capture diagnostic fires.
        val match = errors.firstOrNull {
            it.contains("Post") &&
                it.contains("'author'") &&
                it.contains("declaration name could not be captured")
        }
        if (match == null) {
            fail("expected declaration-name capture diagnostic for var-backed FK; got:\n${errors.joinToString("\n") { "  - $it" }}")
        }
    }

    @Test
    fun `inherited backing field is rejected — capture is direct-only`() {
        // `declaredMemberProperties` returns only the concrete
        // class's own properties, not inherited ones. A field
        // declared on a superclass produces a Field on the schema
        // (because `string(...)` registers via the protected DSL
        // helper inherited by Post) but the property itself is on
        // BaseSchema, not Post, so declaredMemberProperties on Post
        // doesn't see it.
        open class BaseSchema(table: String) : EntSchema(table) {
            override fun id() = EntId.long()
            val inheritedBacking = long("author_id")
        }
        class Post : BaseSchema("posts") {
            val author = belongsTo<Target>("author").field(inheritedBacking)
        }
        val errors = validate("Target" to Target(), "Post" to Post())
        val match = errors.firstOrNull {
            it.contains("Post") &&
                it.contains("'author'") &&
                it.contains("declaration name could not be captured")
        }
        if (match == null) {
            fail("expected declaration-name capture diagnostic for inherited backing; got:\n${errors.joinToString("\n") { "  - $it" }}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Defense-in-depth: EntGenerator.generate(...) — the direct
    // codegen entry point — must reject uncaptured backings even
    // when no SchemaInspector.validate(...) pass ran ahead of it.
    // A regression that ever drops the EntGenerator-side hook
    // would let direct callers fall through to
    // computeEdgeFks's toCamelCase(column) fallback silently.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `EntGenerator generate rejects an uncaptured field-backed FK directly`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            // Private val — won't be captured (V1 public-only).
            private val hidden = long("author_id")
            val author = belongsTo<Target>("author").field(hidden)
            init { @Suppress("UNUSED_EXPRESSION") hidden }
        }

        val inputs = listOf(
            SchemaInput("Target", Target()),
            SchemaInput("Post", Post()),
        )
        val generator = EntGenerator("com.example.ent")

        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            generator.generate(inputs)
        }
        // Diagnostic surfaced through the EntGenerator path uses
        // the "Field-backed FK declaration capture failed" header
        // before listing each per-schema error.
        kotlin.test.assertTrue(
            "Field-backed FK declaration capture failed" in ex.message!!,
            "expected EntGenerator-path header in the error message; got: ${ex.message}",
        )
        kotlin.test.assertTrue(
            "Post" in ex.message!! && "'author'" in ex.message!! && "author_id" in ex.message!!,
            "expected per-schema diagnostic naming Post / author / author_id; got: ${ex.message}",
        )
    }

    @Test
    fun `EntGenerator generate rejects a duplicate-alias schema directly`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val writer = long("author_id")
            val alsoWriter = writer
            init { @Suppress("UNUSED_EXPRESSION") alsoWriter }
        }

        val inputs = listOf(
            SchemaInput("Target", Target()),
            SchemaInput("Post", Post()),
        )
        val generator = EntGenerator("com.example.ent")

        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            generator.generate(inputs)
        }
        kotlin.test.assertTrue(
            "Field-backed FK declaration capture failed" in ex.message!!,
            "expected EntGenerator-path header; got: ${ex.message}",
        )
        kotlin.test.assertTrue(
            "direct public vals" in ex.message!! &&
                "'writer'" in ex.message!! &&
                "'alsoWriter'" in ex.message!!,
            "expected duplicate-alias diagnostic naming both vals; got: ${ex.message}",
        )
    }

    @Test
    fun `Field declarationName is null for backing fields whose val is not captured`() {
        // Direct introspection: verify the capture actually
        // leaves declarationName null for private vals (the case
        // rejects).
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            private val hidden = long("author_id")
            init { @Suppress("UNUSED_EXPRESSION") hidden }
        }
        val post = Post()
        post.finalize(mapOf(post::class to post))
        assertNull(post.fields().single { it.name == "author_id" }.declarationName)
    }
}
