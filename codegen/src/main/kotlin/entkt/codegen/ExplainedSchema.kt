package entkt.codegen

import entkt.schema.FieldType

data class ExplainedSchemaGraph(
    val schemas: List<ExplainedSchema>,
)

/**
 * Explain carries all three naming identities separately, because they
 * are independent and a reader needs to see which is which:
 * the schema class name ([schemaName]), the generated client property
 * ([clientName]), and the storage table ([tableName]). Field and edge
 * entries follow the same split — `apiName` is the Kotlin declaration,
 * `name` stays the storage identifier the relational view is about.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
data class ExplainedSchema(
    val schemaName: String,
    /** The generated client property: `client.<clientName>`. */
    val clientName: String,
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
    /** Storage column name. */
    val name: String,
    /** Kotlin declaration name — the generated field API. */
    val apiName: String,
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
    /** The generated FK property, e.g. `curatorId`. */
    val propertyName: String,
    val targetTable: String,
    val targetColumn: String,
    val nullable: Boolean,
    val onDelete: String,
    /** Storage name of the edge that owns this FK. */
    val sourceEdge: String,
    /** Kotlin declaration name of that edge. */
    val sourceEdgeApiName: String,
)

data class ExplainedEdge(
    /** Storage edge name — the driver's edge-lookup key. */
    val name: String,
    /** Kotlin declaration name — the generated edge API. */
    val apiName: String,
    val kind: String,
    val targetSchema: String,
    val fkColumn: String? = null,
    /** Storage name of the inverse edge on the target schema. */
    val inverse: String? = null,
    /** Kotlin declaration name of that inverse edge. */
    val inverseApiName: String? = null,
    val through: ExplainedThrough? = null,
    val comment: String? = null,
)

data class ExplainedThrough(
    val junctionTable: String,
    /** Storage name of the junction edge pointing at the source schema. */
    val sourceEdge: String,
    /** Kotlin declaration name of that junction edge. */
    val sourceEdgeApiName: String,
    /** Storage name of the junction edge pointing at the target schema. */
    val targetEdge: String,
    /** Kotlin declaration name of that junction edge. */
    val targetEdgeApiName: String,
    /**
     * The concrete link-table mutation surface this edge exposes:
     * `["add", "remove", "set"]` for a `throughLink` edge, or `null` for
     * a `throughEntity` edge, which is mutated through its junction repo.
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
