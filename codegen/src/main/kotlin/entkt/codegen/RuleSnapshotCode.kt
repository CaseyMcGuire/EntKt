package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import entkt.schema.Field
import entkt.schema.FieldType

private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")

/**
 * Copy every generated `bytes()` property on an entity or write candidate.
 * Rule contexts use this when they capture their input so a mutable
 * [ByteArray] cannot alias the mutation pipeline's database-bound value.
 */
internal fun byteArraySnapshot(source: String, fields: List<Field>): CodeBlock {
    val byteFields = fields.filter { it.type == FieldType.BYTES }
    if (byteFields.isEmpty()) return CodeBlock.of("%L", source)

    return CodeBlock.builder()
        .add("%L.copy(\n", source)
        .apply {
            for (field in byteFields) {
                val property = toCamelCase(field.name)
                val nullableAccess = if (field.nullable) "?" else ""
                add("  %L = %L.%L$nullableAccess.copyOf(),\n", property, source, property)
            }
        }
        .add(")")
        .build()
}

/**
 * Copy `FieldPatch.Set(ByteArray)` entries while preserving `Unset` and
 * explicit `Set(null)`. Immutable fields are absent from generated patches.
 */
internal fun byteArrayPatchSnapshot(source: String, fields: List<Field>): CodeBlock {
    val byteFields = fields.filter { it.type == FieldType.BYTES && !it.immutable }
    if (byteFields.isEmpty()) return CodeBlock.of("%L", source)

    return CodeBlock.builder()
        .add("%L.copy(\n", source)
        .apply {
            for (field in byteFields) {
                val property = toCamelCase(field.name)
                val nullableAccess = if (field.nullable) "?" else ""
                add("  %L = when (val entry = %L.%L) {\n", property, source, property)
                add(
                    "    is %T.Set -> %T.Set(entry.value$nullableAccess.copyOf())\n",
                    FIELD_PATCH,
                    FIELD_PATCH,
                )
                add("    %T.Unset -> %T.Unset\n", FIELD_PATCH, FIELD_PATCH)
                add("  },\n")
            }
        }
        .add(")")
        .build()
}
