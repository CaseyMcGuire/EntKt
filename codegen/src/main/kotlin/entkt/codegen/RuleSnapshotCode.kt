package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import entkt.codegen.kotlinpoet.codeBlock
import entkt.schema.Field
import entkt.schema.FieldType

private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")

/**
 * Detach caller-owned mutable `FieldPatch.Set` entries at UPDATE preparation,
 * not per rule, while preserving `Unset` and
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

    return codeBlock {
        add("%L.copy(\n", source)
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
        add(")")
    }
}
