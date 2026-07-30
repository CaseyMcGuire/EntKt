package entkt.migrations

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.IndexMetadata
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NormalizedSchemaTest {

    private enum class Color { RED, GREEN }

    private val typeMapper = object : TypeMapper {
        override fun sqlTypeFor(
            fieldType: FieldType,
            isPrimaryKey: Boolean,
            idStrategy: IdStrategy,
            storage: entkt.schema.ColumnStorage?,
        ): String {
            if (isPrimaryKey) {
                when (idStrategy) {
                    IdStrategy.AUTO_INT -> return "serial"
                    IdStrategy.AUTO_LONG -> return "bigserial"
                    else -> Unit
                }
            }
            return when (fieldType) {
                FieldType.STRING, FieldType.TEXT, FieldType.ENUM -> "text"
                FieldType.BOOL -> "boolean"
                FieldType.INT -> "integer"
                FieldType.LONG -> "bigint"
                FieldType.FLOAT -> "real"
                FieldType.DOUBLE -> "double precision"
                FieldType.TIME -> "timestamptz"
                FieldType.UUID -> "uuid"
                FieldType.BYTES -> "bytea"
                FieldType.PGVECTOR -> "vector" // test fake; real mapping uses storage.sqlType (Phase 4)
                FieldType.JSON -> "jsonb"
            }
        }

        override fun canonicalize(rawSqlType: String): String = rawSqlType
    }

    @Test
    fun `fromEntitySchemas maps columns correctly`() {
        val schema = EntitySchema(
            table = "users",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_INT,
            columns = listOf(
                ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false),
                ColumnMetadata("bio", FieldType.TEXT, nullable = true),
            ),
            edges = emptyMap(),
        )
        val normalized = NormalizedSchema.fromEntitySchemas(listOf(schema), typeMapper)
        val table = normalized.tables["users"]!!

        assertEquals(3, table.columns.size)
        assertEquals("serial", table.columns[0].sqlType) // PK with AUTO_INT
        assertEquals("text", table.columns[1].sqlType)
        assertTrue(table.columns[2].nullable)
    }

    @Test
    fun `single-column unique is normalized into index list`() {
        val schema = EntitySchema(
            table = "users",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_INT,
            columns = listOf(
                ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                ColumnMetadata("email", FieldType.STRING, nullable = false, unique = true),
            ),
            edges = emptyMap(),
        )
        val normalized = NormalizedSchema.fromEntitySchemas(listOf(schema), typeMapper)
        val table = normalized.tables["users"]!!

        assertEquals(1, table.indexes.size)
        assertEquals(listOf("email"), table.indexes[0].columns)
        assertTrue(table.indexes[0].unique)
    }

    @Test
    fun `composite indexes are preserved`() {
        val schema = EntitySchema(
            table = "users",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_INT,
            columns = listOf(
                ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                ColumnMetadata("first_name", FieldType.STRING, nullable = false),
                ColumnMetadata("last_name", FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
            indexes = listOf(
                IndexMetadata(listOf("first_name", "last_name"), unique = true, name = "idx_full_name"),
            ),
        )
        val normalized = NormalizedSchema.fromEntitySchemas(listOf(schema), typeMapper)
        val table = normalized.tables["users"]!!

        assertEquals(1, table.indexes.size)
        assertEquals(listOf("first_name", "last_name"), table.indexes[0].columns)
        assertEquals("idx_full_name", table.indexes[0].name)
    }

    @Test
    fun `fromEntitySchemas formats column defaults`() {
        val schema = EntitySchema(
            table = "items",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_INT,
            columns = listOf(
                ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false, default = "anon"),
                ColumnMetadata("count", FieldType.INT, nullable = false, default = 5),
                ColumnMetadata("active", FieldType.BOOL, nullable = false, default = true),
                ColumnMetadata("color", FieldType.ENUM, nullable = false, default = Color.GREEN),
                ColumnMetadata("created_at", FieldType.TIME, nullable = false, default = "now"),
                ColumnMetadata("bio", FieldType.TEXT, nullable = true),
            ),
            edges = emptyMap(),
        )
        val table = NormalizedSchema.fromEntitySchemas(listOf(schema), typeMapper).tables["items"]!!
        val byName = table.columns.associateBy { it.name }

        assertEquals("'anon'", byName["name"]!!.default)
        assertEquals("5", byName["count"]!!.default)
        assertEquals("true", byName["active"]!!.default)
        assertEquals("'GREEN'", byName["color"]!!.default)
        assertEquals("now()", byName["created_at"]!!.default)
        assertNull(byName["id"]!!.default)
        assertNull(byName["bio"]!!.default)
    }

    @Test
    fun `formatSqlDefault renders each type`() {
        assertEquals("'hi'", formatSqlDefault(FieldType.STRING, "hi"))
        assertEquals("'O''Brien'", formatSqlDefault(FieldType.TEXT, "O'Brien"))
        assertEquals("true", formatSqlDefault(FieldType.BOOL, true))
        assertEquals("42", formatSqlDefault(FieldType.INT, 42))
        assertEquals("100", formatSqlDefault(FieldType.LONG, 100L))
        assertEquals("1.5", formatSqlDefault(FieldType.DOUBLE, 1.5))
        assertEquals("'GREEN'", formatSqlDefault(FieldType.ENUM, Color.GREEN))
        assertEquals("now()", formatSqlDefault(FieldType.TIME, "now"))
        assertEquals("2.5", formatSqlDefault(FieldType.FLOAT, 2.5f))
        assertNull(formatSqlDefault(FieldType.INT, null))
    }

    @Test
    fun `normalizeDefault reconciles cast quoting and parens`() {
        assertEquals(normalizeDefault("'active'"), normalizeDefault("'active'::text"))
        assertEquals(normalizeDefault("5"), normalizeDefault("'5'::bigint"))
        assertEquals(normalizeDefault("-5"), normalizeDefault("'-5'::integer"))
        assertEquals(normalizeDefault("1.5"), normalizeDefault("1.5::double precision"))
        assertEquals(normalizeDefault("-5"), normalizeDefault("(-5)"))
        assertEquals(normalizeDefault("''"), normalizeDefault("''::text"))
        assertEquals("now()", normalizeDefault("now()"))
        assertNull(normalizeDefault(null))
    }

    @Test
    fun `normalizeDefault keeps a literal distinct from an expression of the same spelling`() {
        // A text column defaulted to the STRING 'now()' stores that
        // string on every insert; now()::text stores the current
        // timestamp. Postgres reports them as 'now()'::text vs
        // (now())::text — the quoting is the distinction and must
        // survive normalization.
        assertNotEquals(normalizeDefault("'now()'"), normalizeDefault("now()"))
        assertNotEquals(normalizeDefault("'now()'::text"), normalizeDefault("(now())::text"))
        // The literal still reconciles with its own decorated form.
        assertEquals(normalizeDefault("'now()'"), normalizeDefault("'now()'::text"))
    }

    @Test
    fun `normalizeDefault keeps casts on expression defaults`() {
        // (now())::date stores midnight where now() stores the current
        // timestamp — an expression cast changes what the default
        // produces and is part of its identity.
        assertNotEquals(normalizeDefault("now()"), normalizeDefault("(now())::date"))
        assertNotEquals(normalizeDefault("now()"), normalizeDefault("(now())::text"))
        // Both spellings of the same cast expression still reconcile,
        // including chains, canonicalized innermost-first.
        assertEquals(normalizeDefault("now()::date"), normalizeDefault("(now())::date"))
        assertEquals("now()::date::text", normalizeDefault("((now())::date)::text"))
        // Numeric constants keep their decoration stripped — including
        // a textual cast, which produces the same value the DSL's
        // quoted form does: (5)::text, '5', and 5 are one default.
        assertEquals(normalizeDefault("1.5"), normalizeDefault("(1.5)::double precision"))
        assertEquals(normalizeDefault("'5'"), normalizeDefault("(5)::text"))
        // A typmod cast changes a numeric constant's value and stays.
        assertNotEquals(normalizeDefault("1.55"), normalizeDefault("(1.55)::numeric(2,1)"))
    }

    @Test
    fun `normalizeDefault is a fixed point across cast spellings`() {
        // ('x'::text) is the quoted-literal shape spelled with parens;
        // both must reach the same key, and re-normalizing must change
        // nothing (snapshots re-feed normalized output).
        assertEquals(normalizeDefault("'x'::text"), normalizeDefault("('x'::text)"))
        for (input in listOf("('x'::text)", "(now())::date", "((now())::date)::text", "'5'::bigint", "(-5)")) {
            val once = normalizeDefault(input)
            assertEquals(once, normalizeDefault(once), "not a fixed point for $input")
        }
    }

    @Test
    fun `normalizeDefault preserves colons inside string literals`() {
        // A `::` inside a string value must not be stripped as a cast.
        assertEquals("'a::b'", normalizeDefault("'a::b'"))
        // ...and a real cast on such a literal still reconciles.
        assertEquals(normalizeDefault("'a::b'"), normalizeDefault("'a::b'::text"))
        // ...but two genuinely different values stay distinct (no masking).
        assertNotEquals(normalizeDefault("'a::b'"), normalizeDefault("'a::c'"))
    }

    @Test
    fun `fromEntitySchemas rejects identifiers PostgreSQL would truncate`() {
        // Backstop for programmatic FlywayMigrationWorkflow callers
        // that skip SchemaInspector.validate: a name the server would
        // silently truncate must never enter the pipeline.
        val longName = "this_name_is_deliberately_far_too_long_to_survive_the_postgres_identifier_limit"
        val longTable = EntitySchema(
            table = longName,
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_INT,
            columns = listOf(ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true)),
            edges = emptyMap(),
        )
        val e = assertFailsWith<IllegalArgumentException> {
            NormalizedSchema.fromEntitySchemas(listOf(longTable), typeMapper)
        }
        assertContains(e.message ?: "", "truncates")

        val longColumn = EntitySchema(
            table = "long_columns",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_INT,
            columns = listOf(
                ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                ColumnMetadata(longName, FieldType.TEXT, nullable = true),
            ),
            edges = emptyMap(),
        )
        assertFailsWith<IllegalArgumentException> {
            NormalizedSchema.fromEntitySchemas(listOf(longColumn), typeMapper)
        }
    }

    @Test
    fun `foreign keys are extracted from column references`() {
        val schema = EntitySchema(
            table = "posts",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_INT,
            columns = listOf(
                ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "author_id", FieldType.INT, nullable = false,
                    references = ForeignKeyRef("users", "id"),
                ),
            ),
            edges = emptyMap(),
        )
        val normalized = NormalizedSchema.fromEntitySchemas(listOf(schema), typeMapper)
        val table = normalized.tables["posts"]!!

        assertEquals(1, table.foreignKeys.size)
        assertEquals(listOf("author_id"), table.foreignKeys[0].columns)
        assertEquals("users", table.foreignKeys[0].targetTable)
        assertEquals(listOf("id"), table.foreignKeys[0].targetColumns)
    }
}
