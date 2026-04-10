package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import entkt.schema.Edge
import entkt.schema.EntSchema
import entkt.schema.FieldType

internal val ENTITY_SCHEMA = ClassName("entkt.runtime", "EntitySchema")
internal val COLUMN_METADATA = ClassName("entkt.runtime", "ColumnMetadata")
internal val EDGE_METADATA = ClassName("entkt.runtime", "EdgeMetadata")
internal val INDEX_METADATA = ClassName("entkt.runtime", "IndexMetadata")
internal val ID_STRATEGY = ClassName("entkt.runtime", "IdStrategy")
internal val FIELD_TYPE = ClassName("entkt.schema", "FieldType")

/**
 * Convert a generated entity name (`User`, `Post`, `Tag`) to its SQL
 * table name. This mirrors the ClientGenerator's repo property naming
 * so that `client.users` and `User.TABLE` agree.
 */
internal fun tableNameFor(schemaName: String): String =
    pluralize(schemaName.replaceFirstChar { it.lowercase() })

/**
 * The [IdStrategy] enum variant that matches this schema's id declaration.
 * UUIDs are minted by the generated `save()`; numeric ids with
 * `autoIncrement` are assigned by the driver; everything else forces the
 * caller to supply an id.
 */
internal fun idStrategyName(schema: EntSchema): String {
    val id = schema.id()
    return when {
        id.type == FieldType.UUID -> "CLIENT_UUID"
        id.type == FieldType.INT && id.autoIncrement -> "AUTO_INT"
        id.type == FieldType.LONG && id.autoIncrement -> "AUTO_LONG"
        else -> "EXPLICIT"
    }
}

/**
 * One column descriptor as it should appear in the generated
 * `EntitySchema.columns` list. Captured as a plain Kotlin record so
 * codegen can fold it into either the runtime [entkt.runtime.ColumnMetadata]
 * literal or other emitters without re-deriving nullability.
 */
internal data class ColumnDescriptor(
    val name: String,
    val type: FieldType,
    val nullable: Boolean,
    val primaryKey: Boolean = false,
    val unique: Boolean = false,
)

/**
 * Every column backing the entity, in declaration order: `id` first,
 * then declared and mixin fields, then any synthesized edge FKs. Used
 * to build the `columns` list on the generated [entkt.runtime.EntitySchema]
 * constant so SQL drivers can enumerate them — type and all — without
 * reflection.
 */
