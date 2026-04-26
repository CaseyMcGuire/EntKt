package entkt.migrations

import entkt.runtime.ColumnMetadata
import entkt.runtime.EntitySchema
import entkt.runtime.ForeignKeyRef
import entkt.runtime.IdStrategy
import entkt.runtime.IndexMetadata
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizedSchemaTest {

    private val typeMapper = object : TypeMapper {
        override fun sqlTypeFor(fieldType: FieldType, isPrimaryKey: Boolean, idStrategy: IdStrategy): String {
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
