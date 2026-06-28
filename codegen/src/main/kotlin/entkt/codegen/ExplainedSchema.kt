package entkt.codegen

import entkt.schema.FieldType

data class ExplainedSchemaGraph(
    val schemas: List<ExplainedSchema>,
)

data class ExplainedSchema(
    val schemaName: String,
    val tableName: String,
    val id: ExplainedId,
    val fields: List<ExplainedField>,
    val foreignKeys: List<ExplainedForeignKey>,
    val edges: List<ExplainedEdge>,
    val indexes: List<ExplainedIndex>,
)

data class ExplainedId(
    val type: FieldType,
    val strategy: String,
)

data class ExplainedField(
    val name: String,
    val type: FieldType,
    val nullable: Boolean,
    val unique: Boolean = false,
    val immutable: Boolean = false,
    val sensitive: Boolean = false,
    val default: String? = null,
    val comment: String? = null,
)

data class ExplainedForeignKey(
    val column: String,
    val targetTable: String,
    val targetColumn: String,
    val nullable: Boolean,
    val onDelete: String,
    val sourceEdge: String,
)

data class ExplainedEdge(
    val name: String,
    val kind: String,
    val targetSchema: String,
    val fkColumn: String? = null,
    val inverse: String? = null,
    val through: ExplainedThrough? = null,
    val comment: String? = null,
)

data class ExplainedThrough(
    val junctionTable: String,
    val sourceEdge: String,
    val targetEdge: String,
    /**
     * The concrete link-table mutation surface this edge exposes (symmetric link-table writes):
     * `["add", "remove", "set"]` for a writable `throughLink` side,
     * `[]` for a `.readOnly()` side (read traversal only). `null` for a
     * `throughEntity` M2M edge, which has no link-table write helpers.
     */
    val writeHelpers: List<String>? = null,
)

data class ExplainedIndex(
    val name: String,
    val columns: List<String>,
    val unique: Boolean,
    val where: String? = null,
    /**
     * The generated index-helper access paths this index produces, e.g.
     * `indexes.authorId(authorId).query()`. One per left prefix. Empty
     * when the index is ineligible for helper generation (partial,
     * native/non-btree, or a btree-incompatible column type).
     */
    val helpers: List<String> = emptyList(),
)

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String>,
)