internal fun columnMetadataFor(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<ColumnDescriptor> {
    val fields = schema.fields() + schema.mixins().flatMap { it.fields() }
    val edgeFks = computeEdgeFks(schema, schemaNames)
    return buildList {
        add(
            ColumnDescriptor(
                name = "id",
                type = schema.id().type,
                nullable = false,
                primaryKey = true,
            ),
        )
        for (field in fields) {
            add(
                ColumnDescriptor(
                    name = field.name,
                    type = field.type,
                    nullable = field.optional || field.nillable,
                    unique = field.unique,
                ),
            )
        }
        for (fk in edgeFks) {
            add(
                ColumnDescriptor(
                    name = fk.columnName,
                    type = fk.idType,
                    nullable = !fk.required,
                ),
            )
        }
    }
}

/**
 * Join shape for a single edge: which column on *this* row joins to
 * which column on the target row. Both directions of an edge resolve
 * through this — owning side uses its FK, owned side uses its id.
 */
internal data class EdgeJoin(val sourceColumn: String, val targetColumn: String)

/**
 * Resolve [edge]'s join columns by asking "is this edge's FK stored on
 * this side or on the target?". The owning side (unique + no
 * `.through(...)`) names the FK column explicitly (`field` overrides the
 * default `${edgeName}_id`); the owned side (to-many) has to find its
 * inverse and read the FK from there.
 *
 * Returns null for to-many edges whose inverse can't be identified —
 * codegen just skips the entry in the generated edges map.
 */
internal fun resolveEdgeJoin(
    edge: Edge,
    source: EntSchema,
): EdgeJoin? {
    if (edge.through != null) return null

    if (edge.unique) {
        // Owning side: the FK sits on this row.
        val fkColumn = edge.field ?: "${edge.name}_id"
        return EdgeJoin(sourceColumn = fkColumn, targetColumn = "id")
    }

    // Owned side: the FK sits on the target row. Find the matching
    // inverse edge to learn its column name.
    val inverse = findInverseEdge(edge, source) ?: return null
    if (!inverse.unique || inverse.through != null) return null
    val fkColumn = inverse.field ?: "${inverse.name}_id"
    return EdgeJoin(sourceColumn = "id", targetColumn = fkColumn)
}

/**
 * Build the `CodeBlock` for an `EntitySchema(...)` literal — the value
 * that gets baked into the entity companion as `SCHEMA`. Driven entirely
 * by the static [schema] and the resolved [schemaNames] map so that no
 * runtime introspection is needed.
 */
internal fun entitySchemaCodeBlock(
    schemaName: String,
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): CodeBlock {
    val table = tableNameFor(schemaName)
    val columns = columnMetadataFor(schema, schemaNames)
    val columnsLiteral = CodeBlock.builder()
        .add("listOf(\n")
        .also { cb ->
            for (col in columns) {
                cb.add(
                    "  %T(name = %S, type = %T.%L, nullable = %L, primaryKey = %L, unique = %L),\n",
                    COLUMN_METADATA,
                    col.name,
                    FIELD_TYPE,
                    col.type.name,
                    col.nullable,
                    col.primaryKey,
                    col.unique,
                )
            }
        }
        .add(")")
        .build()

    val edgesLiteral = CodeBlock.builder()
    val edgeEntries = schema.edges()
        .filter { it.through == null }
        .mapNotNull { edge ->
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            val join = resolveEdgeJoin(edge, schema) ?: return@mapNotNull null
            Triple(edge.name, tableNameFor(targetName), join)
        }

    if (edgeEntries.isEmpty()) {
        edgesLiteral.add("emptyMap()")
    } else {
        edgesLiteral.add("mapOf(\n")
        for ((edgeName, targetTable, join) in edgeEntries) {
            edgesLiteral.add(
                "  %S to %T(targetTable = %S, sourceColumn = %S, targetColumn = %S),\n",
                edgeName,
                EDGE_METADATA,
                targetTable,
                join.sourceColumn,
                join.targetColumn,
            )
        }
        edgesLiteral.add(")")
    }

    val schemaIndexes = schema.indexes()
    val indexesLiteral = CodeBlock.builder()
    if (schemaIndexes.isEmpty()) {
        indexesLiteral.add("emptyList()")
    } else {
        indexesLiteral.add("listOf(\n")
        for (idx in schemaIndexes) {
            val fieldsLiteral = idx.fields.joinToString(", ") { "\"$it\"" }
            if (idx.storageKey != null) {
                indexesLiteral.add(
                    "  %T(columns = listOf($fieldsLiteral), unique = %L, storageKey = %S),\n",
                    INDEX_METADATA,
                    idx.unique,
                    idx.storageKey,
                )
            } else {
                indexesLiteral.add(
                    "  %T(columns = listOf($fieldsLiteral), unique = %L),\n",
                    INDEX_METADATA,
                    idx.unique,
                )
            }
        }
        indexesLiteral.add(")")
    }

    return CodeBlock.builder()
        .add("%T(\n", ENTITY_SCHEMA)
        .add("  table = %S,\n", table)
        .add("  idColumn = %S,\n", "id")
        .add("  idStrategy = %T.%L,\n", ID_STRATEGY, idStrategyName(schema))
        .add("  columns = %L,\n", columnsLiteral)
        .add("  edges = %L,\n", edgesLiteral.build())
        .add("  indexes = %L,\n", indexesLiteral.build())
        .add(")")
        .build()
}
