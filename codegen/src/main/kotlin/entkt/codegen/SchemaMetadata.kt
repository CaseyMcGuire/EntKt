package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.ManyToManyThrough
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
 * fields in [schema]. Used to resolve `.field(...)` edge references and
 * index columns to their actual database column names.
 */
internal fun fieldColumnMap(schema: EntSchema): Map<String, String> {
    val fields = schema.fields()
    val map = mutableMapOf<String, String>()
    val columnToField = mutableMapOf<String, String>()
    for (field in fields) {
        val existing = map.put(field.name, field.columnName)
        if (existing != null) {
            error("Duplicate field name '${field.name}' — field names must be unique per schema")
        }
        val previousField = columnToField.put(field.columnName, field.name)
        if (previousField != null) {
            error("Fields '$previousField' and '${field.name}' both resolve to column '${field.columnName}' — physical column names must be unique")
        }
    }
    return map
}

/**
 * Like [fieldColumnMap] but also includes synthesized edge FK columns.
 * Used for index resolution, where indexes may target edge FK columns.
 */
internal fun indexableColumnMap(schema: EntSchema, schemaNames: Map<EntSchema, String>): Map<String, String> {
    val base = fieldColumnMap(schema).toMutableMap()
    for (fk in computeEdgeFks(schema, schemaNames)) {
        base[fk.columnName] = fk.columnName
    }
    return base
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
 * then declared fields, then any synthesized edge FKs. Used to build
 * the `columns` list on the generated [entkt.runtime.EntitySchema]
 * constant so SQL drivers can enumerate them — type and all — without
 * reflection.
 */
internal fun columnMetadataFor(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<ColumnDescriptor> {
    val fields = schema.fields()
    // Column metadata is storage-oriented: backing FK columns come from
    // the declared field (which carries the comment, default, validators)
    // with edge metadata layered on via `explicitFieldEdges`. Drop
    // field-backed entries from the FK list here so the same column
    // isn't emitted twice.
    val edgeFks = computeEdgeFks(schema, schemaNames).filterNot { it.isFieldBacked }

    // Edges with .field(handle) re-use a declared field as their FK
    // column. Build a lookup so the declared field picks up the FK
    // reference, onDelete action, and unique constraint from the edge.
    val fieldsByName = fields.associateBy { it.name }
    val explicitFieldEdges = mutableMapOf<String, ExplicitFieldEdge>()
    for (edge in schema.edges()) {
        if (edge.kind !is EdgeKind.BelongsTo) continue
        val belongsTo = edge.kind as EdgeKind.BelongsTo
        val f = belongsTo.field ?: continue
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
        if (backingField.updateDefault != null) {
            // Per RFC: update defaults on relationship FKs would silently
            // rewrite untouched relationships on every update. The
            // caller should express the intent as a beforeUpdate or
            // afterUpdate hook on the owner entity instead.
            //
            // Today only `time().updateDefaultNow()` exposes the DSL,
            // and `time` can't match any current FK target id type
            // (Int / Long / UUID / String), so the type-mismatch check
            // above fires first under the current DSL. This check is
            // preventative future-proofing in case a numeric
            // `.updateDefault(...)` modifier is added later.
            error(
                "Edge '${edge.name}' references .field(\"$f\") which carries " +
                    "an updateDefault — update defaults are not allowed on " +
                    "field-backed FK columns. Express the intent as a " +
                    "beforeUpdate or afterUpdate hook on the owner entity instead.",
            )
        }
        val existing = explicitFieldEdges.put(
            f,
            ExplicitFieldEdge(edge.name, edge.target.tableName, belongsTo.onDelete, belongsTo.required, belongsTo.unique),
        )
        if (existing != null) {
            error(
                "Field '$f' is used as the backing field for both edge '${existing.edgeName}' " +
                    "and edge '${edge.name}' — each backing field can only be used by one edge",
            )
        }
    }

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
            if (edgeRef != null) {
                if (edgeRef.required && fieldNullable) {
                    error(
                        "Edge '${edgeRef.edgeName}' is required but .field(\"${field.name}\") " +
                            "is nullable — a required edge needs a non-nullable backing field",
                    )
                }
                if (!edgeRef.required && !fieldNullable) {
                    error(
                        "Edge '${edgeRef.edgeName}' is nullable but .field(\"${field.name}\") " +
                            "is non-null — a nullable edge needs a nullable backing field " +
                            "(add .nullable() to the field declaration)",
                    )
                }
                if (field.unique && !edgeRef.unique) {
                    error(
                        "Edge '${edgeRef.edgeName}' is not .unique() but .field(\"${field.name}\") " +
                            "has a unique constraint — add .unique() to the edge or remove " +
                            ".unique() from the field",
                    )
                }
                if (edgeRef.onDelete == OnDelete.SET_NULL && !fieldNullable) {
                    error(
                        "ON DELETE SET_NULL on edge with .field(\"${field.name}\") requires " +
                            "the backing field to be nullable",
                    )
                }
            }
            add(
                ColumnDescriptor(
                    name = col,
                    type = field.type,
                    nullable = fieldNullable,
                    unique = field.unique || (edgeRef?.unique == true),
                    references = edgeRef?.let { it.targetTable to "id" },
                    onDelete = edgeRef?.onDelete,
                    comment = field.comment,
                ),
            )
        }
        for (fk in edgeFks) {
            add(
                ColumnDescriptor(
                    name = fk.columnName,
                    type = fk.idType,
                    nullable = !fk.required,
                    unique = fk.unique,
                    references = fk.targetTable to "id",
                    onDelete = fk.onDelete,
                ),
            )
        }
    }.also { columns ->
        val seen = mutableMapOf<String, Int>()
        for ((i, col) in columns.withIndex()) {
            val prev = seen.put(col.name, i)
            if (prev != null) {
                error("Column '${col.name}' appears more than once — physical column names must be unique per entity")
            }
        }
    }
}

