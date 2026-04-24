package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import entkt.schema.Edge
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.OnDelete

internal val ENTITY_SCHEMA = ClassName("entkt.runtime", "EntitySchema")
internal val COLUMN_METADATA = ClassName("entkt.runtime", "ColumnMetadata")
internal val EDGE_METADATA = ClassName("entkt.runtime", "EdgeMetadata")
internal val INDEX_METADATA = ClassName("entkt.runtime", "IndexMetadata")
internal val FOREIGN_KEY_REF = ClassName("entkt.runtime", "ForeignKeyRef")
internal val ID_STRATEGY = ClassName("entkt.runtime", "IdStrategy")
internal val FIELD_TYPE = ClassName("entkt.schema", "FieldType")
internal val ON_DELETE = ClassName("entkt.schema", "OnDelete")

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
 * Build a map from schema field name to physical column name for all
 * fields in [schema] (including mixin fields). Used to resolve
 * `.field("name")` edge references and index columns to their actual
 * database column names when `storageKey` is set.
 */
internal fun fieldColumnMap(schema: EntSchema): Map<String, String> {
    val fields = schema.fields() + schema.mixins().flatMap { it.fields() }
    val map = mutableMapOf<String, String>()
    for (field in fields) {
        val existing = map.put(field.name, field.columnName)
        if (existing != null) {
            error("Duplicate field name '${field.name}' — field names must be unique across schema fields and mixin fields")
        }
    }
    return map
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
    /** If non-null, this column is an FK referencing (table, column). */
    val references: Pair<String, String>? = null,
    /** Referential action on delete for FK columns. */
    val onDelete: OnDelete? = null,
    /** Documentation comment from the schema DSL, if any. */
    val comment: String? = null,
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

    // Edges with .field("col_name") re-use a declared field as their FK
    // column. Build a lookup so the declared field picks up the FK
    // reference and onDelete action from the edge.
    val fieldsByName = fields.associateBy { it.name }
    val explicitFieldEdges = schema.edges()
        .filter { it.unique && it.field != null && it.through == null }
        .mapNotNull { edge ->
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            val f = edge.field!!
            val backingField = fieldsByName[f]
                ?: error(
                    "Edge '${edge.name}' references .field(\"$f\") but no field " +
                        "with that name exists on the schema",
                )
            val targetIdType = edge.target.id().type
            if (backingField.type != targetIdType) {
                error(
                    "Edge '${edge.name}' references .field(\"$f\") of type " +
                        "${backingField.type} but target entity's id type is $targetIdType",
                )
            }
            f to (tableNameFor(targetName) to edge.onDelete)
        }
        .toMap()

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
            val col = field.columnName
            val edgeRef = explicitFieldEdges[field.name]
            val fieldNullable = field.nullable
            if (edgeRef?.second == OnDelete.SET_NULL && !fieldNullable) {
                error(
                    "ON DELETE SET_NULL on edge with .field(\"${field.name}\") requires " +
                        "the backing field to be nullable",
                )
            }
            add(
                ColumnDescriptor(
                    name = col,
                    type = field.type,
                    nullable = fieldNullable,
                    unique = field.unique,
                    references = edgeRef?.let { (table, _) -> table to "id" },
                    onDelete = edgeRef?.second,
                    comment = field.comment,
                ),
            )
        }
        for (fk in edgeFks) {
            val targetTable = tableNameFor(fk.targetName)
            add(
                ColumnDescriptor(
                    name = fk.columnName,
                    type = fk.idType,
                    nullable = !fk.required,
                    references = targetTable to "id",
                    onDelete = fk.onDelete,
                ),
            )
        }
    }
}

/**
 * Join shape for a single edge: which column on *this* row joins to
 * which column on the target row. Both directions of an edge resolve
 * through this — owning side uses its FK, owned side uses its id.
 * M2M edges additionally carry junction table info.
 */
internal data class EdgeJoin(
    val sourceColumn: String,
    val targetColumn: String,
    val junctionTable: String? = null,
    val junctionSourceColumn: String? = null,
    val junctionTargetColumn: String? = null,
)

/**
 * Look up a field by schema name on [schema], verify it exists, and
 * return its physical column name (respecting storageKey). Used by
 * edge join resolution so a typo in `.field("...")` fails early.
 */
private fun resolveExplicitField(fieldName: String, schema: EntSchema, edgeName: String): String {
    val colMap = fieldColumnMap(schema)
    return colMap[fieldName]
        ?: error(
            "Edge '$edgeName' references .field(\"$fieldName\") but no field " +
                "with that name exists on the schema",
        )
}

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
        val fkColumn = if (edge.field != null) {
            val f = edge.field!!
            resolveExplicitField(f, source, edge.name)
        } else {
            "${edge.name}_id"
        }
        return EdgeJoin(sourceColumn = fkColumn, targetColumn = "id")
    }

    // Owned side: the FK sits on the target row. Find the matching
    // inverse edge to learn its column name.
    val inverse = findInverseEdge(edge, source) ?: return null
    if (!inverse.unique || inverse.through != null) return null
    val fkColumn = if (inverse.field != null) {
        val f = inverse.field!!
        resolveExplicitField(f, edge.target, inverse.name)
    } else {
        "${inverse.name}_id"
    }
    return EdgeJoin(sourceColumn = "id", targetColumn = fkColumn)
}

