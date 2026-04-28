package entkt.codegen

import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.OnDelete

/**
 * Shared schema inspection layer. Validates and explains the resolved
 * relational shape of a schema graph without requiring a live database
 * or running codegen.
 */
object SchemaInspector {

    /**
     * Validate the schema graph: finalization, cross-schema constraints,
     * member-name collisions, and relation-name uniqueness. Returns a
     * [ValidationResult] rather than throwing.
     */
    fun validate(inputs: List<SchemaInput>): ValidationResult {
        val errors = mutableListOf<String>()

        // Finalization can leave schemas in a partially-resolved state on
        // failure, so if it fails we report the error and stop early.
        try {
            ensureFinalized(inputs)
        } catch (e: Exception) {
            return ValidationResult(valid = false, errors = listOf(e.message ?: e.toString()))
        }

        // Run column/edge/index resolution per schema, collecting all
        // errors rather than stopping at the first one.
        val schemaNames = inputs.associate { it.schema to it.name }
        for (input in inputs) {
            try {
                validateSchema(input, schemaNames, errors)
            } catch (e: Exception) {
                errors.add(e.message ?: e.toString())
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult(valid = true, errors = emptyList())
        } else {
            ValidationResult(valid = false, errors = errors)
        }
    }

    private fun validateSchema(
        input: SchemaInput,
        schemaNames: Map<EntSchema, String>,
        errors: MutableList<String>,
    ) {
        try {
            columnMetadataFor(input.schema, schemaNames)
        } catch (e: Exception) {
            // Column resolution touches fields() and edges() internally.
            // If it fails, further edge/index validation will likely
            // re-trigger the same root cause. Stop early for this schema.
            errors.add(e.message ?: e.toString())
            return
        }

        // Resolve edges — guard the edges() accessor so that duplicate
        // edge names or broken finalization state doesn't skip the rest.
        val edges = try {
            input.schema.edges()
        } catch (e: Exception) {
            errors.add(e.message ?: e.toString())
            null
        }
        if (edges != null) {
            for (edge in edges) {
                try {
                    if (edge.kind is EdgeKind.ManyToMany) {
                        resolveM2MEdgeJoin(edge, input.schema, schemaNames)
                    } else {
                        resolveEdgeJoin(edge, input.schema)
                    }
                } catch (e: Exception) {
                    errors.add(e.message ?: e.toString())
                }
            }

            // Check reverse M2M edge name collisions — codegen appends
            // reverse entries and fails if a declared edge or another
            // reverse entry uses the synthesized name.
            try {
                val forwardNames = edges.map { it.name }.toSet()
                val reverseNames = mutableSetOf<String>()
                for (reverse in reverseM2MEdgeEntries(input.schema, schemaNames)) {
                    if (reverse.name in forwardNames) {
                        errors.add(
                            "Schema '${input.name}': reverse M2M edge '${reverse.name}' " +
                                "collides with a declared edge of the same name",
                        )
                    }
                    if (!reverseNames.add(reverse.name)) {
                        errors.add(
                            "Schema '${input.name}': duplicate reverse M2M edge '${reverse.name}'",
                        )
                    }
                }
            } catch (e: Exception) {
                errors.add(e.message ?: e.toString())
            }
        }

        // Resolve indexes — guard both indexableColumnMap and indexes().
        val idxColMap = try {
            indexableColumnMap(input.schema, schemaNames)
        } catch (e: Exception) {
            errors.add(e.message ?: e.toString())
            return
        }
        val indexes = try {
            input.schema.indexes()
        } catch (e: Exception) {
            errors.add(e.message ?: e.toString())
            return
        }
        for (idx in indexes) {
            for (field in idx.fields) {
                if (idxColMap[field] == null) {
                    errors.add(
                        "Index '${idx.name}' references field '$field' but no field " +
                            "with that name exists on schema '${input.name}'",
                    )
                }
            }
        }
    }

    /**
     * Build a normalized, inspectable view of the schema graph.
     * Runs the full validation path first — if the schema graph is
     * invalid, throws rather than rendering a misleading output.
     * Inputs do not need to be pre-finalized.
     */
    fun explain(inputs: List<SchemaInput>): ExplainedSchemaGraph {
        val validation = validate(inputs)
        if (!validation.valid) {
            error(
                "Schema validation failed:\n" +
                    validation.errors.joinToString("\n") { "  - $it" },
            )
        }
        val schemaNames = inputs.associate { it.schema to it.name }
        val schemas = inputs.map { input ->
            explainSchema(input.name, input.schema, schemaNames)
        }
        return ExplainedSchemaGraph(schemas)
    }

    /**
     * Filter an explained graph to only schemas whose schema name or table
     * name contains [filter] (case-insensitive). Returns the full graph
     * if [filter] is null or blank.
     */
    fun filter(graph: ExplainedSchemaGraph, filter: String?): ExplainedSchemaGraph {
        if (filter.isNullOrBlank()) return graph
        val lower = filter.lowercase()
        val filtered = graph.schemas.filter {
            it.schemaName.lowercase().contains(lower) ||
                it.tableName.lowercase().contains(lower)
        }
        return ExplainedSchemaGraph(filtered)
    }

    /**
     * Render an [ExplainedSchemaGraph] as human-readable text matching the
     * format described in the schema-validation-explain RFC.
     */
    fun renderText(graph: ExplainedSchemaGraph): String {
        return graph.schemas.joinToString("\n") { renderSchemaText(it) }
    }

    /**
     * Render an [ExplainedSchemaGraph] as deterministic JSON suitable for
     * golden tests, tooling, and editor integrations.
     */
    fun renderJson(graph: ExplainedSchemaGraph): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"schemas\": [")
        for ((i, schema) in graph.schemas.withIndex()) {
            renderSchemaJson(sb, schema, indent = "    ")
            if (i < graph.schemas.lastIndex) sb.appendLine(",") else sb.appendLine()
        }
        sb.appendLine("  ]")
        sb.append("}")
        return sb.toString()
    }

    private fun explainSchema(
        name: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): ExplainedSchema {
        val id = ExplainedId(
            type = schema.id().type,
            strategy = idStrategyName(schema),
        )

        // Use the resolved column metadata so that edge-driven modifiers
        // (e.g. belongsTo().field(handle).unique()) are reflected on the
        // backing field rather than only on the raw field declaration.
        val columns = columnMetadataFor(schema, schemaNames)
        val columnByName = columns.associateBy { it.name }

        val fields = schema.fields().map { field ->
            val resolved = columnByName[field.name]
            ExplainedField(
                name = field.name,
                type = field.type,
                nullable = resolved?.nullable ?: field.nullable,
                unique = resolved?.unique ?: field.unique,
                immutable = field.immutable,
                sensitive = field.sensitive,
                default = formatDefault(field.default),
                comment = field.comment,
            )
        }

        val foreignKeys = buildForeignKeys(schema, schemaNames)
        val edges = buildEdges(schema, schemaNames)
        val indexes = buildIndexes(schema, schemaNames)

        return ExplainedSchema(
            schemaName = name,
            tableName = schema.tableName,
            id = id,
            fields = fields,
            foreignKeys = foreignKeys,
            edges = edges,
            indexes = indexes,
        )
    }

    /**
     * Build FK entries from belongsTo edges. Each belongsTo edge owns
     * exactly one FK column — either an explicit `.field(handle)` or
     * the synthesized `${edgeName}_id`.
     */
    private fun buildForeignKeys(
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): List<ExplainedForeignKey> {
        return schema.edges().mapNotNull { edge ->
            val bt = edge.kind as? EdgeKind.BelongsTo ?: return@mapNotNull null
            val explicitField = bt.field
            val fkColumn = explicitField ?: "${edge.name}_id"
            val nullable = if (explicitField != null) {
                schema.fields().find { it.name == explicitField }?.nullable ?: !bt.required
            } else {
                !bt.required
            }
            val effectiveOnDelete = bt.onDelete
                ?: if (nullable) OnDelete.SET_NULL else OnDelete.RESTRICT
            ExplainedForeignKey(
                column = fkColumn,
                targetTable = edge.target.tableName,
                targetColumn = "id",
                nullable = nullable,
                onDelete = effectiveOnDelete.name,
                sourceEdge = edge.name,
            )
        }
    }

    private fun buildEdges(
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): List<ExplainedEdge> {
        val declared = schema.edges().map { edge ->
            val targetName = schemaNames[edge.target] ?: edge.target::class.simpleName ?: "?"
            when (val kind = edge.kind) {
                is EdgeKind.BelongsTo -> {
                    val fkColumn = kind.field ?: "${edge.name}_id"
                    val inverse = tryFindInverseName(edge, schema)
                    ExplainedEdge(
                        name = edge.name,
                        kind = "belongsTo",
                        targetSchema = targetName,
                        fkColumn = fkColumn,
                        inverse = inverse,
                        comment = edge.comment,
                    )
                }
                is EdgeKind.HasMany -> {
                    val inverse = tryFindInverseName(edge, schema)
                    ExplainedEdge(
                        name = edge.name,
                        kind = "hasMany",
                        targetSchema = targetName,
                        inverse = inverse,
                        comment = edge.comment,
                    )
                }
                is EdgeKind.HasOne -> {
                    val inverse = tryFindInverseName(edge, schema)
                    ExplainedEdge(
                        name = edge.name,
                        kind = "hasOne",
                        targetSchema = targetName,
                        inverse = inverse,
                        comment = edge.comment,
                    )
                }
                is EdgeKind.ManyToMany -> {
                    val through = kind.through
                    val junctionTable = through.target.tableName
                    ExplainedEdge(
                        name = edge.name,
                        kind = "manyToMany",
                        targetSchema = targetName,
                        through = ExplainedThrough(
                            junctionTable = junctionTable,
                            sourceEdge = through.sourceEdge ?: "",
                            targetEdge = through.targetEdge ?: "",
                        ),
                        comment = edge.comment,
                    )
                }
            }
        }

        // Include synthesized reverse M2M edges — these are injected by
        // buildEntitySchemas() at runtime and used for M2M traversals from
        // the target side.
        val reverse = buildReverseM2MEdges(schema, schemaNames)

        return declared + reverse
    }

    /**
     * Build explained edges for reverse M2M entries that codegen
     * synthesizes on the target side of each manyToMany declaration.
     */
    private fun buildReverseM2MEdges(
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): List<ExplainedEdge> {
        return schemaNames.flatMap { (otherSchema, otherName) ->
            otherSchema.edges()
                .filter { it.kind is EdgeKind.ManyToMany && it.target === schema }
                .mapNotNull { edge ->
                    val m2m = edge.kind as EdgeKind.ManyToMany
                    val through = m2m.through
                    val junctionTable = through.target.tableName
                    val reverseName = reverseM2MEdgeName(otherSchema, edge.name)
                    ExplainedEdge(
                        name = reverseName,
                        kind = "manyToMany",
                        targetSchema = otherName,
                        through = ExplainedThrough(
                            junctionTable = junctionTable,
                            // Reverse: source/target edges are swapped
                            sourceEdge = through.targetEdge ?: "",
                            targetEdge = through.sourceEdge ?: "",
                        ),
                    )
                }
        }
    }

    /**
     * Format a field default for display. Enum constants use [Enum.name]
     * rather than [toString] so that custom toString overrides don't
     * produce a value that diverges from what codegen/runtime emit.
     */
    private fun formatDefault(value: Any?): String? {
        if (value == null) return null
        if (value is Enum<*>) return value.name
        return value.toString()
    }

    private fun tryFindInverseName(edge: entkt.schema.Edge, source: EntSchema): String? {
        return try {
            findInverseEdge(edge, source)?.name
        } catch (_: Exception) {
            null
        }
    }

    private fun buildIndexes(
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): List<ExplainedIndex> {
        val idxColMap = indexableColumnMap(schema, schemaNames)
        val explicit = schema.indexes().map { idx ->
            ExplainedIndex(
                name = normalizeIdentifier(idx.name),
                columns = idx.fields.map { idxColMap[it] ?: it },
                unique = idx.unique,
                where = idx.where,
            )
        }

        // The Postgres driver emits CREATE UNIQUE INDEX idx_<table>_<col>_unique
        // for every non-PK unique column. Include these so the explain output
        // shows all indexes that will actually be emitted.
        val table = schema.tableName
        val columns = columnMetadataFor(schema, schemaNames)
        val synthesized = columns
            .filter { it.unique && !it.primaryKey }
            .map { col ->
                ExplainedIndex(
                    name = normalizeIdentifier("idx_${table}_${col.name}_unique"),
                    columns = listOf(col.name),
                    unique = true,
                )
            }

        return explicit + synthesized
    }

    // ── Text rendering ──────────────────────────────────────────────

    private fun renderSchemaText(schema: ExplainedSchema): String {
        val sb = StringBuilder()
        sb.appendLine("Schema: ${schema.schemaName}")
        sb.appendLine("Table: ${schema.tableName}")
        sb.appendLine("Id: ${schema.id.type} (${schema.id.strategy})")

        if (schema.fields.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Fields:")
            val rows = schema.fields.map { field ->
                val attrs = buildList {
                    if (field.nullable) add("NULL") else add("NOT NULL")
                    if (field.immutable) add("immutable")
                    if (field.sensitive) add("sensitive")
                    if (field.unique) add("unique")
                    if (field.default != null) add("DEFAULT ${field.default}")
                }.joinToString(", ")
                listOf(field.name, field.type.name, attrs)
            }
            sb.append(renderTable(listOf("Name", "Type", "Attributes"), rows))
        }

        if (schema.foreignKeys.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Foreign Keys:")
            val rows = schema.foreignKeys.map { fk ->
                listOf(
                    fk.column,
                    "${fk.targetTable}.${fk.targetColumn}",
                    if (fk.nullable) "NULL" else "NOT NULL",
                    fk.onDelete,
                    fk.sourceEdge,
                )
            }
            sb.append(renderTable(listOf("Column", "References", "Nullable", "On Delete", "Source Edge"), rows))
        }

        if (schema.edges.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Edges:")
            val rows = schema.edges.map { edge ->
                val detail = buildString {
                    if (edge.fkColumn != null) append("fk=${edge.fkColumn}")
                    if (edge.inverse != null) {
                        if (isNotEmpty()) append(", ")
                        append("inverse=${edge.inverse}")
                    }
                    if (edge.through != null) {
                        val t = edge.through
                        if (isNotEmpty()) append(", ")
                        append("through=${t.junctionTable}(${t.sourceEdge}, ${t.targetEdge})")
                    }
                }
                listOf(edge.name, edge.kind, edge.targetSchema, detail)
            }
            sb.append(renderTable(listOf("Name", "Kind", "Target", "Details"), rows))
        }

        if (schema.indexes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Indexes:")
            val rows = schema.indexes.map { idx ->
                val cols = idx.columns.joinToString(", ")
                val attrs = buildList {
                    if (idx.unique) add("unique")
                    if (idx.where != null) add("WHERE ${idx.where}")
                }.joinToString(", ")
                listOf(idx.name, "($cols)", attrs)
            }
            sb.append(renderTable(listOf("Name", "Columns", "Attributes"), rows))
        }

        sb.appendLine()
        return sb.toString()
    }

    private fun renderTable(headers: List<String>, rows: List<List<String>>): String {
        val colCount = headers.size
        // Sanitize cell contents: escape | and replace newlines so they
        // don't break the markdown table structure.
        val sanitized = rows.map { row ->
            row.map { cell -> cell.replace("|", "\\|").replace("\n", " ").replace("\r", "") }
        }
        val widths = IntArray(colCount) { col ->
            maxOf(headers[col].length, sanitized.maxOfOrNull { it[col].length } ?: 0)
        }

        val sb = StringBuilder()
        // Header
        sb.append("| ")
        for (col in 0 until colCount) {
            if (col > 0) sb.append(" | ")
            sb.append(headers[col].padEnd(widths[col]))
        }
        sb.appendLine(" |")
        // Separator
        sb.append("|")
        for (col in 0 until colCount) {
            sb.append("-")
            sb.append("-".repeat(widths[col]))
            sb.append("-|")
        }
        sb.appendLine()
        // Rows
        for (row in sanitized) {
            sb.append("| ")
            for (col in 0 until colCount) {
                if (col > 0) sb.append(" | ")
                sb.append(row[col].padEnd(widths[col]))
            }
            sb.appendLine(" |")
        }
        return sb.toString()
    }

    // ── JSON rendering ─────────────────────────────────────────────

    private fun renderSchemaJson(sb: StringBuilder, schema: ExplainedSchema, indent: String) {
        sb.appendLine("$indent{")
        sb.appendLine("$indent  \"schemaName\": ${jsonString(schema.schemaName)},")
        sb.appendLine("$indent  \"tableName\": ${jsonString(schema.tableName)},")
        sb.appendLine("$indent  \"id\": { \"type\": ${jsonString(schema.id.type.name)}, \"strategy\": ${jsonString(schema.id.strategy)} },")

        // Fields
        sb.appendLine("$indent  \"fields\": [")
        for ((i, f) in schema.fields.withIndex()) {
            val comma = if (i < schema.fields.lastIndex) "," else ""
            sb.append("$indent    { \"name\": ${jsonString(f.name)}, \"type\": ${jsonString(f.type.name)}, \"nullable\": ${f.nullable}")
            if (f.unique) sb.append(", \"unique\": true")
            if (f.immutable) sb.append(", \"immutable\": true")
            if (f.sensitive) sb.append(", \"sensitive\": true")
            if (f.default != null) sb.append(", \"default\": ${jsonString(f.default)}")
            if (f.comment != null) sb.append(", \"comment\": ${jsonString(f.comment)}")
            sb.appendLine(" }$comma")
        }
        sb.appendLine("$indent  ],")

        // Foreign Keys
        sb.appendLine("$indent  \"foreignKeys\": [")
        for ((i, fk) in schema.foreignKeys.withIndex()) {
            val comma = if (i < schema.foreignKeys.lastIndex) "," else ""
            sb.appendLine("$indent    { \"column\": ${jsonString(fk.column)}, \"targetTable\": ${jsonString(fk.targetTable)}, \"targetColumn\": ${jsonString(fk.targetColumn)}, \"nullable\": ${fk.nullable}, \"onDelete\": ${jsonString(fk.onDelete)}, \"sourceEdge\": ${jsonString(fk.sourceEdge)} }$comma")
        }
        sb.appendLine("$indent  ],")

        // Edges
        sb.appendLine("$indent  \"edges\": [")
        for ((i, e) in schema.edges.withIndex()) {
            val comma = if (i < schema.edges.lastIndex) "," else ""
            sb.append("$indent    { \"name\": ${jsonString(e.name)}, \"kind\": ${jsonString(e.kind)}, \"targetSchema\": ${jsonString(e.targetSchema)}")
            if (e.fkColumn != null) sb.append(", \"fkColumn\": ${jsonString(e.fkColumn)}")
            if (e.inverse != null) sb.append(", \"inverse\": ${jsonString(e.inverse)}")
            if (e.through != null) {
                sb.append(", \"through\": { \"junctionTable\": ${jsonString(e.through.junctionTable)}, \"sourceEdge\": ${jsonString(e.through.sourceEdge)}, \"targetEdge\": ${jsonString(e.through.targetEdge)} }")
            }
            if (e.comment != null) sb.append(", \"comment\": ${jsonString(e.comment)}")
            sb.appendLine(" }$comma")
        }
        sb.appendLine("$indent  ],")

        // Indexes
        sb.appendLine("$indent  \"indexes\": [")
        for ((i, idx) in schema.indexes.withIndex()) {
            val comma = if (i < schema.indexes.lastIndex) "," else ""
            val cols = idx.columns.joinToString(", ") { jsonString(it) }
            sb.append("$indent    { \"name\": ${jsonString(idx.name)}, \"columns\": [$cols], \"unique\": ${idx.unique}")
            if (idx.where != null) sb.append(", \"where\": ${jsonString(idx.where)}")
            sb.appendLine(" }$comma")
        }
        sb.appendLine("$indent  ]")

        sb.append("$indent}")
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Normalize an identifier to Postgres's 63-byte NAMEDATALEN limit.
     * Mirrors the truncation+hash logic in [PostgresTypeMapper] so that
     * explain text/json output matches the actual database names.
     */
    private fun normalizeIdentifier(name: String): String {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 63) return name
        // 9 = underscore + 8 hex chars
        var prefixLen = 63 - 9
        while (prefixLen > 0 && (bytes[prefixLen].toInt() and 0xC0) == 0x80) prefixLen--
        val prefix = String(bytes, 0, prefixLen, Charsets.UTF_8)
        val hash = name.hashCode().toUInt().toString(16).padStart(8, '0')
        return "${prefix}_$hash"
    }
}
