package entkt.codegen.metadata

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import entkt.codegen.columnName
import entkt.codegen.kotlinpoet.codeBlock
import entkt.runtime.driver.JsonMapperIds
import entkt.codegen.apiName
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.OnDelete

internal val ENTITY_SCHEMA = ClassName("entkt.runtime.driver", "EntitySchema")
internal val COLUMN_METADATA = ClassName("entkt.runtime.driver", "ColumnMetadata")
internal val EDGE_METADATA = ClassName("entkt.runtime.driver", "EdgeMetadata")
internal val INDEX_METADATA = ClassName("entkt.runtime.driver", "IndexMetadata")
internal val FOREIGN_KEY_REF = ClassName("entkt.runtime.driver", "ForeignKeyRef")
internal val ID_STRATEGY = ClassName("entkt.runtime.driver", "IdStrategy")
internal val FIELD_TYPE = ClassName("entkt.schema", "FieldType")
internal val ON_DELETE = ClassName("entkt.schema", "OnDelete")
internal val COLUMN_STORAGE_NATIVE = ClassName("entkt.schema", "ColumnStorage").nestedClass("Native")
internal val JSON_COLUMN_METADATA = ClassName("entkt.runtime.driver", "JsonColumnMetadata")
internal val JSON_MAPPER_IDS = ClassName("entkt.runtime.driver", "JsonMapperIds")
private val TYPE_OF = MemberName("kotlin.reflect", "typeOf")

/**
 * Emission for a JSON column's `mapper` field: the shared constant for the
 * built-in ids, a plain string literal for third-party codec ids (codegen
 * is deliberately open to unknown ids — a typo'd one is caught by the
 * driver's register() cross-check, never a silent fallback).
 */
private fun jsonMapperExpr(jsonMapper: String): CodeBlock = when (jsonMapper) {
    JsonMapperIds.KOTLINX -> CodeBlock.of("%T.KOTLINX", JSON_MAPPER_IDS)
    JsonMapperIds.JACKSON -> CodeBlock.of("%T.JACKSON", JSON_MAPPER_IDS)
    else -> CodeBlock.of("%S", jsonMapper)
}

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
 * codegen can fold it into either the runtime [entkt.runtime.driver.ColumnMetadata]
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
    /** True when the schema field was marked `.sensitive()`. */
    val sensitive: Boolean = false,
    /**
     * Raw default value from the field's `.default(...)` / `.defaultNow()`
     * modifier, or null. Carried through to
     * [entkt.runtime.driver.ColumnMetadata.default] on the migration path so the
     * differ can emit `DEFAULT` clauses.
     */
    val default: Any? = null,
    /** Native storage metadata (pgvector, etc.) from `Field.storage`, or null. */
    val storage: entkt.schema.ColumnStorage? = null,
    /** Full `@Serializable` Kotlin type for a JSON column (`Field.jsonType`), or null. */
    val jsonType: kotlin.reflect.KType? = null,
)

