package entkt.viewer.html

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.schema.ColumnStorage
import entkt.schema.FieldType

/**
 * The column's underlying database type, for schema pages. Native columns
 * carry their exact SQL type in metadata (`vector(3)`); everything else is
 * the canonical Postgres mapping (mirrors `PostgresTypeMapper`), with
 * serial/bigserial for auto-increment primary keys.
 */
internal fun dbDisplayType(schema: EntitySchema, column: ColumnMetadata): String {
    (column.storage as? ColumnStorage.Native)?.let { return it.sqlType }
    if (column.primaryKey) {
        when (schema.idStrategy) {
            IdStrategy.AUTO_INT -> return "serial"
            IdStrategy.AUTO_LONG -> return "bigserial"
            else -> {}
        }
    }
    return when (column.type) {
        FieldType.STRING, FieldType.TEXT, FieldType.ENUM -> "text"
        FieldType.BOOL -> "boolean"
        FieldType.INT -> "integer"
        FieldType.LONG -> "bigint"
        FieldType.FLOAT -> "real"
        FieldType.DOUBLE -> "double precision"
        FieldType.TIME -> "timestamptz"
        FieldType.UUID -> "uuid"
        FieldType.BYTES -> "bytea"
        FieldType.JSON -> "jsonb"
        FieldType.PGVECTOR -> "vector"
    }
}
