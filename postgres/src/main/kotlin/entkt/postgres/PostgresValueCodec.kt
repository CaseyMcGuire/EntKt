package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.schema.ColumnStorage
import entkt.schema.FieldType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Converts values between Kotlin and JDBC for every [FieldType] the Postgres
 * driver supports: statement parameter binding and `ResultSet` row/column
 * decoding. Typed JSON text conversion is delegated to the driver's
 * configured [jsonCodec]; this class owns only the JDBC-side handling
 * (SQL NULL, the erased type check, the `jsonb` PGobject wrapping).
 */
internal class PostgresValueCodec(
    private val jsonCodec: entkt.runtime.driver.JsonColumnCodec,
) {

    /**
     * Bind a column value, first validating any native-storage constraint the
     * generic [bind] can't enforce (it sees only [FieldType], not the column's
     * [ColumnStorage]). This keeps a raw `driver.insert/update` with a
     * wrong-dimension vector from relying on Postgres' `vector(n)` to reject it
     * — the caller gets a field-named entkt error, matching generated writes
     * and distance queries.
     */
    fun bindColumn(stmt: PreparedStatement, idx: Int, schema: EntitySchema, col: String, value: Any?) {
        val column = schema.columns.firstOrNull { it.name == col }
        if (column?.type == FieldType.JSON) {
            bindJson(stmt, idx, schema.table, column, value)
            return
        }
        checkVectorDimensions(column?.storage as? ColumnStorage.Native, value, "Column '${schema.table}.$col'")
        bind(stmt, idx, column?.type, value)
    }

    /**
     * Encode a typed JSON value to a `jsonb` parameter through the configured
     * [jsonCodec]. A null value binds SQL NULL without reaching the codec.
     * Missing metadata (a raw write without registered schema info) and a
     * wrong runtime type both fail with a field-named entkt error rather than
     * reaching Postgres; the (erased) type check stays here because it is
     * codec-independent.
     */
    private fun bindJson(
        stmt: PreparedStatement,
        idx: Int,
        table: String,
        column: ColumnMetadata,
        value: Any?,
    ) {
        if (value == null) {
            stmt.setNull(idx, Types.OTHER)
            return
        }
        val meta = column.json
            ?: error(
                "Cannot write JSON to '$table.${column.name}': the column has no serializer metadata. Use the " +
                    "generated repos or register the schema so typed-JSON metadata is available.",
            )
        if (!meta.klass.isInstance(value)) {
            error(
                "Column '$table.${column.name}' expects JSON of ${meta.typeName}, " +
                    "got ${value::class.qualifiedName}",
            )
        }
        val obj = org.postgresql.util.PGobject()
        obj.type = "jsonb"
        obj.value = jsonCodec.encode(table, column, value)
        stmt.setObject(idx, obj)
    }

    fun bind(stmt: PreparedStatement, idx: Int, type: FieldType?, value: Any?) {
        if (value == null) {
            stmt.setNull(idx, jdbcTypeFor(type))
            return
        }
        when (type) {
            FieldType.STRING, FieldType.TEXT, FieldType.ENUM ->
                stmt.setString(idx, value as String)
            FieldType.BOOL -> stmt.setBoolean(idx, value as Boolean)
            FieldType.INT -> stmt.setInt(idx, (value as Number).toInt())
            FieldType.LONG -> stmt.setLong(idx, (value as Number).toLong())
            FieldType.FLOAT -> stmt.setFloat(idx, (value as Number).toFloat())
            FieldType.DOUBLE -> stmt.setDouble(idx, (value as Number).toDouble())
            FieldType.TIME -> {
                val instant = when (value) {
                    is Instant -> value
                    is OffsetDateTime -> value.toInstant()
                    else -> error("Unsupported TIME value: ${value::class}")
                }
                stmt.setObject(idx, instant.atOffset(ZoneOffset.UTC))
            }
            FieldType.UUID -> stmt.setObject(idx, value as UUID)
            FieldType.BYTES -> stmt.setBytes(idx, value as ByteArray)
            // pgvector: bind as a PGobject of type "vector" with the canonical
            // "[f0,f1,...]" text. Postgres rejects a wrong-dimension literal
            // against a vector(n) column, so this is the defensive backstop to
            // the generated setter's dimension check.
            FieldType.PGVECTOR -> {
                val obj = org.postgresql.util.PGobject()
                obj.type = "vector"
                obj.value = pgVectorLiteral(value as entkt.postgres.vector.PgVector)
                stmt.setObject(idx, obj)
            }
            // JSON encode needs the column's serializer (not just FieldType), so
            // it is handled in bindColumn (via bindJson) before reaching here.
            FieldType.JSON -> error("JSON values are encoded in bindColumn, not bind")
            null -> stmt.setObject(idx, value)
        }
    }

    private fun jdbcTypeFor(type: FieldType?): Int = when (type) {
        FieldType.STRING, FieldType.TEXT, FieldType.ENUM -> Types.VARCHAR
        FieldType.BOOL -> Types.BOOLEAN
        FieldType.INT -> Types.INTEGER
        FieldType.LONG -> Types.BIGINT
        FieldType.FLOAT -> Types.REAL
        FieldType.DOUBLE -> Types.DOUBLE
        FieldType.TIME -> Types.TIMESTAMP_WITH_TIMEZONE
        FieldType.UUID -> Types.OTHER
        FieldType.BYTES -> Types.BINARY
        FieldType.PGVECTOR -> Types.OTHER
        FieldType.JSON -> Types.OTHER
        null -> Types.OTHER
    }

    fun decodeRow(rs: ResultSet, table: String, columns: List<ColumnMetadata>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(columns.size)
        for (col in columns) {
            out[col.name] = decodeColumn(rs, table, col)
        }
        return out
    }

    fun decodeColumn(rs: ResultSet, table: String, col: ColumnMetadata): Any? {
        return when (col.type) {
            FieldType.STRING, FieldType.TEXT, FieldType.ENUM -> rs.getString(col.name)
            FieldType.BOOL -> {
                val v = rs.getBoolean(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.INT -> {
                val v = rs.getInt(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.LONG -> {
                val v = rs.getLong(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.FLOAT -> {
                val v = rs.getFloat(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.DOUBLE -> {
                val v = rs.getDouble(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.TIME ->
                rs.getObject(col.name, OffsetDateTime::class.java)?.toInstant()
            FieldType.UUID -> rs.getObject(col.name, UUID::class.java)
            FieldType.BYTES -> rs.getBytes(col.name)
            // pgvector decodes to its "[f0,f1,...]" text; parse back to PgVector.
            FieldType.PGVECTOR -> rs.getString(col.name)?.let { parsePgVector(it) }
            // JSON: SQL NULL bypasses decode; otherwise decode the jsonb text
            // through the driver's configured codec.
            FieldType.JSON -> rs.getString(col.name)?.let { text ->
                if (col.json == null) error("JSON column '$table.${col.name}' has no serializer metadata")
                jsonCodec.decode(table, col, text)
            }
        }
    }

    private fun pgVectorLiteral(v: entkt.postgres.vector.PgVector): String =
        v.toFloatArray().joinToString(",", "[", "]")

    private fun parsePgVector(text: String): entkt.postgres.vector.PgVector {
        val inner = text.trim().removeSurrounding("[", "]").trim()
        val floats = if (inner.isEmpty()) FloatArray(0)
        else inner.split(",").map { it.trim().toFloat() }.toFloatArray()
        return entkt.postgres.vector.PgVector.of(floats)
    }
}
