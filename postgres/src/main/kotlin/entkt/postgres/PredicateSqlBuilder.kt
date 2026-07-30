package entkt.postgres

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.query.TraversalSourceShape
import entkt.runtime.driver.EntitySchema
import entkt.schema.ColumnStorage
import entkt.schema.FieldType

/**
 * Lowers a [Predicate] tree to a parameterized SQL fragment, accumulating
 * parameter bindings as the tree is walked. Each placeholder in the produced
 * SQL corresponds to one entry in [params], in order, so binding is just
 * `bind(stmt, i+1, p.type, p.value)`.
 *
 * One instance per statement: [params] is also appended to by the SELECT
 * builder for pgvector distance-ordering operands, so the placeholder order
 * stays aligned with the SQL text. Leaves become `"col" op ?`; edge
 * predicates become `EXISTS (...)` subqueries walking the registered
 * [entkt.runtime.driver.EdgeMetadata] — which is why the builder needs the
 * driver's whole schema [registry], not just the base table's schema. No
 * string concatenation of user values ever happens — only of column and
 * table identifiers (which originate in generated code, never user input).
 */
internal class PredicateSqlBuilder(
    private val registry: Map<String, EntitySchema>,
) {
    val params = mutableListOf<Param>()
    private var aliasCounter = 0

    fun nextAlias(): String = "t${++aliasCounter}"

    fun lower(predicate: Predicate<*>, schema: EntitySchema, alias: String): String =
        when (predicate) {
            is Predicate.Leaf<*> -> lowerLeaf(predicate, schema, alias)
            is Predicate.And<*> ->
                "(${lower(predicate.left, schema, alias)} AND ${lower(predicate.right, schema, alias)})"
            is Predicate.Or<*> ->
                "(${lower(predicate.left, schema, alias)} OR ${lower(predicate.right, schema, alias)})"
            is Predicate.HasEdge<*> -> lowerHasEdge(predicate.edge, null, schema, alias)
            is Predicate.HasEdgeWith<*, *> ->
                lowerHasEdge(predicate.edge, predicate.inner, schema, alias)
            is Predicate.HasM2MEdgeFrom<*, *> ->
                lowerInverseM2M(predicate, alias)
            is Predicate.HasEdgeFromShape<*, *> ->
                lowerEdgeFromShape(predicate, schema, alias)
            is Predicate.HasM2MEdgeFromShape<*, *> ->
                lowerM2MEdgeFromShape(predicate, alias)
        }

    /**
     * Render an ORDER BY clause body (no `ORDER BY` keyword) for
     * [orderBy] against [alias]-qualified columns of [schema],
     * appending any pgvector distance operands to [params] in SQL-text
     * order. Shared by the top-level SELECT builder and the shaped
     * traversal-source subquery so the two renderings can't drift.
     */
    fun orderBySql(orderBy: List<OrderField<*>>, schema: EntitySchema, alias: String): String =
        orderBy.joinToString(", ") { of ->
            val dir = if (of.direction == OrderDirection.ASC) "ASC" else "DESC"
            val distance = of.distance
            if (distance != null) {
                // pgvector distance ordering: `col <op> ?`, operand bound
                // as a vector parameter (never inlined). Appended to
                // params at render time so the placeholder order matches
                // the SQL text.
                //
                // Validate the operand dimension against the column's
                // declared dimensions here — bind() only sees FieldType,
                // so without this a wrong-size query vector would surface
                // as an opaque Postgres error instead of a field-named one.
                val operand = distance.operand
                val native = schema.nativeStorage(of.field)
                checkVectorDimensions(native, operand, "orderBy distance on '${schema.table}.${of.field}'")
                params.add(Param(FieldType.PGVECTOR, operand))
                // NULLS LAST always: a null embedding has no distance, so it
                // belongs at the end for both nearest-first (asc) and
                // farthest-first (desc). Postgres otherwise defaults nulls
                // FIRST on DESC, which would surface missing embeddings ahead
                // of the actual farthest vectors.
                "$alias.${quote(of.field)} ${distance.operator.sql} ? $dir NULLS LAST"
            } else {
                "$alias.${quote(of.field)} $dir"
            }
        }

    /**
     * Render a [TraversalSourceShape] as a `SELECT <selectedColumn>`
     * subquery preserving the source's predicates, order, limit, and
     * offset — the lowering that makes `queryX()` traversal follow
     * the source query as written.
     */
    private fun sourceShapeSubquery(shape: TraversalSourceShape<*>): String {
        val sourceSchema = registry[shape.table]
            ?: error("Traversal source shape references unregistered table ${shape.table}")
        val sAlias = nextAlias()
        val sql = StringBuilder()
        sql.append("SELECT ").append(sAlias).append('.').append(quote(shape.selectedColumn))
            .append(" FROM ").append(quote(shape.table)).append(" AS ").append(sAlias)
        val combined = shape.predicates.andTogether()
        if (combined != null) {
            sql.append(" WHERE ").append(lower(combined, sourceSchema, sAlias))
        }
        // Rendered whenever the source query carries an ordering,
        // even though without limit/offset the ORDER BY cannot change
        // which rows the subquery selects. Skipping it when bounds
        // are absent would let a malformed hand-built order field
        // (unknown column, wrong-dimension or non-vector distance
        // operand) silently succeed until someone adds a limit —
        // whereas the same source query run standalone renders its
        // ORDER BY unconditionally (buildSelectSql) and fails loudly.
        // Traversal follows the source query as written, failure
        // modes included: query validity must not depend on bounds.
        if (shape.orderBy.isNotEmpty()) {
            sql.append(" ORDER BY ").append(orderBySql(shape.orderBy, sourceSchema, sAlias))
        }
        if (shape.limit != null) sql.append(" LIMIT ").append(shape.limit)
        if (shape.offset != null) sql.append(" OFFSET ").append(shape.offset)
        return sql.toString()
    }

    /**
     * Lower [Predicate.HasEdgeFromShape] into
     * `candidate.<joinColumn> IN (<shaped source subquery>)`.
     * [predicate.edge] names the edge on the candidate that points
     * back at the traversal source, so the candidate-side join
     * column is that edge's `sourceColumn` and the subquery selects
     * the shape's `selectedColumn` (= the edge's `targetColumn`)
     * from the source table. `IN` is a filter on candidate rows —
     * it cannot duplicate them — and preserves exactly the source
     * rows the shape's predicates/order/limit/offset select.
     */
    private fun lowerEdgeFromShape(
        predicate: Predicate.HasEdgeFromShape<*, *>,
        candidateSchema: EntitySchema,
        candidateAlias: String,
    ): String {
        val edge = candidateSchema.edges[predicate.edge]
            ?: error("Edge ${candidateSchema.table}.${predicate.edge} has no metadata — was the schema registered?")
        return "$candidateAlias.${quote(edge.sourceColumn)} IN (${sourceShapeSubquery(predicate.source)})"
    }

    /**
     * Lower [Predicate.HasM2MEdgeFromShape] into a junction walk fed
     * by the shaped source subquery:
     * `candidate.id IN (SELECT j.targetCol FROM junction j WHERE
     * j.sourceCol IN (<shaped source subquery>))`. Like
     * [lowerInverseM2M], the junction is resolved from the *source*
     * schema's forward-edge metadata; the outer `IN` keeps the
     * candidate row set duplicate-free regardless of junction
     * fan-out.
     */
    private fun lowerM2MEdgeFromShape(
        predicate: Predicate.HasM2MEdgeFromShape<*, *>,
        candidateAlias: String,
    ): String {
        val sourceSchema = registry[predicate.source.table]
            ?: error("HasM2MEdgeFromShape: unregistered source table ${predicate.source.table}")
        val edge = sourceSchema.edges[predicate.edgeName]
            ?: error("HasM2MEdgeFromShape: edge ${predicate.source.table}.${predicate.edgeName} has no metadata")
        val junctionTable = edge.junctionTable
            ?: error("HasM2MEdgeFromShape: edge ${predicate.source.table}.${predicate.edgeName} is not M2M")

        val jAlias = nextAlias()
        return "$candidateAlias.${quote(edge.targetColumn)} IN" +
            " (SELECT $jAlias.${quote(edge.junctionTargetColumn!!)} FROM ${quote(junctionTable)} AS $jAlias" +
            " WHERE $jAlias.${quote(edge.junctionSourceColumn!!)} IN (${sourceShapeSubquery(predicate.source)}))"
    }

    /** Field-named label for a predicate operand on `schema.field`. */
    private fun predicateLabel(schema: EntitySchema, field: String): String =
        "predicate on '${schema.table}.$field'"

    private fun lowerLeaf(leaf: Predicate.Leaf<*>, schema: EntitySchema, alias: String): String {
        val col = "$alias.${quote(leaf.field)}"
        val type = schema.columnType(leaf.field)
        // Validate native operands (e.g. a wrong-dimension vector in
        // `embedding eq v` / `embedding in [...]`) here, so a predicate
        // gives the same field-named entkt error as writes and distance
        // ordering instead of falling through to an opaque Postgres error.
        // No-ops for non-native columns and non-PgVector operands; the
        // collection ops validate per-item in lowerInList.
        val native = schema.nativeStorage(leaf.field)
        checkVectorDimensions(native, leaf.value, predicateLabel(schema, leaf.field))
        return when (leaf.op) {
            Op.EQ -> {
                params.add(Param(type, leaf.value))
                "$col = ?"
            }
            Op.NEQ -> {
                params.add(Param(type, leaf.value))
                "$col <> ?"
            }
            Op.GT -> {
                params.add(Param(type, leaf.value))
                "$col > ?"
            }
            Op.GTE -> {
                params.add(Param(type, leaf.value))
                "$col >= ?"
            }
            Op.LT -> {
                params.add(Param(type, leaf.value))
                "$col < ?"
            }
            Op.LTE -> {
                params.add(Param(type, leaf.value))
                "$col <= ?"
            }
            Op.IS_NULL -> "$col IS NULL"
            Op.IS_NOT_NULL -> "$col IS NOT NULL"
            Op.IN -> lowerInList(col, leaf.value, type, native, predicateLabel(schema, leaf.field), negated = false)
            Op.NOT_IN -> lowerInList(col, leaf.value, type, native, predicateLabel(schema, leaf.field), negated = true)
            Op.CONTAINS -> {
                // Escape `%`, `_`, and `\` in the caller's value
                // before splicing it into the LIKE pattern, then
                // declare the escape char explicitly. Without this,
                // a caller passing `%` matches almost everything
                // (LIKE wildcard injection) instead of the intended
                // literal-substring semantics.
                params.add(Param(FieldType.STRING, "%${escapeLikePattern(leaf.value as String)}%"))
                "$col LIKE ? ESCAPE '\\'"
            }
            Op.HAS_PREFIX -> {
                params.add(Param(FieldType.STRING, "${escapeLikePattern(leaf.value as String)}%"))
                "$col LIKE ? ESCAPE '\\'"
            }
            Op.HAS_SUFFIX -> {
                params.add(Param(FieldType.STRING, "%${escapeLikePattern(leaf.value as String)}"))
                "$col LIKE ? ESCAPE '\\'"
            }
        }
    }

    /**
     * Escape `%`, `_`, and `\` in [value] so it can be safely
     * spliced into a `LIKE` pattern. Used by CONTAINS / HAS_PREFIX
     * / HAS_SUFFIX lowering, paired with `LIKE ? ESCAPE '\\'`.
     *
     * Without this, raw caller input flowing into the pattern lets
     * a value like `%` match almost everything (LIKE wildcard
     * injection) instead of the intended literal-substring
     * semantics.
     *
     * The escape char `\` is escaped first so the inserted escapes
     * in the next steps aren't double-escaped.
     */
    private fun escapeLikePattern(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\', '%', '_' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun lowerInList(
        col: String,
        value: Any?,
        type: FieldType?,
        native: ColumnStorage.Native?,
        label: String,
        negated: Boolean,
    ): String {
        val items = (value as Collection<*>).toList()
        if (items.isEmpty()) {
            // Empty IN: matches nothing. Empty NOT IN: matches everything.
            return if (negated) "TRUE" else "FALSE"
        }
        val placeholders = items.joinToString(", ") { "?" }
        for (item in items) {
            checkVectorDimensions(native, item, label)
            params.add(Param(type, item))
        }
        return if (negated) "$col NOT IN ($placeholders)" else "$col IN ($placeholders)"
    }

    private fun lowerHasEdge(
        edgeName: String,
        inner: Predicate<*>?,
        sourceSchema: EntitySchema,
        sourceAlias: String,
    ): String {
        val edge = sourceSchema.edges[edgeName]
            ?: error("Edge ${sourceSchema.table}.$edgeName has no metadata — was the schema registered?")
        val targetSchema = registry[edge.targetTable]
            ?: error("Edge ${sourceSchema.table}.$edgeName points at unregistered ${edge.targetTable}")

        // M2M edge: join through the junction table.
        if (edge.junctionTable != null) {
            val jAlias = nextAlias()
            val tAlias = nextAlias()
            val onClause = "$tAlias.${quote(edge.targetColumn)} = $jAlias.${quote(edge.junctionTargetColumn!!)}"
            val whereClause = "$jAlias.${quote(edge.junctionSourceColumn!!)} = $sourceAlias.${quote(edge.sourceColumn)}"
            val innerSql = inner?.let { lower(it, targetSchema, tAlias) }
            val fullWhere = if (innerSql == null) whereClause else "$whereClause AND $innerSql"
            return "EXISTS (SELECT 1 FROM ${quote(edge.junctionTable!!)} AS $jAlias" +
                " JOIN ${quote(edge.targetTable)} AS $tAlias ON $onClause" +
                " WHERE $fullWhere)"
        }

        // Direct edge: simple subquery.
        val targetAlias = nextAlias()
        val join = "$targetAlias.${quote(edge.targetColumn)} = $sourceAlias.${quote(edge.sourceColumn)}"
        val innerSql = inner?.let { lower(it, targetSchema, targetAlias) }
        val where = if (innerSql == null) join else "$join AND $innerSql"
        return "EXISTS (SELECT 1 FROM ${quote(edge.targetTable)} AS $targetAlias WHERE $where)"
    }

    /**
     * Lower [Predicate.HasM2MEdgeFrom] into an EXISTS subquery that
     * walks the junction backwards: candidate target row's id =
     * junction.targetCol = source.id; the optional source-side
     * filter applies to the source table.
     */
    private fun lowerInverseM2M(
        predicate: Predicate.HasM2MEdgeFrom<*, *>,
        candidateAlias: String,
    ): String {
        val sourceSchema = registry[predicate.sourceTable]
            ?: error("HasM2MEdgeFrom: unregistered source table ${predicate.sourceTable}")
        val edge = sourceSchema.edges[predicate.edgeName]
            ?: error("HasM2MEdgeFrom: edge ${predicate.sourceTable}.${predicate.edgeName} has no metadata")
        val junctionTable = edge.junctionTable
            ?: error("HasM2MEdgeFrom: edge ${predicate.sourceTable}.${predicate.edgeName} is not M2M")

        val jAlias = nextAlias()
        val sAlias = nextAlias()
        val joinJunctionToCandidate =
            "$jAlias.${quote(edge.junctionTargetColumn!!)} = $candidateAlias.${quote(edge.targetColumn)}"
        val joinJunctionToSource =
            "$jAlias.${quote(edge.junctionSourceColumn!!)} = $sAlias.${quote(edge.sourceColumn)}"
        val innerSql = predicate.sourceFilter?.let { lower(it, sourceSchema, sAlias) }
        val where = listOfNotNull(joinJunctionToCandidate, joinJunctionToSource, innerSql).joinToString(" AND ")
        return "EXISTS (SELECT 1 FROM ${quote(junctionTable)} AS $jAlias" +
            " JOIN ${quote(predicate.sourceTable)} AS $sAlias ON $joinJunctionToSource" +
            " WHERE $where)"
    }
}
