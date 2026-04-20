package entkt.codegen

import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.OnDelete

/**
 * A synthetic foreign-key field derived from a unique edge that doesn't
 * already expose its FK via `.field(...)`. The property mirrors the edge
 * name with an "Id" suffix and its type matches the target's id type.
 */
data class EdgeFk(
    val edgeName: String,
    val propertyName: String,
    val columnName: String,
    val targetName: String,
    val idType: FieldType,
    val required: Boolean,
    val onDelete: OnDelete? = null,
)

/**
 * Compute implicit FK properties for a schema's unique edges. Non-unique
 * edges (to-many) keep their FK on the opposite side. Edges with an
 * explicit `.field(...)` or `.through(...)` are skipped — the former is
 * already declared as a regular field, the latter lives in a join table.
 */
fun computeEdgeFks(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<EdgeFk> {
    return schema.edges()
        .filter { it.unique && it.field == null && it.through == null }
        .mapNotNull { edge ->
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            EdgeFk(
                edgeName = edge.name,
                propertyName = "${toCamelCase(edge.name)}Id",
                columnName = "${edge.name}_id",
                targetName = targetName,
                idType = edge.target.id().type,
                required = edge.required,
                onDelete = edge.onDelete,
            )
        }
}