/**
 * Info carried from a `belongsTo(...).field(handle)` edge to the backing
 * field's [ColumnDescriptor]. Captures the FK reference, ON DELETE action,
 * and whether the edge declared `.unique()`.
 */
private data class ExplicitFieldEdge(
    val edgeName: String,
    val targetTable: String,
    val onDelete: OnDelete?,
    val required: Boolean,
    val unique: Boolean,
)

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
 * return its physical column name. Used by
 * edge join resolution so a typo in `.field(...)` fails early.
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
 * Resolve [edge]'s join columns based on its [EdgeKind].
 *
 * - **BelongsTo**: the FK sits on this row. `.field(...)` overrides the
 *   default `${edgeName}_id` column name.
 * - **HasMany / HasOne**: the FK sits on the target row. Finds the
 *   inverse `BelongsTo` edge to learn its column name.
 * - **ManyToMany**: handled by [resolveM2MEdgeJoin]; returns null here.
 */
internal fun resolveEdgeJoin(
    edge: Edge,
    source: EntSchema,
): EdgeJoin? {
    when (val kind = edge.kind) {
        is EdgeKind.ManyToMany -> return null

        is EdgeKind.BelongsTo -> {
            // Owning side: the FK sits on this row.
            val fieldName = kind.field
            val fkColumn = if (fieldName != null) {
                resolveExplicitField(fieldName, source, edge.name)
            } else {
                "${edge.name}_id"
            }
            return EdgeJoin(sourceColumn = fkColumn, targetColumn = "id")
        }

        is EdgeKind.HasMany, is EdgeKind.HasOne -> {
            // Inverse side: the FK sits on the target row. Find the
            // matching BelongsTo edge to learn its column name.
            val inverse = findInverseEdge(edge, source)
                ?: error(
                    "Edge '${edge.name}' is a ${edge.kind::class.simpleName} edge but no " +
                        "inverse belongsTo edge was found on the target schema. " +
                        "The target must declare a belongsTo(...) edge pointing back at the source.",
                )
            val inverseBt = inverse.kind as? EdgeKind.BelongsTo
                ?: error(
                    "Edge '${edge.name}' resolved to inverse '${inverse.name}' " +
                        "but it is not a belongsTo edge",
                )
            if (edge.kind is EdgeKind.HasOne && !inverseBt.unique) {
                error(
                    "hasOne edge '${edge.name}' requires its inverse belongsTo " +
                        "edge '${inverse.name}' to declare .unique()",
                )
            }
            if (edge.kind is EdgeKind.HasMany && inverseBt.unique) {
                error(
                    "hasMany edge '${edge.name}' found inverse belongsTo " +
                        "edge '${inverse.name}' with .unique() — use hasOne " +
                        "instead of hasMany for one-to-one relationships",
                )
            }
            val inverseFieldName = inverseBt.field
            val fkColumn = if (inverseFieldName != null) {
                resolveExplicitField(inverseFieldName, edge.target, inverse.name)
            } else {
                "${inverse.name}_id"
            }
            return EdgeJoin(sourceColumn = "id", targetColumn = fkColumn)
        }
    }
}