/**
 * Every column backing the entity, in declaration order: `id` first,
 * then declared fields, then any synthesized edge FKs. Used to build
 * the `columns` list on the generated [entkt.runtime.driver.EntitySchema]
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
                "Edge '${edge.apiName}' (storage '${edge.name}') references .field(\"$f\") but no field " +
                    "with that name exists on the schema",
            )
        val targetIdType = edge.target.id().type
        if (backingField.type != targetIdType) {
            error(
                "Edge '${edge.apiName}' (storage '${edge.name}') references .field(\"$f\") of type " +
                    "${backingField.type} but target entity's id type is $targetIdType",
            )
        }
        if (backingField.updateDefault != null) {
            // Update defaults on relationship FKs would silently
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
                "Edge '${edge.apiName}' (storage '${edge.name}') references .field(\"$f\") which carries " +
                    "an updateDefault — update defaults are not allowed on " +
                    "field-backed FK columns. Express the intent as a " +
                    "beforeUpdate or afterUpdate hook on the owner entity instead.",
            )
        }
        val existing = explicitFieldEdges.put(
            f,
            ExplicitFieldEdge(edge.name, edge.apiName, edge.target.tableName, belongsTo.onDelete, belongsTo.required, belongsTo.unique),
        )
        if (existing != null) {
            error(
                "Field '$f' is used as the backing field for both edge " +
                    "'${existing.edgeApiName}' (storage '${existing.edgeName}') and edge " +
                    "'${edge.apiName}' (storage '${edge.name}') — each backing field can only " +
                    "be used by one edge",
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
                        "Edge '${edgeRef.edgeApiName}' (storage '${edgeRef.edgeName}') is required but its backing field '${field.apiName}' (column '${field.name}') " +
                            "is nullable — a required edge needs a non-nullable backing field",
                    )
                }
                if (!edgeRef.required && !fieldNullable) {
                    error(
                        "Edge '${edgeRef.edgeApiName}' (storage '${edgeRef.edgeName}') is nullable but its backing field '${field.apiName}' (column '${field.name}') " +
                            "is non-null — a nullable edge needs a nullable backing field " +
                            "(add .nullable() to the field declaration)",
                    )
                }
                if (field.unique && !edgeRef.unique) {
                    error(
                        "Edge '${edgeRef.edgeApiName}' (storage '${edgeRef.edgeName}') is not .unique() but its backing field '${field.apiName}' (column '${field.name}') " +
                            "has a unique constraint — add .unique() to the edge or remove " +
                            ".unique() from the field",
                    )
                }
                if (edgeRef.onDelete == OnDelete.SET_NULL && !fieldNullable) {
                    error(
                        "ON DELETE SET_NULL on an edge backed by field '${field.apiName}' " +
                            "(column '${field.name}') requires " +
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
                    sensitive = field.sensitive,
                    default = field.default,
                    storage = field.storage,
                    jsonType = field.jsonType,
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
                    default = fk.default,
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
    /** Storage edge name. */
    val edgeName: String,
    /** Kotlin declaration name of the same edge. */
    val edgeApiName: String,
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
                    "Edge '${edge.apiName}' (storage '${edge.name}') is a ${edge.kind::class.simpleName} edge but no " +
                        "inverse belongsTo edge was found on the target schema. " +
                        "The target must declare a belongsTo(...) edge pointing back at the source.",
                )
            val inverseBt = inverse.kind as? EdgeKind.BelongsTo
                ?: error(
                    "Edge '${edge.apiName}' (storage '${edge.name}') resolved to inverse " +
                        "'${inverse.apiName}' (storage '${inverse.name}') " +
                        "but it is not a belongsTo edge",
                )
            if (edge.kind is EdgeKind.HasOne && !inverseBt.unique) {
                error(
                    "hasOne edge '${edge.apiName}' (storage '${edge.name}') requires its " +
                        "inverse belongsTo edge '${inverse.apiName}' (storage " +
                        "'${inverse.name}') to declare .unique()",
                )
            }
            if (edge.kind is EdgeKind.HasMany && inverseBt.unique) {
                error(
                    "hasMany edge '${edge.apiName}' (storage '${edge.name}') found inverse " +
                        "belongsTo edge '${inverse.apiName}' (storage '${inverse.name}') with " +
                        ".unique() — use hasOne instead of hasMany for one-to-one relationships",
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
                "Available belongsTo edges: " +
                "${junctionEdges.filter { it.kind is EdgeKind.BelongsTo }.map { "${it.apiName} (storage ${it.name})" }}.",
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
                "Available belongsTo edges: " +
                "${junctionEdges.filter { it.kind is EdgeKind.BelongsTo }.map { "${it.apiName} (storage ${it.name})" }}.",
        )
    if (sourceEdge === targetEdge) {
        error(
            "M2M edge \"${edge.apiName}\" (storage \"${edge.name}\"): sourceEdge and targetEdge " +
                "resolved to the same junction edge \"${sourceEdge.apiName}\" (storage " +
                "\"${sourceEdge.name}\") on $junctionName — the two refs must be distinct.",
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
    /**
     * Which JSON mapper the generated metadata targets. Stamped into every
     * JSON column's `JsonColumnMetadata.mapper`; kotlinx additionally emits
     * the statically-resolved serializer expression.
     */
    jsonMapper: String = JsonMapperIds.KOTLINX,
): CodeBlock {
    val table = schema.tableName
    val columns = columnMetadataFor(schema, schemaNames)
    // Note: ColumnMetadata.default is intentionally omitted from this
    // runtime literal — it is consumed only on the migration path (which
    // builds ColumnMetadata via buildEntitySchemas, not this literal), and
    // the generated create() applies defaults from Field.default directly.
    // See ColumnMetadata.default's KDoc.
    val columnsLiteral = codeBlock {
        add("listOf(\n")
        for (col in columns) {
            add(codeBlock {
                add("  %T(name = %S, type = %T.%L, nullable = %L, primaryKey = %L, unique = %L",
                        COLUMN_METADATA, col.name, FIELD_TYPE, col.type.name,
                        col.nullable, col.primaryKey, col.unique)
                if (col.references != null) {
                    val (refTable, refCol) = col.references
                    if (col.onDelete != null) {
                        add(", references = %T(table = %S, column = %S, onDelete = %T.%L)",
                            FOREIGN_KEY_REF, refTable, refCol, ON_DELETE, col.onDelete.name)
                    } else {
                        add(", references = %T(table = %S, column = %S)",
                            FOREIGN_KEY_REF, refTable, refCol)
                    }
                }
                if (col.comment != null) {
                    add(", comment = %S", col.comment)
                }
                if (col.sensitive) {
                    add(", sensitive = true")
                }
                val storage = col.storage
                if (storage is entkt.schema.ColumnStorage.Native) {
                    if (storage.requiredExtension != null) {
                        add(
                            ", storage = %T(dialect = %S, typeName = %S, sqlType = %S, codec = %S, requiredExtension = %S, dimensions = %L)",
                            COLUMN_STORAGE_NATIVE, storage.dialect, storage.typeName, storage.sqlType,
                            storage.codec, storage.requiredExtension, storage.dimensions,
                        )
                    } else {
                        add(
                            ", storage = %T(dialect = %S, typeName = %S, sqlType = %S, codec = %S, requiredExtension = null, dimensions = %L)",
                            COLUMN_STORAGE_NATIVE, storage.dialect, storage.typeName, storage.sqlType,
                            storage.codec, storage.dimensions,
                        )
                    }
                }
                val jsonType = col.jsonType
                if (jsonType != null) {
                    // json = JsonColumnMetadata(klass = X::class, kType = typeOf<...>(),
                    //        typeName = "...", mapper = ..., [kotlinxSerializer = <expr>]).
                    // klass stays the raw classifier (List::class for List<Rect>) — it backs
                    // the driver's erased isInstance write check; kType is the mapper-neutral
                    // carrier reflective codecs (Jackson) build from; typeName carries the
                    // full type for diagnostics. Only the kotlinx mapper emits the serializer
                    // expression — its statically-resolved references (X.serializer(),
                    // ListSerializer(X.serializer()), ...) are what make a type without
                    // kotlinx support fail at compile time; other mappers must not reference
                    // symbols the serialization plugin would have generated.
                    val raw = jsonType.classifier as? kotlin.reflect.KClass<*>
                        ?: error("JSON column '${col.name}': type '$jsonType' is not a concrete class")
                    add(
                        ", json = %T(klass = %T::class, kType = %M<%T>(), typeName = %S, mapper = %L",
                        JSON_COLUMN_METADATA,
                        raw.asClassName(),
                        TYPE_OF,
                        jsonType.asTypeName(),
                        jsonType.toString(),
                        jsonMapperExpr(jsonMapper),
                    )
                    if (jsonMapper == JsonMapperIds.KOTLINX) {
                        add(", kotlinxSerializer = %L", jsonSerializerCodeBlock(col.name, jsonType))
                    }
                    add(")")
                }
                add("),\n")
            })
        }
        add(")")
    }

    val edgeEntries = schema.edges()
        .mapNotNull { edge ->
            val join = if (edge.kind is EdgeKind.ManyToMany) {
                resolveM2MEdgeJoin(edge, schema, schemaNames)
            } else {
                resolveEdgeJoin(edge, schema)
            } ?: return@mapNotNull null
            EdgeEntry(edge.name, edge.target.tableName, join, edge.comment)
        }
    val seenEdges = mutableSetOf<String>()
    for (entry in edgeEntries) {
        require(seenEdges.add(entry.name)) {
            "Duplicate edge name '${entry.name}' — edge names must be unique per entity"
        }
    }

    val edgesLiteral = codeBlock {
        if (edgeEntries.isEmpty()) {
            add("emptyMap()")
        } else {
            add("mapOf(\n")
            for (entry in edgeEntries) {
                add(codeBlock {
                    add("  %S to %T(targetTable = %S, sourceColumn = %S, targetColumn = %S",
                    entry.name, EDGE_METADATA, entry.targetTable,
                    entry.join.sourceColumn, entry.join.targetColumn)
                    if (entry.join.junctionTable != null) {
                        add(", junctionTable = %S, junctionSourceColumn = %S, junctionTargetColumn = %S",
                            entry.join.junctionTable, entry.join.junctionSourceColumn,
                            entry.join.junctionTargetColumn)
                    }
                    if (entry.comment != null) add(", comment = %S", entry.comment)
                    add("),\n")
                })
            }
            add(")")
        }
    }

    val schemaIndexes = schema.indexes()
    val idxColMap = indexableColumnMap(schema, schemaNames)
    val indexesLiteral = codeBlock {
        if (schemaIndexes.isEmpty()) {
            add("emptyList()")
        } else {
            add("listOf(\n")
            for (idx in schemaIndexes) {
                val fieldsLiteral = idx.fields.joinToString(", ") {
                    val col = idxColMap[it]
                        ?: error("Index references field '$it' but no field with that name exists on the schema")
                    "\"$col\""
                }
                add(codeBlock {
                    add("  %T(columns = listOf($fieldsLiteral), unique = %L, name = %S", INDEX_METADATA, idx.unique, idx.name)
                    if (idx.where != null) add(", where = %S", idx.where)
                    if (idx.using != null) add(", using = %S", idx.using)
                    if (idx.opclasses != null) {
                        val opcs = idx.opclasses!!.joinToString(", ") { "\"$it\"" }
                        add(", opclasses = listOf($opcs)")
                    }
                    if (idx.with != null) {
                        val entries = idx.with!!.entries.joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }
                        add(", with = mapOf($entries)")
                    }
                    add("),\n")
                })
            }
            add(")")
        }
    }

    return codeBlock {
        add("%T(\n", ENTITY_SCHEMA)
        add("  table = %S,\n", table)
        add("  idColumn = %S,\n", "id")
        add("  idStrategy = %T.%L,\n", ID_STRATEGY, idStrategyName(schema))
        add("  columns = %L,\n", columnsLiteral)
        add("  edges = %L,\n", edgesLiteral)
        add("  indexes = %L,\n", indexesLiteral)
        add(")")
    }
}

/**
 * One forward edge entry as it appears in a generated entity's
 * `SCHEMA.edges` map. M2M traversal predicates
 * ([Predicate.HasM2MEdgeFromShape], and the predicate-only
 * [Predicate.HasM2MEdgeFrom]) resolve the source's forward edge
 * directly; no reverse-edge entries are synthesized on the target
 * schema.
 */
internal data class EdgeEntry(
    val name: String,
    val targetTable: String,
    val join: EdgeJoin,
    val comment: String? = null,
)

/**
 * Canonical relationship identity of an M2M edge — the junction schema
 * paired with the unordered set of junction-edge property names. Two
 * edges with the same canonical identity describe the same relationship
 * (possibly in opposite orientations); used at codegen-validation time
 * to detect duplicate / pair-swapped declarations.
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
