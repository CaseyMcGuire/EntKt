package entkt.postgres

import entkt.migrations.TypeMapper
import entkt.runtime.IdStrategy
import entkt.schema.FieldType

/**
 * Maps [FieldType] values to PostgreSQL SQL type strings. Also
 * canonicalizes introspected type names (e.g. `int4` → `integer`) so
 * the differ can compare types reliably.
 */
class PostgresTypeMapper : TypeMapper {

    companion object {
        /** Postgres NAMEDATALEN - 1. */
        const val MAX_IDENTIFIER_BYTES = 63
        /** underscore + 8 hex chars. */
        const val HASH_SUFFIX_LEN = 9
    }

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

    /**
     * Postgres truncates identifiers to NAMEDATALEN - 1 (63 bytes).
     * When truncation is needed, the tail is replaced with a hash of
     * the full name to avoid collisions.
     */
    override fun normalizeIdentifier(name: String): String {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_IDENTIFIER_BYTES) return name
        // Walk backwards to avoid splitting a multibyte UTF-8 character
        var prefixLen = MAX_IDENTIFIER_BYTES - HASH_SUFFIX_LEN
        while (prefixLen > 0 && (bytes[prefixLen].toInt() and 0xC0) == 0x80) prefixLen--
        val prefix = String(bytes, 0, prefixLen, Charsets.UTF_8)
        val hash = name.hashCode().toUInt().toString(16).padStart(8, '0')
        return "${prefix}_$hash"
    }

    override fun canonicalize(rawSqlType: String): String = when (rawSqlType.lowercase()) {
        "int4", "int", "integer" -> "integer"
        "int8", "bigint" -> "bigint"
        "int2", "smallint" -> "smallint"
        "serial", "serial4" -> "serial"
        "bigserial", "serial8" -> "bigserial"
        "float4", "real" -> "real"
        "float8", "double precision" -> "double precision"
        "bool", "boolean" -> "boolean"
        "text", "character varying", "varchar" -> "text"
        "timestamptz", "timestamp with time zone" -> "timestamptz"
        "timestamp", "timestamp without time zone" -> "timestamp"
        "uuid" -> "uuid"
        "bytea" -> "bytea"
        else -> rawSqlType.lowercase()
    }
}
