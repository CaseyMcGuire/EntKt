package entkt.jackson

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.JsonColumnCodec
import entkt.runtime.driver.JsonColumnMetadata
import entkt.runtime.driver.JsonMapperIds
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.jvm.javaType

/**
 * Jackson-backed [JsonColumnCodec] for typed JSON columns, so a Jackson
 * project can use `json(...)` fields without adopting kotlinx.serialization.
 * Opt in on both sides:
 *
 *  - generate with `jsonMapper = "jackson"` (Gradle: `entkt {
 *    jsonMapper.set("jackson") }`), so the SCHEMA metadata targets this codec
 *    and no kotlinx serializer references are emitted;
 *  - configure the driver with `PostgresDriver(dataSource, jsonCodec =
 *    JacksonJsonCodec(objectMapper))`.
 *
 * The driver's `register()` cross-checks the two and fails at startup on a
 * mismatch.
 *
 * The [objectMapper] is the caller's, configuration included — for Kotlin
 * data classes it must have `jackson-module-kotlin` registered
 * (`jacksonObjectMapper()` / `registerKotlinModule()`). Round-trip semantics
 * (absent-vs-null, Kotlin default parameters, unknown keys) are the
 * mapper's, exactly as the caller configured them.
 *
 * **Tradeoff vs. the kotlinx default:** Jackson is reflective, so there is
 * no compile-time failure for an unmappable type. [validate] preflights what
 * it can at `register()` (the type must construct a Jackson `JavaType` and
 * look deserializable); anything Jackson can only discover at runtime — e.g.
 * a missing Kotlin module — surfaces as a decode/encode failure, wrapped
 * with the table, column, and expected type.
 */
class JacksonJsonCodec(
    private val objectMapper: ObjectMapper,
) : JsonColumnCodec {

    override val id: String = JsonMapperIds.JACKSON

    /**
     * JavaType per column metadata, built once — metadata instances are
     * stable for the lifetime of a registered schema, and encode/decode are
     * the hot paths.
     */
    private val javaTypes = ConcurrentHashMap<JsonColumnMetadata, JavaType>()

    /**
     * Writer pinned to the column's DECLARED type, per column metadata.
     * `writeValueAsString(value)` alone would serialize by the value's
     * runtime class — a raw driver write of `List<String>` against a
     * `json<List<Rect>>` column passes the erased `List::class` check and
     * would persist happily, deferring the failure to a later read. Writing
     * through the declared type makes the wrong-element write fail at encode
     * time with the column named, matching the kotlinx codec's strictness.
     */
    private val writers = ConcurrentHashMap<JsonColumnMetadata, com.fasterxml.jackson.databind.ObjectWriter>()

    override fun validate(table: String, column: ColumnMetadata) {
        val meta = jsonMeta(table, column)
        val javaType = javaTypeFor(table, column, meta)
        check(objectMapper.canDeserialize(javaType)) {
            "JSON column '$table.${column.name}': the configured ObjectMapper cannot deserialize " +
                "${meta.typeName} — is the type visible to Jackson (and jackson-module-kotlin " +
                "registered for Kotlin classes)?"
        }
        check(objectMapper.canSerialize(javaType.rawClass)) {
            "JSON column '$table.${column.name}': the configured ObjectMapper cannot serialize ${meta.typeName}"
        }
    }

    override fun encode(table: String, column: ColumnMetadata, value: Any): String {
        val meta = jsonMeta(table, column)
        val writer = writers.computeIfAbsent(meta) {
            objectMapper.writerFor(javaTypeFor(table, column, meta))
        }
        return try {
            writer.writeValueAsString(value)
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
        return try {
            // Explicit type argument: Kotlin otherwise infers the Java
            // generic T (return-position only) as Void here and inserts a
            // failing checkcast on the decoded value.
            objectMapper.readValue<Any>(text, javaTypeFor(table, column, meta))
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to decode JSON column '$table.${column.name}' as ${meta.typeName}",
                e,
            )
        }
    }

    private fun javaTypeFor(table: String, column: ColumnMetadata, meta: JsonColumnMetadata): JavaType =
        javaTypes.computeIfAbsent(meta) {
            try {
                // KType → java.lang.reflect.Type keeps the type arguments
                // (List<Rect> arrives as a ParameterizedType), so Jackson
                // deserializes typed elements, not maps.
                objectMapper.typeFactory.constructType(it.kType.javaType)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "JSON column '$table.${column.name}': cannot map ${it.typeName} to a Jackson JavaType",
                    e,
                )
            }
        }

    private fun jsonMeta(table: String, column: ColumnMetadata): JsonColumnMetadata =
        column.json ?: error("JSON column '$table.${column.name}' has no JSON metadata")
}
