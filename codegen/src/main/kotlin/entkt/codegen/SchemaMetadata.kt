package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import entkt.schema.Edge
import entkt.schema.EntSchema
import entkt.schema.FieldType

internal val ENTITY_SCHEMA = ClassName("entkt.runtime", "EntitySchema")
internal val EDGE_METADATA = ClassName("entkt.runtime", "EdgeMetadata")
internal val ID_STRATEGY = ClassName("entkt.runtime", "IdStrategy")

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
 * The list of every column backing the entity: `id`, each declared and
 * mixin field, and any synthesized edge FKs. Used as the `columns` list
 * on the generated [entkt.runtime.EntitySchema] constant so drivers can
 * enumerate them without reflection.
 */
internal fun columnNamesFor(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<String> {
    val fields = schema.fields() + schema.mixins().flatMap { it.fields() }
    val edgeFks = computeEdgeFks(schema, schemaNames)
    return buildList {
        add("id")
        addAll(fields.map { it.name })
        addAll(edgeFks.map { it.columnName })
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
    val columns = columnNamesFor(schema, schemaNames)
    val columnsLiteral = CodeBlock.builder()
        .add("listOf(")
        .add(columns.joinToString(", ") { "%S" }, *columns.toTypedArray())
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

    return CodeBlock.builder()
        .add("%T(\n", ENTITY_SCHEMA)
        .add("  table = %S,\n", table)
        .add("  idColumn = %S,\n", "id")
        .add("  idStrategy = %T.%L,\n", ID_STRATEGY, idStrategyName(schema))
        .add("  columns = %L,\n", columnsLiteral)
        .add("  edges = %L,\n", edgesLiteral.build())
        .add(")")
        .build()
}