/**
 * Resolve a many-to-many edge's join through its junction table.
 * The junction schema declares two unique edges with `.field(...)` —
 * one pointing back at [source], one at [edge.target]. We read those
 * FK column names to build the junction join.
 *
 * When the junction has multiple unique edges to the same schema
 * (e.g. a `ProjectAssignment` with both `user` and `approver` edges
 * to `User`), the [Through.sourceEdge] and [Through.targetEdge] hints
 * disambiguate which junction edges participate in the M2M.
 */
internal fun resolveM2MEdgeJoin(
    edge: Edge,
    source: EntSchema,
    schemaNames: Map<EntSchema, String>,
): EdgeJoin? {
    val through = edge.through ?: return null
    val junctionSchema = through.target
    val junctionName = schemaNames[junctionSchema] ?: return null
    val junctionTable = tableNameFor(junctionName)

    val junctionEdges = junctionSchema.edges()

    // Find the junction edge pointing at the source schema.
    // If through.sourceEdge is set, match by name for disambiguation.
    val sourceEdge = if (through.sourceEdge != null) {
        junctionEdges.firstOrNull { it.name == through.sourceEdge && it.unique && it.target === source }
            ?: error(
                "M2M sourceEdge hint \"${through.sourceEdge}\" does not match any unique edge " +
                    "on junction $junctionName targeting ${schemaNames[source] ?: "source"}. " +
                    "Available unique edges: ${junctionEdges.filter { it.unique }.map { it.name }}.",
            )
    } else {
        val candidates = junctionEdges.filter { it.target === source && it.unique }
        // For non-self-referential edges, multiple candidates are ambiguous.
        // Self-referential (source === target) with exactly 2 is fine — first
        // becomes source, second becomes target after exclusion below.
        if (candidates.size > 1 && source !== edge.target) {
            val names = candidates.map { it.name }
            error(
                "Ambiguous M2M: junction $junctionName has ${candidates.size} unique edges " +
                    "targeting ${schemaNames[source] ?: "source"}: $names. " +
                    "Use through(..., sourceEdge = \"...\", targetEdge = \"...\") to disambiguate.",
            )
        }
        candidates.firstOrNull()
    } ?: return null
    val sourceFk = if (sourceEdge.field != null) {
        resolveExplicitField(sourceEdge.field!!, junctionSchema, sourceEdge.name)
    } else {
        "${sourceEdge.name}_id"
    }

    // Find the junction edge pointing at the target schema.
    // If through.targetEdge is set, match by name; otherwise exclude
    // sourceEdge by index so self-referential M2M resolves two distinct edges.
    val targetEdge = if (through.targetEdge != null) {
        junctionEdges.firstOrNull { it.name == through.targetEdge && it.unique && it.target === edge.target }
            ?: error(
                "M2M targetEdge hint \"${through.targetEdge}\" does not match any unique edge " +
                    "on junction $junctionName targeting ${schemaNames[edge.target] ?: "target"}. " +
                    "Available unique edges: ${junctionEdges.filter { it.unique }.map { it.name }}.",
            )
    } else {
        val sourceIdx = junctionEdges.indexOf(sourceEdge)
        val candidates = junctionEdges
            .filterIndexed { i, _ -> i != sourceIdx }
            .filter { it.target === edge.target && it.unique }
        if (candidates.size > 1) {
            val names = candidates.map { it.name }
            error(
                "Ambiguous M2M: junction $junctionName has ${candidates.size} unique edges " +
                    "targeting ${schemaNames[edge.target] ?: "target"}: $names. " +
                    "Use through(..., sourceEdge = \"...\", targetEdge = \"...\") to disambiguate.",
            )
        }
        candidates.firstOrNull()
    } ?: return null
    val targetFk = if (targetEdge.field != null) {
        resolveExplicitField(targetEdge.field!!, junctionSchema, targetEdge.name)
    } else {
        "${targetEdge.name}_id"
    }

    return EdgeJoin(
        sourceColumn = "id",
        targetColumn = "id",
        junctionTable = junctionTable,
        junctionSourceColumn = sourceFk,
        junctionTargetColumn = targetFk,
    )
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
                val colCb = CodeBlock.builder()
                    .add("  %T(name = %S, type = %T.%L, nullable = %L, primaryKey = %L, unique = %L",
                        COLUMN_METADATA, col.name, FIELD_TYPE, col.type.name,
                        col.nullable, col.primaryKey, col.unique)
                if (col.references != null) {
                    val (refTable, refCol) = col.references
                    if (col.onDelete != null) {
                        colCb.add(", references = %T(table = %S, column = %S, onDelete = %T.%L)",
                            FOREIGN_KEY_REF, refTable, refCol, ON_DELETE, col.onDelete.name)
                    } else {
                        colCb.add(", references = %T(table = %S, column = %S)",
                            FOREIGN_KEY_REF, refTable, refCol)
                    }
                }
                if (col.comment != null) {
                    colCb.add(", comment = %S", col.comment)
                }
                colCb.add("),\n")
                cb.add(colCb.build())
            }
        }
        .add(")")
        .build()

    val edgesLiteral = CodeBlock.builder()
    val forwardEntries = schema.edges()
        .mapNotNull { edge ->
            val targetName = schemaNames[edge.target] ?: return@mapNotNull null
            val join = if (edge.through != null) {
                resolveM2MEdgeJoin(edge, schema, schemaNames)
            } else {
                resolveEdgeJoin(edge, schema)
            } ?: return@mapNotNull null
            EdgeEntry(edge.name, tableNameFor(targetName), join, edge.comment)
        }
    val reverseEntries = reverseM2MEdgeEntries(schema, schemaNames)
    val edgeEntries = forwardEntries + reverseEntries

    if (edgeEntries.isEmpty()) {
        edgesLiteral.add("emptyMap()")
    } else {
        edgesLiteral.add("mapOf(\n")
        for (entry in edgeEntries) {
            val edgeCb = CodeBlock.builder()
                .add("  %S to %T(targetTable = %S, sourceColumn = %S, targetColumn = %S",
                    entry.name, EDGE_METADATA, entry.targetTable,
                    entry.join.sourceColumn, entry.join.targetColumn)
            if (entry.join.junctionTable != null) {
                edgeCb.add(", junctionTable = %S, junctionSourceColumn = %S, junctionTargetColumn = %S",
                    entry.join.junctionTable, entry.join.junctionSourceColumn,
                    entry.join.junctionTargetColumn)
            }
            if (entry.comment != null) {
                edgeCb.add(", comment = %S", entry.comment)
            }
            edgeCb.add("),\n")
            edgesLiteral.add(edgeCb.build())
        }
        edgesLiteral.add(")")
    }

    val schemaIndexes = schema.indexes() + schema.mixins().flatMap { it.indexes() }
    val colMap = fieldColumnMap(schema)
    val indexesLiteral = CodeBlock.builder()
    if (schemaIndexes.isEmpty()) {
        indexesLiteral.add("emptyList()")
    } else {
        indexesLiteral.add("listOf(\n")
        for (idx in schemaIndexes) {
            val fieldsLiteral = idx.fields.joinToString(", ") {
                val col = colMap[it] ?: error("Index references field '$it' but no field with that name exists on the schema")
                "\"$col\""
            }
            val cb = CodeBlock.builder()
                .add("  %T(columns = listOf($fieldsLiteral), unique = %L", INDEX_METADATA, idx.unique)
            if (idx.storageKey != null) cb.add(", storageKey = %S", idx.storageKey)
            if (idx.where != null) cb.add(", where = %S", idx.where)
            cb.add("),\n")
            indexesLiteral.add(cb.build())
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

/**
 * Find M2M edges declared on *other* schemas that target [schema] and
 * produce reverse edge entries so the runtime can resolve `HasEdge` /
 * `HasEdgeWith` predicates from the target side. Each reverse entry
 * swaps the junction's source and target FK columns so the join walks
 * from [schema] back through the junction to the declaring schema.
 *
 * The reverse edge name is the declaring schema's table name — e.g. for
 * `Group.to("users", User).through(...)`, User gets a reverse edge
 * named `"groups"`.
 */
internal data class EdgeEntry(
    val name: String,
    val targetTable: String,
    val join: EdgeJoin,
    val comment: String? = null,
)

internal fun reverseM2MEdgeEntries(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<EdgeEntry> {
    return schemaNames.flatMap { (otherSchema, otherName) ->
        otherSchema.edges()
            .filter { it.through != null && it.target === schema }
            .mapNotNull { edge ->
                val forwardJoin = resolveM2MEdgeJoin(edge, otherSchema, schemaNames)
                    ?: return@mapNotNull null
                val reverseJoin = EdgeJoin(
                    sourceColumn = "id",
                    targetColumn = "id",
                    junctionTable = forwardJoin.junctionTable,
                    junctionSourceColumn = forwardJoin.junctionTargetColumn,
                    junctionTargetColumn = forwardJoin.junctionSourceColumn,
                )
                val reverseName = reverseM2MEdgeName(otherName, edge.name)
                val targetTable = tableNameFor(otherName)
                EdgeEntry(reverseName, targetTable, reverseJoin)
            }
    }
}

/**
 * Compute the name for a reverse M2M edge entry on the target schema.
 * Incorporates both the source table name and the forward edge name so
 * that multiple M2M edges from the same source to the same target each
 * get their own unique reverse entry.
 */
internal fun reverseM2MEdgeName(sourceName: String, forwardEdgeName: String): String =
    "${tableNameFor(sourceName)}_$forwardEdgeName"
