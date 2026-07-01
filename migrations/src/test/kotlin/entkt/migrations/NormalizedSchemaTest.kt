package entkt.migrations

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.IndexMetadata
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(normalizeDefault("1.5"), normalizeDefault("1.5::double precision"))
        assertEquals(normalizeDefault("-5"), normalizeDefault("(-5)"))
        assertEquals("now()", normalizeDefault("now()"))
        assertNull(normalizeDefault(null))
    }

    @Test
    fun `normalizeDefault preserves colons inside string literals`() {
        // A `::` inside a string value must not be stripped as a cast.
        assertEquals("a::b", normalizeDefault("'a::b'"))
        // ...and a real cast on such a literal still reconciles.
        assertEquals(normalizeDefault("'a::b'"), normalizeDefault("'a::b'::text"))
        // ...but two genuinely different values stay distinct (no masking).
        assertNotEquals(normalizeDefault("'a::b'"), normalizeDefault("'a::c'"))
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
        assertEquals("author_id", table.foreignKeys[0].column)
        assertEquals("users", table.foreignKeys[0].targetTable)
        assertEquals("id", table.foreignKeys[0].targetColumn)
    }
}
