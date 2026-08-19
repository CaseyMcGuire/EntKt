package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import entkt.schema.Field
import entkt.schema.FieldType

private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")

/**
 * Detach every mutable generated property on an entity or write candidate.
 * Byte arrays copy directly; typed JSON values round-trip through the driver's
 * configured mapper so nested mutable collections and arrays are detached too.
 */
internal fun lifecycleValueSnapshot(
    source: String,
    fields: List<Field>,
    entityClass: ClassName,
): CodeBlock {
    val mutableFields = fields.filter { it.type == FieldType.BYTES || it.type == FieldType.JSON }
    if (mutableFields.isEmpty()) return CodeBlock.of("%L", source)

    return CodeBlock.builder()
        .add("%L.copy(\n", source)
        .apply {
            for (field in mutableFields) {
                val property = field.apiName
                if (field.type == FieldType.BYTES) {
                    val nullableAccess = if (field.nullable) "?" else ""
                    add("  %L = %L.%L$nullableAccess.copyOf(),\n", property, source, property)
                } else {
                    add(
                        "  %L = driver.copyJsonValue(%T.TABLE, %S, %L.%L),\n",
                        property,
                        entityClass,
                        field.columnName,
                        source,
                        property,
                    )
                }
            }
        }
        .add(")")
        .build()
}

/**
 * Detach mutable `FieldPatch.Set` entries while preserving `Unset` and
 * explicit `Set(null)`. Immutable fields are absent from generated patches.
 */
internal fun lifecyclePatchSnapshot(
    source: String,
    fields: List<Field>,
    entityClass: ClassName,
): CodeBlock {
    val mutableFields = fields.filter {
        !it.immutable && (it.type == FieldType.BYTES || it.type == FieldType.JSON)
    }
    if (mutableFields.isEmpty()) return CodeBlock.of("%L", source)

    return CodeBlock.builder()
        .add("%L.copy(\n", source)
        .apply {
            for (field in mutableFields) {
                val property = field.apiName
                add("  %L = when (val entry = %L.%L) {\n", property, source, property)
                if (field.type == FieldType.BYTES) {
                    val nullableAccess = if (field.nullable) "?" else ""
                    add(
                        "    is %T.Set -> %T.Set(entry.value$nullableAccess.copyOf())\n",
                        FIELD_PATCH,
                        FIELD_PATCH,
                    )
                } else {
                    add(
                        "    is %T.Set -> %T.Set(driver.copyJsonValue(%T.TABLE, %S, entry.value))\n",
                        FIELD_PATCH,
                        FIELD_PATCH,
                        entityClass,
                        field.columnName,
                    )
                }
                add("    %T.Unset -> %T.Unset\n", FIELD_PATCH, FIELD_PATCH)
                add("  },\n")
            }
        }
        .add(")")
        .build()
}
