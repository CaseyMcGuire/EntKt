package entkt.runtime.driver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Stable ids for the JSON mappers entkt ships codecs for. Codegen stamps one
 * of these (or a third-party codec's id) into each JSON column's
 * [JsonColumnMetadata.mapper]; the driver's configured [JsonColumnCodec]
 * advertises its own id, and `register()` requires the two to match.
 *
 * Built-in users should reference these constants everywhere an id is
 * configured; raw strings are the escape hatch for third-party codecs. A
 * typo'd id can't be rejected at codegen time (codegen is open to unknown
 * codecs), but it can never survive startup: no configured codec advertises
 * the typo'd id, so the register() cross-check fails naming both ids.
 */
object JsonMapperIds {
    const val KOTLINX = "kotlinx"
    const val JACKSON = "jackson"
}

/**
 * Converts one typed JSON column's values to/from JSON text. Drivers hold
 * exactly one codec and route every `FieldType.JSON` column through it;
 * which mapper a column's generated code targets is recorded in
 * [JsonColumnMetadata.mapper] and cross-checked against [id] at `register()`.
 *
 * Every method takes the table name because [ColumnMetadata] doesn't carry
 * it — codec errors must name `table.column`, matching the driver's own
 * error contract. Implementations own their mapper's configuration
 * (`Json` / `ObjectMapper`) and its round-trip semantics (absent-vs-null,
 * Kotlin default parameters); entkt just stores the text.
 *
 * SQL NULL never reaches a codec — the driver binds/returns null before
 * encode/decode is called — so [encode]/[decode] deal in non-null values.
 * Every [decode] call must return a value that shares no mutable state with a
 * prior decode result or codec-held cache. [copyValue] additionally makes that
 * detachment requirement explicit for lifecycle-rule snapshots.
 */
interface JsonColumnCodec {
    /** Stable codec id, matched against [JsonColumnMetadata.mapper] at register(). */
    val id: String

    /**
     * Reject a column this codec cannot round-trip, at `register()` time —
     * a clear startup failure instead of a late first-read/write failure.
     * Called only for `FieldType.JSON` columns whose [JsonColumnMetadata.mapper]
     * already matched [id].
     */
    fun validate(table: String, column: ColumnMetadata)

    /** Encode [value] to JSON text. Failures must name `table.column` and the expected type. */
    fun encode(table: String, column: ColumnMetadata, value: Any): String

    /** Decode JSON [text] to the column's Kotlin value. Failures must name `table.column` and the expected type. */
    fun decode(table: String, column: ColumnMetadata, text: String): Any

    /**
     * Return a detached copy of [value] using this codec's exact mapper
     * configuration. The default encode/decode round trip is correct only when
     * [decode] honors its fresh-result contract. A codec that interns or caches
     * decoded objects must override this method and allocate a detached graph.
     */
    fun copyValue(table: String, column: ColumnMetadata, value: Any): Any =
        decode(table, column, encode(table, column, value))
}

/**
 * The default codec: kotlinx.serialization through the column's
 * statically-emitted [JsonColumnMetadata.kotlinxSerializer] and this codec's
 * configured [json] instance. Generated code references the serializer
 * expressions directly, so a type without kotlinx serialization support
 * fails at consumer compile time — the compile-time-safety contract that
 * makes kotlinx the zero-config default.
 */
class KotlinxJsonCodec(
    private val json: Json = Json.Default,
) : JsonColumnCodec {

    override val id: String = JsonMapperIds.KOTLINX

    override fun validate(table: String, column: ColumnMetadata) {
        // The metadata init invariant ties serializer presence to the kotlinx
        // mapper id, and register() checks the id before calling validate —
        // this is a belt-and-braces re-statement with a column-named error.
        val meta = jsonMeta(table, column)
        checkNotNull(meta.kotlinxSerializer) {
            "JSON column '$table.${column.name}' (${meta.typeName}) has no kotlinx serializer in its metadata"
        }
    }

    override fun encode(table: String, column: ColumnMetadata, value: Any): String {
        val meta = jsonMeta(table, column)
        @Suppress("UNCHECKED_CAST")
        val serializer = meta.kotlinxSerializer as KSerializer<Any>
        return try {
            json.encodeToString(serializer, value)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to encode JSON column '$table.${column.name}' as ${meta.typeName} " +
                    "(value is ${value::class.qualifiedName})",
                e,
            )
        }
    }

    override fun decode(table: String, column: ColumnMetadata, text: String): Any {
        val meta = jsonMeta(table, column)
        @Suppress("UNCHECKED_CAST")
        val serializer = meta.kotlinxSerializer as KSerializer<Any>
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode JSON column '$table.${column.name}' as ${meta.typeName}",
                e,
            )
        }
    }

    private fun jsonMeta(table: String, column: ColumnMetadata): JsonColumnMetadata =
        column.json ?: error("JSON column '$table.${column.name}' has no serializer metadata")
}