/**
 * Resolve a many-to-many edge's join through its junction table.
 * The junction schema declares `belongsTo` edges pointing at both
 * sides; `through.sourceEdge` / `through.targetEdge` name them
 * explicitly (the DSL forces both refs via `throughLink` /
 * `throughEntity`), so we look them up directly and verify the targets.
 */
internal fun resolveM2MEdgeJoin(
    edge: Edge,
    source: EntSchema,
    schemaNames: Map<EntSchema, String>,
): EdgeJoin? {
    val m2m = edge.kind as? EdgeKind.ManyToMany ?: return null
    val through = m2m.through
    val junctionSchema = through.junction
    val junctionName = schemaNames[junctionSchema] ?: return null
    val junctionTable = junctionSchema.tableName

    val junctionEdges = junctionSchema.edges()

    val sourceEdge = junctionEdges.firstOrNull { it.name == through.sourceEdge && it.kind is EdgeKind.BelongsTo && it.target === source }
        ?: error(
            "M2M sourceEdge \"${through.sourceEdge}\" does not match any belongsTo edge " +
                "on junction $junctionName targeting ${schemaNames[source] ?: "source"}. " +
                "Available belongsTo edges: ${junctionEdges.filter { it.kind is EdgeKind.BelongsTo }.map { it.name }}.",
        )
    val sourceBt = sourceEdge.kind as EdgeKind.BelongsTo
    val sourceFieldName = sourceBt.field
    val sourceFk = if (sourceFieldName != null) {
        resolveExplicitField(sourceFieldName, junctionSchema, sourceEdge.name)
    } else {
        "${sourceEdge.name}_id"
    }

    val targetEdge = junctionEdges.firstOrNull { it.name == through.targetEdge && it.kind is EdgeKind.BelongsTo && it.target === edge.target }
        ?: error(
            "M2M targetEdge \"${through.targetEdge}\" does not match any belongsTo edge " +
                "on junction $junctionName targeting ${schemaNames[edge.target] ?: "target"}. " +
                "Available belongsTo edges: ${junctionEdges.filter { it.kind is EdgeKind.BelongsTo }.map { it.name }}.",
        )
    if (sourceEdge === targetEdge) {
        error(
            "M2M edge \"${edge.name}\": sourceEdge and targetEdge resolved to the same " +
                "junction edge \"${sourceEdge.name}\" on $junctionName — the two refs must be distinct.",
        )
    }
    val targetBt = targetEdge.kind as EdgeKind.BelongsTo
    val targetFieldName = targetBt.field
    val targetFk = if (targetFieldName != null) {
        resolveExplicitField(targetFieldName, junctionSchema, targetEdge.name)
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
    val table = schema.tableName
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
            val join = if (edge.kind is EdgeKind.ManyToMany) {
                resolveM2MEdgeJoin(edge, schema, schemaNames)
            } else {
                resolveEdgeJoin(edge, schema)
            } ?: return@mapNotNull null
            EdgeEntry(edge.name, edge.target.tableName, join, edge.comment)
        }
    val reverseEntries = reverseM2MEdgeEntries(schema, schemaNames)
    val edgeEntries = forwardEntries + reverseEntries
    val seenEdges = mutableSetOf<String>()
    for (entry in edgeEntries) {
        require(seenEdges.add(entry.name)) {
            "Duplicate edge name '${entry.name}' — edge names must be unique per entity (including reverse M2M edges)"
        }
    }

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

    val schemaIndexes = schema.indexes()
    val idxColMap = indexableColumnMap(schema, schemaNames)
    val indexesLiteral = CodeBlock.builder()
    if (schemaIndexes.isEmpty()) {
        indexesLiteral.add("emptyList()")
    } else {
        indexesLiteral.add("listOf(\n")
        for (idx in schemaIndexes) {
            val fieldsLiteral = idx.fields.joinToString(", ") {
                val col = idxColMap[it] ?: error("Index references field '$it' but no field with that name exists on the schema")
                "\"$col\""
            }
            val cb = CodeBlock.builder()
                .add("  %T(columns = listOf($fieldsLiteral), unique = %L, name = %S", INDEX_METADATA, idx.unique, idx.name)
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
    // Canonical-identity keys of M2M edges [schema] declares for itself.
    // If `otherSchema` has a forward M2M targeting [schema] whose
    // canonical identity matches one of these, the explicit declaration
    // on [schema] owns the traversal surface, so we suppress the
    // default synthesized reverse (per RFC #3 "matching rule for two
    // explicit throughEntity declarations").
    val schemaDeclaredCanonicals = schema.edges()
        .mapNotNull { canonicalM2MIdentity(it) }
        .toSet()

    return schemaNames.flatMap { (otherSchema, _) ->
        otherSchema.edges()
            .filter { it.kind is EdgeKind.ManyToMany && it.target === schema }
            .mapNotNull { edge ->
                // Self-referential M2M: declaring schema and M2M target
                // are the same instance. RFC #3 explicitly skips reverse
                // synthesis here — there's no separate schema to host
                // the synthesized edge, and any name would collide with
                // the original declared edge. Callers that need
                // bidirectional traversal for a self-referential
                // throughEntity declare both edges explicitly with
                // pair-swapped orientation keys.
                if (otherSchema === schema) return@mapNotNull null

                // Opposite side declares the relationship explicitly —
                // suppress synthesis so we don't shadow / duplicate it.
                val canonical = canonicalM2MIdentity(edge)
                if (canonical != null && canonical in schemaDeclaredCanonicals) {
                    return@mapNotNull null
                }

                val forwardJoin = resolveM2MEdgeJoin(edge, otherSchema, schemaNames)
                    ?: return@mapNotNull null
                val reverseJoin = EdgeJoin(
                    sourceColumn = "id",
                    targetColumn = "id",
                    junctionTable = forwardJoin.junctionTable,
                    junctionSourceColumn = forwardJoin.junctionTargetColumn,
                    junctionTargetColumn = forwardJoin.junctionSourceColumn,
                )
                val reverseName = reverseM2MEdgeName(otherSchema, edge.name)
                val targetTable = otherSchema.tableName
                EdgeEntry(reverseName, targetTable, reverseJoin)
            }
    }
}

/**
 * A synthesized reverse M2M edge plus the metadata codegen needs to
 * wire up its user-facing traversal surface.
 *
 * [edge] is a fully-formed [Edge] shaped so the standard edge
 * consumers ([EntityGenerator] `EdgeRef` / `Edges` field,
 * [QueryGenerator] eager-loading) treat it like any declared
 * `manyToMany`: its `target` is the schema that declared the *forward*
 * edge, and its `through` carries the junction with `sourceEdge` /
 * `targetEdge` swapped so [resolveM2MEdgeJoin] walks the junction in
 * the reverse direction.
 *
 * [forwardEdgeName] is the original forward edge's name on the
 * declaring schema. Reverse query traversal lowers to a
 * `HasEdgeWith(forwardEdgeName, parent)` predicate against the
 * declaring schema's `SCHEMA.edges` map — it references the *forward*
 * edge, not a doubly-reversed synthesized name.
 */
internal data class SynthesizedReverseEdge(
    val edge: Edge,
    val forwardEdgeName: String,
)

/**
 * Reverse M2M edges that should gain a user-facing traversal surface
 * on [schema] — `queryX()`, `withX()`, an `EdgeRef`, and an `Edges`
 * field — per RFC #3's default-reverse-synthesis behavior.
 *
 * Only `throughEntity` forward edges qualify: RFC #3 explicitly says
 * `throughLink` relationships get no opposite-side read-only edge,
 * eager-loading, or predicate surface in V1. (The runtime `SCHEMA.edges`
 * reverse metadata in [reverseM2MEdgeEntries] is still emitted for
 * `throughLink` so forward query traversal keeps working — that's a
 * separate, non-user-facing concern.)
 *
 * Mirrors [reverseM2MEdgeEntries]'s suppression rules: self-referential
 * M2M and canonical-identity matches against an explicit declaration on
 * [schema] produce no synthesized surface.
 */
internal fun synthesizedReverseM2MEdges(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<SynthesizedReverseEdge> {
    val schemaDeclaredCanonicals = schema.edges()
        .mapNotNull { canonicalM2MIdentity(it) }
        .toSet()

    return schemaNames.flatMap { (otherSchema, _) ->
        otherSchema.edges()
            .filter { it.kind is EdgeKind.ManyToMany && it.target === schema }
            .mapNotNull { forwardEdge ->
                if (otherSchema === schema) return@mapNotNull null

                val m2m = forwardEdge.kind as EdgeKind.ManyToMany
                val through = m2m.through
                // throughLink gets no user-facing reverse surface in V1.
                if (through !is ManyToManyThrough.ThroughEntity) return@mapNotNull null

                val canonical = canonicalM2MIdentity(forwardEdge)
                if (canonical != null && canonical in schemaDeclaredCanonicals) {
                    return@mapNotNull null
                }

                // Sanity-check the join resolves before emitting a surface.
                resolveM2MEdgeJoin(forwardEdge, otherSchema, schemaNames)
                    ?: return@mapNotNull null

                val reverseName = reverseM2MEdgeName(otherSchema, forwardEdge.name)
                val reverseEdge = Edge(
                    name = reverseName,
                    target = otherSchema,
                    kind = EdgeKind.ManyToMany(
                        ManyToManyThrough.ThroughEntity(
                            junction = through.junction,
                            // Swap source/target so resolveM2MEdgeJoin walks
                            // the junction from [schema] back to otherSchema.
                            sourceEdge = through.targetEdge,
                            targetEdge = through.sourceEdge,
                        ),
                    ),
                    comment = null,
                )
                SynthesizedReverseEdge(reverseEdge, forwardEdge.name)
            }
    }
}

/**
 * Canonical relationship identity of an M2M edge — the junction schema
 * paired with the unordered set of junction-edge property names. Two
 * edges with the same canonical identity describe the same relationship
 * (possibly in opposite orientations); used to detect when one side
 * already owns the traversal surface and synthesis should be suppressed.
 */
internal fun canonicalM2MIdentity(edge: Edge): Triple<EntSchema, String, String>? {
    val m2m = edge.kind as? EdgeKind.ManyToMany ?: return null
    val through = m2m.through
    val (lo, hi) = if (through.sourceEdge <= through.targetEdge) {
        through.sourceEdge to through.targetEdge
    } else {
        through.targetEdge to through.sourceEdge
    }
    return Triple(through.junction, lo, hi)
}


/**
 * Compute the name for a reverse M2M edge entry on the target schema.
 * Incorporates both the source table name and the forward edge name so
 * that multiple M2M edges from the same source to the same target each
 * get their own unique reverse entry.
 */
internal fun reverseM2MEdgeName(sourceSchema: EntSchema, forwardEdgeName: String): String =
    "${sourceSchema.tableName}_$forwardEdgeName"
