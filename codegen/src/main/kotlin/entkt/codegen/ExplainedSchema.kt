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
)

data class ExplainedIndex(
    val name: String,
    val columns: List<String>,
    val unique: Boolean,
    val where: String? = null,
)

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String>,
)
