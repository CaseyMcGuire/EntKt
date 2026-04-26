package entkt.codegen

import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.OnDelete

/**
 * A synthetic foreign-key field derived from a `belongsTo` edge that
 * doesn't already expose its FK via `.field(...)`. The property mirrors
 * the edge name with an "Id" suffix and its type matches the target's
 * id type.
 */
data class EdgeFk(
    val edgeName: String,
    val propertyName: String,
    val columnName: String,
    val targetName: String,
    val targetTable: String,
    val idType: FieldType,
    val required: Boolean,
    val unique: Boolean = false,
    val onDelete: OnDelete? = null,
)

/**
 * Compute implicit FK properties for a schema's `belongsTo` edges.
 * Other edge kinds keep their FK on the opposite side or in a junction
 * table. Edges with an explicit `.field(...)` are skipped — the FK is
 * already declared as a regular field.
 */
fun computeEdgeFks(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<EdgeFk> {
    return schema.edges()
        .filter { it.kind is EdgeKind.BelongsTo && (it.kind as EdgeKind.BelongsTo).field == null }
        .mapNotNull { edge ->
            val belongsTo = edge.kind as EdgeKind.BelongsTo
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            EdgeFk(
                edgeName = edge.name,
                propertyName = "${toCamelCase(edge.name)}Id",
                columnName = "${edge.name}_id",
                targetName = targetName,
                targetTable = edge.target.tableName,
                idType = edge.target.id().type,
                required = belongsTo.required,
                unique = belongsTo.unique,
                onDelete = belongsTo.onDelete,
            )
        }
}
