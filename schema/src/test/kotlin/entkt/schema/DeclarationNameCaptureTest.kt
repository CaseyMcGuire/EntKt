package entkt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * verification for declaration-name capture's declaration-name capture
 * mechanism. Pins:
 *
 *  - direct public `val` properties capture the property name into
 *    [FieldBuilder.declarationName] and on into [Field.declarationName]
 *  - the column name (`Field.name`) is unaffected — capture only
 *    annotates, never renames
 *  - computed-getter properties are skipped without invoking the
 *    getter (no side-effect field registration)
 *  - declaration name carries the Kotlin val name, not the
 *    `toCamelCase(column)`-derived one — that's the whole point
 */
class DeclarationNameCaptureTest {

    private fun finalized(schema: EntSchema): EntSchema {
        schema.finalize(mapOf(schema::class to schema))
        return schema
    }

    private fun fieldByColumn(schema: EntSchema, columnName: String): Field =
        schema.fields().single { it.name == columnName }

    @Test
    fun `direct public val captures the property name as declaration name`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val writer = uuid("author_id")    // val name diverges from column
            val title = string("title")        // val name matches toCamelCase(column)
        }
        val schema = finalized(Post())

        assertEquals("writer", fieldByColumn(schema, "author_id").declarationName)
        assertEquals("title", fieldByColumn(schema, "title").declarationName)
    }

    @Test
    fun `column name is unchanged — capture only annotates`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val writer = uuid("author_id")
        }
        val schema = finalized(Post())

        val f = fieldByColumn(schema, "author_id")
        assertEquals("author_id", f.name, "Field.name still tracks the storage column")
        assertEquals("writer", f.declarationName, "Field.declarationName tracks the Kotlin val")
    }

    @Test
    fun `non-finalized schemas have null declaration names`() {
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val writer = uuid("author_id")
        }
        val schema = Post()  // intentionally not finalized

        val f = fieldByColumn(schema, "author_id")
        assertNull(f.declarationName, "capture runs in finalize(); pre-finalize fields have null declarationName")
    }

    @Test
    fun `chained modifiers preserve identity so capture still finds the builder`() {
        // `string("x").nullable().unique()` returns the same
        // FieldBuilder instance (modifier methods return `self()`).
        // Capture is identity-based, so the chain still works.
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val maybeAlias = string("alt_name").nullable().unique()
        }
        val schema = finalized(Post())
        assertEquals("maybeAlias", fieldByColumn(schema, "alt_name").declarationName)
    }

    @Test
    fun `computed getter is skipped without invoking the getter`() {
        // The Schema's `init`-time field count includes only the
        // single direct val. After finalize, a computed-getter that
        // would call `string(...)` if invoked must NOT have fired,
        // so the field count stays at 1 — proving the capture pass
        // didn't side-effect-register an extra field.
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val title = string("title")

            // Computed-getter: backing field is null. If capture
            // invoked the getter, this would run `string("dynamic")`
            // and register a second field on the schema.
            @Suppress("unused")
            val dynamic get() = string("dynamic")
        }
        val schema = finalized(Post())

        val fields = schema.fields()
        assertEquals(1, fields.size, "computed getter must NOT register an extra field via capture")
        assertEquals("title", fields.single().name)
        assertNull(
            fields.single().let { if (it.name == "dynamic") it.declarationName else null },
            "no field named 'dynamic' should exist",
        )
    }

    @Test
    fun `delegated property is skipped (javaField is null on delegates)`() {
        // `val x by lazy { ... }` has `javaField == null` for the
        // property itself (the backing storage lives in a
        // synthetic field for the Lazy delegate). The capture
        // pass skips it; the FieldBuilder produced inside lazy{}
        // is also not registered with the schema until the lazy
        // is forced, so it never appears in _fields either way.
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            val title = string("title")

            @Suppress("unused")
            val lazyField by lazy { string("lazy_col") }
        }
        val schema = finalized(Post())

        // The lazy delegate hasn't been touched, so its underlying
        // FieldBuilder was never created — only `title` exists.
        // The point of this test is that capture doesn't crash on
        // delegated properties and doesn't force the lazy.
        val fields = schema.fields()
        assertTrue(fields.any { it.name == "title" })
        assertTrue(fields.none { it.name == "lazy_col" }, "lazy delegate must not be forced by capture")
    }

    @Test
    fun `private val is skipped — declaration name is null on the field`() {
        // Private `val`s aren't part of the public schema contract,
        // so V1 capture scopes to public properties only. The
        // FieldBuilder is still registered with the schema (it's
        // a real field), but declarationName stays null because
        // the property isn't captured.
        class Post : EntSchema("posts") {
            override fun id() = EntId.long()
            private val hidden = string("hidden_col")
            init { @Suppress("UNUSED_EXPRESSION") hidden }  // suppress unused warning
        }
        val schema = finalized(Post())

        assertNull(
            fieldByColumn(schema, "hidden_col").declarationName,
            "non-public properties don't capture (V1 scope rule)",
        )
    }
}
