package entkt.codegen

import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType
import entkt.schema.OnDelete
import entkt.schema.Validator

/**
 * A foreign-key surface derived from a `belongsTo` edge. For implicit
 * edges the column and property are synthesized from the edge name
 * (`edge_name_id` / `edgeNameId`). For field-backed edges
 * (`belongsTo(...).field(handle)`) the column comes from the
 * user-declared field, and the property is the camelCased column name.
 * True declaration-name capture (using the Kotlin `val` name when it
 * diverges from the column name) is still deferred — see the RFC's
 * Deferred Scope section.
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
    /**
     * `true` when the FK column was declared as an explicit scalar
     * field via `belongsTo(...).field(handle)`. The same column must
     * not also be emitted through the scalar field code path — use
     * [scalarFields] to get the filtered view.
     */
    val isFieldBacked: Boolean = false,
    /**
     * Create-time default for the FK value, carried from the backing
     * field's `.default(...)` modifier. Implicit FKs have no DSL surface
     * for defaults so this is always `null` for them. Applied to the
     * create save path with explicit-null-wins semantics for nullable
     * FKs (see [assignedFieldName]).
     */
    val default: Any? = null,
    /**
     * `true` when the relationship is immutable — set on create only,
     * never on update. Driven by the backing field's `.immutable()` for
     * field-backed edges. Implicit FKs are always mutable until an
     * edge-level immutability modifier is added in a future RFC.
     */
    val immutable: Boolean = false,
    /**
     * Field-level validators carried from the backing field declaration
     * (e.g. `long("owner_id").positive()`). Implicit FKs have no DSL
     * surface for validators, so this is empty for them. Generated
     * create and update validation runs these on FK Set entries the
     * same way it does for scalar fields.
     */
    val validators: List<Validator> = emptyList(),
    /**
     * `true` when the backing field is `.sensitive()`. The generated
     * entity `toString()` redacts the FK value as `***` for sensitive
     * field-backed FKs, matching the existing scalar-field behavior.
     * Implicit FKs have no DSL surface for `.sensitive()` so this is
     * always `false` for them.
     */
    val sensitive: Boolean = false,
)

/**
 * Private nullable staging field name used by builders to hold required
 * FK values before the public non-null getter is read. The leading
 * underscore + `Staging` suffix keeps the name out of the public API and
 * distinct from any user-declared property.
 */
internal fun stagingFieldName(propertyName: String): String = "_${propertyName}Staging"

/**
 * Private "has the caller explicitly assigned this FK property?" flag
 * used by the nullable-FK-with-default path. Lets the create save body
 * distinguish "untouched (apply default)" from "explicitly set to null
 * (suppress default)" per the RFC's explicit-null-wins rule.
 */
internal fun assignedFieldName(propertyName: String): String = "_${propertyName}Assigned"

/**
 * Compute the FK surfaces for a schema's `belongsTo` edges — implicit
 * and field-backed alike. Other edge kinds keep their FK on the
 * opposite side or in a junction table.
 */
fun computeEdgeFks(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<EdgeFk> {
    // Look up backing fields by name so field-backed FKs can carry the
    // backing field's `.default(...)` value into the EdgeFk metadata.
    // Implicit edges have no DSL surface for defaults.
    val fieldsByName: Map<String, Field> by lazy { schema.fields().associateBy { it.name } }
    return schema.edges()
        .filter { it.kind is EdgeKind.BelongsTo }
        .mapNotNull { edge ->
            val belongsTo = edge.kind as EdgeKind.BelongsTo
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            val backingColumn = belongsTo.field
            if (backingColumn != null) {
                val backingField = fieldsByName[backingColumn]
                EdgeFk(
                    edgeName = edge.name,
                    propertyName = toCamelCase(backingColumn),
                    columnName = backingColumn,
                    targetName = targetName,
                    targetTable = edge.target.tableName,
                    idType = edge.target.id().type,
                    required = belongsTo.required,
                    unique = belongsTo.unique,
                    onDelete = belongsTo.onDelete,
                    isFieldBacked = true,
                    default = backingField?.default,
                    immutable = backingField?.immutable == true,
                    validators = backingField?.validators ?: emptyList(),
                    sensitive = backingField?.sensitive == true,
                )
            } else {
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
}

/**
 * Scalar fields excluding any column that backs a
 * `belongsTo(...).field(handle)` edge. The backing column is emitted
 * via [computeEdgeFks] instead so it picks up relationship-FK
 * semantics (non-null type on required FKs, throw-on-unassigned getter,
 * setter null-reject, dirty tracking, patch lowering).
 */
fun scalarFields(schema: EntSchema): List<Field> {
    val backingColumns = schema.edges()
        .mapNotNull { (it.kind as? EdgeKind.BelongsTo)?.field }
        .toSet()
    if (backingColumns.isEmpty()) return schema.fields()
    return schema.fields().filterNot { it.name in backingColumns }
}
