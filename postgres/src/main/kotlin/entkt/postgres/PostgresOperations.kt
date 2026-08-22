@file:OptIn(entkt.query.EntktInternal::class)

package entkt.postgres

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.query.EagerWindowStrategy
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.RelatedRow
import entkt.runtime.driver.RelatedRows
import entkt.schema.FieldType
import java.sql.Connection
import java.sql.ResultSet

// Aggregate metric-column type compatibility (mirrors the generated column
// markers): sum/avg are numeric-only; min/max are over comparable scalars.
private val NUMERIC_FIELD_TYPES =
    setOf(FieldType.INT, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE)
private val COMPARABLE_FIELD_TYPES =
    NUMERIC_FIELD_TYPES + setOf(FieldType.STRING, FieldType.TEXT, FieldType.TIME)
// Group keys add the non-comparable scalars (bool/uuid/enum); bytes, pgvector,
// and JSON are excluded (they have no group-key meaning).
private val GROUPABLE_FIELD_TYPES =
    COMPARABLE_FIELD_TYPES + setOf(FieldType.BOOL, FieldType.UUID, FieldType.ENUM)

/**
 * Reject a rendered statement whose FINAL bind-parameter count exceeds
 * PostgreSQL's protocol limit — deterministically, before the statement
 * is prepared or any I/O happens — instead of surfacing the JDBC
 * driver's opaque protocol error ("Tried to send an out-of-range
 * integer as a 2-byte value").
 *
 * Called by every operation whose parameter count is data-dependent:
 * `query` (which serves eager relationship loads — their `IN (...)`
 * lists grow with the parent set and are not yet chunked), `count`,
 * `exists`, `aggregate`, `updateMany`, and `deleteMany`. The count is
 * the statement's complete parameter list: relationship IDs, caller and
 * interceptor predicates, and ordering operands all consume binds.
 * An oversized `IN` list — the one caller-unbounded amplification
 * point — is rejected earlier still, by
 * [PredicateSqlBuilder.lowerInList]'s projected-size pre-check, before
 * any placeholder or parameter is allocated; this post-render check
 * covers everything else (combined SET-plus-predicate counts, many
 * small predicates, ordering operands).
 *
 * Deliberately NOT called by: `insertMany` and `deleteManyByIds`, which
 * chunk physical statements under the limit by construction; the
 * single-row operations (`insert`, `update`, `byId`, `delete`, row
 * locks), whose parameter count is bounded by the schema's column count
 * (PostgreSQL caps tables at 1,600 columns).
 */
private fun checkBindLimit(paramCount: Int, operation: String, table: String) {
    if (paramCount > POSTGRES_BIND_PARAMETER_LIMIT) {
        throw PostgresBindLimitException(
            "PostgreSQL $operation on \"$table\" requires " +
                "%,d".format(java.util.Locale.ROOT, paramCount) +
                " bind parameters; PostgreSQL supports at most " +
                "%,d".format(java.util.Locale.ROOT, POSTGRES_BIND_PARAMETER_LIMIT) +
                ". Reduce the root result size or split the query. " +
                "Large relationship batches are not yet chunked.",
        )
    }
}

/**
 * The Postgres driver's operation core: every read/write/lock operation as a
 * function of an explicit JDBC [Connection]. Both facades delegate here —
 * [PostgresDriver] borrows a pooled connection per call, and
 * [PostgresTransactionalDriver] pins one connection for a whole transaction —
 * so each operation is implemented exactly once.
 *
 * Holds no connection state of its own: [registry] is the driver's live
 * schema registry (populated by `register`), and all value conversion goes
 * through [codec].
 */
internal class PostgresOperations(
    private val registry: Map<String, EntitySchema>,
    private val codec: PostgresValueCodec,
) {

    private fun schemaFor(table: String): EntitySchema =
        registry[table] ?: error("Unregistered table: $table")

    fun <T> copyJsonValue(table: String, column: String, value: T): T {
        val schema = schemaFor(table)
        val metadata = schema.columns.firstOrNull { it.name == column }
            ?: error("Unknown column '$table.$column'")
        require(metadata.type == FieldType.JSON) {
            "Column '$table.$column' is not a typed JSON column"
        }
        return codec.copyJsonValue(table, metadata, value)
    }

    fun insert(
        conn: Connection,
        table: String,
        values: Map<String, Any?>,
    ): Map<String, Any?> {
        val schema = schemaFor(table)

        // For numeric auto-id strategies, drop the id column from the
        // INSERT entirely so the SERIAL/BIGSERIAL default fires. CLIENT_UUID
        // and EXPLICIT must supply the id themselves; that's the contract
        // generated `save()` already follows.
        val skipId = (schema.idStrategy == IdStrategy.AUTO_INT ||
            schema.idStrategy == IdStrategy.AUTO_LONG) &&
            values[schema.idColumn] == null

        val cols = values.keys.filter { !(skipId && it == schema.idColumn) }
        val placeholders = cols.joinToString(", ") { "?" }
        val colList = cols.joinToString(", ") { quote(it) }
        val sql = if (cols.isEmpty()) {
            "INSERT INTO ${quote(table)} DEFAULT VALUES RETURNING *"
        } else {
            "INSERT INTO ${quote(table)} ($colList) VALUES ($placeholders) RETURNING *"
        }

        return conn.prepareStatement(sql).useQuietClose { stmt ->
            for ((i, col) in cols.withIndex()) {
                codec.bindColumn(stmt, i + 1, schema, col, values[col])
            }
            stmt.executeQuery().useQuietClose { rs ->
                check(rs.next()) { "INSERT into $table returned no row" }
                codec.decodeRow(rs, schema.table, schema.columns)
            }
        }
    }

    /**
     * Idempotent insert: `INSERT ... ON CONFLICT (conflictColumns) DO
     * NOTHING RETURNING *`. Returns the inserted row, or `null` when the
     * insert was skipped because a row already matched [conflictColumns].
     *
     * Mirrors [insert], with two differences: the targeted
     * `ON CONFLICT` clause, and — critically — it does NOT assert a row
     * came back. `DO NOTHING` produces zero result rows on a conflict, and
     * that skip is the whole point (re-adding an existing junction pair is
     * a no-op), so `rs.next() == false` maps to `null` rather than an error.
     * The conflict target is scoped to [conflictColumns]; any *other*
     * constraint violation still propagates as a thrown exception.
     */
    fun insertIgnore(
        conn: Connection,
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
    ): Map<String, Any?>? {
        val schema = schemaFor(table)

        val skipId = (schema.idStrategy == IdStrategy.AUTO_INT ||
            schema.idStrategy == IdStrategy.AUTO_LONG) &&
            values[schema.idColumn] == null

        val cols = values.keys.filter { !(skipId && it == schema.idColumn) }
        val placeholders = cols.joinToString(", ") { "?" }
        val colList = cols.joinToString(", ") { quote(it) }
        val conflictList = conflictColumns.joinToString(", ") { quote(it) }
        val valuesClause = if (cols.isEmpty()) "DEFAULT VALUES" else "($colList) VALUES ($placeholders)"
        val sql =
            "INSERT INTO ${quote(table)} $valuesClause ON CONFLICT ($conflictList) DO NOTHING RETURNING *"

        return conn.prepareStatement(sql).useQuietClose { stmt ->
            for ((i, col) in cols.withIndex()) {
                codec.bindColumn(stmt, i + 1, schema, col, values[col])
            }
            stmt.executeQuery().useQuietClose { rs ->
                // No assertion: a conflict legitimately produces zero rows.
                if (rs.next()) codec.decodeRow(rs, schema.table, schema.columns) else null
            }
        }
    }

    fun update(
        conn: Connection,
        table: String,
        id: Any,
        values: Map<String, Any?>,
    ): Map<String, Any?>? {
        val schema = schemaFor(table)
        // Never let an update rewrite the primary key.
        val cols = values.keys.filter { it != schema.idColumn }
        if (cols.isEmpty()) {
            // Nothing to update; just return the existing row (or null).
            return byId(conn, table, id)
        }
        val setClause = cols.joinToString(", ") { "${quote(it)} = ?" }
        val sql = "UPDATE ${quote(table)} SET $setClause WHERE ${quote(schema.idColumn)} = ? RETURNING *"

        return conn.prepareStatement(sql).useQuietClose { stmt ->
            for ((i, col) in cols.withIndex()) {
                codec.bindColumn(stmt, i + 1, schema, col, values[col])
            }
            codec.bind(stmt, cols.size + 1, schema.columnType(schema.idColumn), id)
            stmt.executeQuery().useQuietClose { rs ->
                if (rs.next()) codec.decodeRow(rs, schema.table, schema.columns) else null
            }
        }
    }

    fun byId(conn: Connection, table: String, id: Any): Map<String, Any?>? {
        val schema = schemaFor(table)
        val sql = "SELECT * FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ?"
        return conn.prepareStatement(sql).useQuietClose { stmt ->
            codec.bind(stmt, 1, schema.columnType(schema.idColumn), id)
            stmt.executeQuery().useQuietClose { rs ->
                if (rs.next()) codec.decodeRow(rs, schema.table, schema.columns) else null
            }
        }
    }

    fun buildSelectSql(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): PreparedSql {
        val schema = schemaFor(table)
        val builder = PredicateSqlBuilder(registry)
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("SELECT ").append(baseAlias).append(".* FROM ")
            .append(quote(table)).append(" AS ").append(baseAlias)

        val combined = predicates.andTogether()
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }

        if (orderBy.isNotEmpty()) {
            // Rendering (incl. pgvector distance operands appended to
            // builder.params after the WHERE params, so the placeholder
            // order matches the SQL text) is shared with the shaped
            // traversal-source subquery — see PredicateSqlBuilder.orderBySql.
            sql.append(" ORDER BY ").append(builder.orderBySql(orderBy, schema, baseAlias))
        }

        if (limit != null) sql.append(" LIMIT ").append(limit)
        if (offset != null) sql.append(" OFFSET ").append(offset)

        return PreparedSql(sql.toString(), builder.params.toList())
    }

    fun query(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> {
        val schema = schemaFor(table)
        val prepared = buildSelectSql(table, predicates, orderBy, limit, offset)
        checkBindLimit(prepared.params.size, "query", table)

        return conn.prepareStatement(prepared.sql).useQuietClose { stmt ->
            for ((i, p) in prepared.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().useQuietClose { rs ->
                val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) out.add(codec.decodeRow(rs, schema.table, schema.columns))
                out
            }
        }
    }

    /**
     * The native direct to-many lowering: one statement whose
     * relationship constraint travels as a single typed PostgreSQL
     * array (`fk = ANY(?)`) instead of one scalar bind per parent,
     * with any finite per-parent window applied in storage through
     * `ROW_NUMBER() OVER (PARTITION BY fk ORDER BY <effective order>)`.
     *
     * The window-less shape omits `ROW_NUMBER()` but keeps the
     * typed-array relationship predicate — parent cardinality never
     * consumes scalar binds on this path. Window-bound arithmetic is
     * evaluated in `Long` and bound as BIGINT parameters, so extreme
     * `offset`/`limit` values cannot overflow. One statement ⇒ one
     * database snapshot, per the RFC's consistency requirement.
     *
     * The ranking alias is allocated to dodge every registered
     * storage column (a collision would make the derived table's
     * column names ambiguous), and the outer SELECT lists exactly the
     * registered columns, so the synthetic column never crosses into
     * the decoded row maps — which [PostgresValueCodec.decodeRow]
     * additionally guarantees by decoding registered names only.
     *
     * The caller (the runtime's `executeDirectToMany`) gates empty
     * parent sets and `limit(0)` windows; this method mirrors those
     * gates defensively so a direct low-level call can never reach
     * PostgreSQL with a read that returns nothing by construction.
     */
    fun queryDirectToMany(conn: Connection, query: DirectToManyQuery): RelatedRows {
        val schema = schemaFor(query.targetTable)
        val fkColumn = query.targetForeignKey
        // Validate before the data gates so a malformed plan fails
        // fast even when the gated read would perform no I/O.
        val fkType = schema.columnType(fkColumn)
            ?: error("'${query.targetTable}.$fkColumn' is not a registered column")
        // The effective order is the deterministic-selection contract:
        // ranking (and chunk-free global row order) is meaningless
        // without it, and the runtime always supplies at least the
        // framework's primary-key term.
        require(query.effectiveOrder.isNotEmpty()) {
            "queryDirectToMany for '${query.targetTable}' requires a non-empty effective order"
        }
        if (query.sourceKeys.isEmpty() || query.window.limit == 0) {
            return RelatedRows(emptyList(), EagerWindowStrategy.STORAGE_NATIVE)
        }

        val prepared = buildDirectToManySql(query)
        checkBindLimit(prepared.params.size, "direct to-many query", query.targetTable)

        return conn.prepareStatement(prepared.sql).useQuietClose { stmt ->
            for ((i, p) in prepared.params.withIndex()) {
                val value = p.value
                if (value is PgTypedArray) {
                    stmt.setArray(i + 1, conn.createArrayOf(value.typeName, value.elements))
                } else {
                    codec.bind(stmt, i + 1, p.type, p.value)
                }
            }
            stmt.executeQuery().useQuietClose { rs ->
                val rows = ArrayList<RelatedRow>()
                while (rs.next()) {
                    val decoded = codec.decodeRow(rs, schema.table, schema.columns)
                    rows.add(RelatedRow(decoded[fkColumn], decoded))
                }
                RelatedRows(rows, EagerWindowStrategy.STORAGE_NATIVE)
            }
        }
    }

    /**
     * Render the native direct to-many statement without executing
     * it, [buildSelectSql]-style: the returned SQL and positional
     * params are exactly what [queryDirectToMany] prepares, so tests
     * pin the storage-window shape itself — a lowering that stopped
     * windowing in storage (or dropped the typed-array transport)
     * changes this text, not just runtime row counts. The parent-key
     * array rides the param list as a [PgTypedArray]-valued [Param].
     */
    fun buildDirectToManySql(query: DirectToManyQuery): PreparedSql {
        val schema = schemaFor(query.targetTable)
        val fkColumn = query.targetForeignKey
        val fkType = schema.columnType(fkColumn)
            ?: error("'${query.targetTable}.$fkColumn' is not a registered column")
        require(query.effectiveOrder.isNotEmpty()) {
            "queryDirectToMany for '${query.targetTable}' requires a non-empty effective order"
        }
        val window = query.window

        val arrayTypeName = when (fkType) {
            FieldType.INT -> "integer"
            FieldType.LONG -> "bigint"
            FieldType.STRING, FieldType.TEXT -> "text"
            FieldType.UUID -> "uuid"
            else -> error(
                "'${query.targetTable}.$fkColumn' is $fkType, which cannot transport " +
                    "parent keys as a typed array",
            )
        }
        // Same normalization scalar binds get (exact INT/LONG
        // conversion), so a raw Number subtype behaves identically on
        // the array path and the emulated fallback's IN list.
        val arrayElements = query.sourceKeys
            .map { codec.idCorrelationKey(fkType, it) }
            .toTypedArray()
        val arrayParam = Param(null, PgTypedArray(arrayTypeName, arrayElements))

        val builder = PredicateSqlBuilder(registry)
        val combined = query.targetPredicates.andTogether()
        val ranked = window.limit != null || window.offset > 0

        val sql: String
        val params: List<Param>
        if (!ranked) {
            // Param order mirrors SQL text: array, predicate binds,
            // ordering operands.
            val predicateSql = combined?.let { builder.lower(it, schema, "t0") }
            val orderSql = builder.orderBySql(query.effectiveOrder, schema, "t0")
            sql = buildString {
                append("SELECT t0.* FROM ").append(quote(query.targetTable)).append(" AS t0")
                append(" WHERE t0.").append(quote(fkColumn)).append(" = ANY(?)")
                if (predicateSql != null) append(" AND (").append(predicateSql).append(")")
                append(" ORDER BY ").append(orderSql)
            }
            params = listOf(arrayParam) + builder.params
        } else {
            // The ranked input applies every frozen predicate BEFORE
            // ROW_NUMBER() is assigned — filtering after ranking could
            // return fewer than the requested limit while later
            // matching rows exist. Param order mirrors SQL text: the
            // OVER (ORDER BY ...) operands sit in the select list and
            // therefore bind FIRST, then the array, predicate binds,
            // rank bounds, and the outer ordering operands.
            val rankAlias = allocateRankAlias(schema)
            val overOrderSql = builder.orderBySql(query.effectiveOrder, schema, "t0")
            val overOrderEnd = builder.params.size
            val predicateSql = combined?.let { builder.lower(it, schema, "t0") }
            val predicateEnd = builder.params.size
            val outerOrderSql = builder.orderBySql(query.effectiveOrder, schema, "t1")
            val overOrderParams = builder.params.subList(0, overOrderEnd).toList()
            val predicateParams = builder.params.subList(overOrderEnd, predicateEnd).toList()
            val outerOrderParams = builder.params.subList(predicateEnd, builder.params.size).toList()
            // Long arithmetic: the Kotlin DSL bounds are Ints, but
            // offset + limit must never be evaluated in Int width.
            val lowerBound = window.offset.toLong()
            val upperBound = window.limit?.let { window.offset.toLong() + it.toLong() }
            val boundParams = listOfNotNull(
                Param(FieldType.LONG, lowerBound),
                upperBound?.let { Param(FieldType.LONG, it) },
            )
            // Both select lists enumerate the REGISTERED columns.
            // `t0.*` would also drag unregistered physical columns
            // into the derived table (registration is metadata-only
            // under autoDdl = false), and a hand-managed column that
            // happened to share the rank alias's name would make the
            // outer rank reference ambiguous — with the explicit
            // list, probing the alias against registered names is
            // sufficient by construction.
            val innerColumns = schema.columns.joinToString(", ") { "t0.${quote(it.name)}" }
            val outerColumns = schema.columns.joinToString(", ") { "t1.${quote(it.name)}" }
            sql = buildString {
                append("SELECT ").append(outerColumns).append(" FROM (")
                append("SELECT ").append(innerColumns).append(", ROW_NUMBER() OVER (PARTITION BY t0.")
                append(quote(fkColumn)).append(" ORDER BY ").append(overOrderSql)
                append(") AS ").append(quote(rankAlias))
                append(" FROM ").append(quote(query.targetTable)).append(" AS t0")
                append(" WHERE t0.").append(quote(fkColumn)).append(" = ANY(?)")
                if (predicateSql != null) append(" AND (").append(predicateSql).append(")")
                append(") AS t1 WHERE t1.").append(quote(rankAlias)).append(" > ?")
                if (upperBound != null) {
                    append(" AND t1.").append(quote(rankAlias)).append(" <= ?")
                }
                append(" ORDER BY ").append(outerOrderSql)
            }
            params = overOrderParams + arrayParam + predicateParams + boundParams + outerOrderParams
        }

        return PreparedSql(sql, params)
    }

    /**
     * Pick a ranking alias no registered storage column uses. DSL
     * storage names can never start with an underscore, but the raw
     * driver API accepts hand-built schemas with arbitrary column
     * names, so the allocator probes rather than trusting the prefix.
     * Probing registered names suffices because the ranked query's
     * select lists enumerate registered columns explicitly — an
     * unregistered physical column on the live table (legal under
     * metadata-only registration) never enters the derived table and
     * so can never make the alias ambiguous.
     */
    private fun allocateRankAlias(schema: EntitySchema): String {
        var candidate = "__entkt_rank"
        var suffix = 0
        while (schema.columns.any { it.name == candidate }) {
            candidate = "__entkt_rank_${suffix++}"
        }
        return candidate
    }

    fun buildCountSql(table: String, predicates: List<Predicate<*>>): PreparedSql {
        val schema = schemaFor(table)
        val builder = PredicateSqlBuilder(registry)
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("SELECT COUNT(*) FROM ")
            .append(quote(table)).append(" AS ").append(baseAlias)

        val combined = predicates.andTogether()
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }

        return PreparedSql(sql.toString(), builder.params)
    }

    fun count(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
    ): Long {
        val prepared = buildCountSql(table, predicates)
        checkBindLimit(prepared.params.size, "count", table)

        return conn.prepareStatement(prepared.sql).useQuietClose { stmt ->
            for ((i, p) in prepared.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().useQuietClose { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    fun aggregate(
        conn: Connection,
        table: String,
        function: AggregateFunction,
        column: String?,
        predicates: List<Predicate<*>>,
        groupBy: String?,
    ): List<AggregateResultRow> {
        val schema = schemaFor(table)
        // Validate identifiers against the registered schema BEFORE rendering SQL.
        // The method takes raw String?, so a bad column must fail with a clear,
        // field-named error rather than reaching Postgres as a query error.
        require(function == AggregateFunction.COUNT || column != null) {
            "$function requires a metric column"
        }
        // COUNT lowers to COUNT(*) and ignores a column, so reject a stray one
        // rather than silently dropping it (least surprise on the raw driver API).
        require(function != AggregateFunction.COUNT || column == null) {
            "COUNT does not take a metric column; pass column = null"
        }
        require(column == null || schema.columns.any { it.name == column }) {
            "$table.$column is not a column on $table"
        }
        require(groupBy == null || schema.columns.any { it.name == groupBy }) {
            "$table.$groupBy is not a column on $table"
        }
        // The group key must be a groupable type — the generated API gates this
        // via GroupableColumn; the raw entry point must too, so grouping by a
        // bytes / pgvector / JSON column fails clearly instead of reaching SQL.
        val groupType = groupBy?.let { g -> schema.columns.first { it.name == g }.type }
        require(groupBy == null || groupType in GROUPABLE_FIELD_TYPES) {
            "$table.$groupBy is $groupType, which cannot be a group key"
        }
        // The metric must be type-compatible with the function. The generated API
        // enforces this at compile time via column markers, but the raw String?
        // entry point must too, so SUM(text) / MIN(enum) fail with a clear error
        // instead of a Postgres error or undocumented behavior.
        val metricType = column?.let { c -> schema.columns.first { it.name == c }.type }
        when (function) {
            AggregateFunction.COUNT -> {}
            AggregateFunction.SUM, AggregateFunction.AVG -> require(metricType in NUMERIC_FIELD_TYPES) {
                "$function requires a numeric column, but $table.$column is $metricType"
            }
            AggregateFunction.MIN, AggregateFunction.MAX -> require(metricType in COMPARABLE_FIELD_TYPES) {
                "$function requires a comparable column, but $table.$column is $metricType"
            }
        }

        val builder = PredicateSqlBuilder(registry)
        val baseAlias = "t0"
        val metricSql = when (function) {
            AggregateFunction.COUNT -> "COUNT(*)"
            AggregateFunction.SUM -> "SUM($baseAlias.${quote(column!!)})"
            AggregateFunction.AVG -> "AVG($baseAlias.${quote(column!!)})"
            AggregateFunction.MIN -> "MIN($baseAlias.${quote(column!!)})"
            AggregateFunction.MAX -> "MAX($baseAlias.${quote(column!!)})"
        }

        // Alias the group key as "k" and the metric as "v" so the by-name
        // [PostgresValueCodec.decodeColumn] / getX("v") below read the right
        // ResultSet columns.
        val sql = StringBuilder("SELECT ")
        if (groupBy != null) {
            sql.append(baseAlias).append('.').append(quote(groupBy))
                .append(" AS ").append(quote("k")).append(", ")
        }
        sql.append(metricSql).append(" AS ").append(quote("v"))
        sql.append(" FROM ").append(quote(table)).append(" AS ").append(baseAlias)

        val combined = predicates.andTogether()
        if (combined != null) {
            sql.append(" WHERE ").append(builder.lower(combined, schema, baseAlias))
        }
        if (groupBy != null) {
            sql.append(" GROUP BY ").append(baseAlias).append('.').append(quote(groupBy))
        }

        val groupCol = groupBy?.let { gb -> schema.columns.first { it.name == gb } }
        val metricCol = column?.let { c -> schema.columns.first { it.name == c } }
        checkBindLimit(builder.params.size, "aggregate", table)

        return conn.prepareStatement(sql.toString()).useQuietClose { stmt ->
            for ((i, p) in builder.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().useQuietClose { rs ->
                val out = ArrayList<AggregateResultRow>()
                while (rs.next()) {
                    // The group key decodes as its column's type; an enum column
                    // decodes to its stored String, which the generated terminal
                    // maps back to the enum via the column's metadata.
                    val key = groupCol?.let { codec.decodeColumn(rs, table, it.copy(name = "k")) }
                    val value = decodeAggregateValue(rs, function, metricCol, table)
                    out.add(AggregateResultRow(key, value))
                }
                out
            }
        }
    }

    private fun decodeAggregateValue(
        rs: ResultSet,
        function: AggregateFunction,
        metricCol: ColumnMetadata?,
        table: String,
    ): Any? = when (function) {
        // COUNT(*) is never NULL — 0 for an empty (ungrouped) result.
        AggregateFunction.COUNT -> rs.getLong("v")
        // AVG is double regardless of input; null over an empty/all-NULL set.
        AggregateFunction.AVG -> rs.getDouble("v").let { if (rs.wasNull()) null else it }
        // SUM widens integral inputs to Long and keeps floating as Double.
        AggregateFunction.SUM -> when (metricCol!!.type) {
            FieldType.INT, FieldType.LONG -> rs.getLong("v").let { if (rs.wasNull()) null else it }
            FieldType.FLOAT, FieldType.DOUBLE -> rs.getDouble("v").let { if (rs.wasNull()) null else it }
            else -> error("SUM is only valid on a numeric column, not ${metricCol.type}")
        }
        // MIN/MAX return the metric column's own type — reuse the row decoder.
        AggregateFunction.MIN, AggregateFunction.MAX ->
            codec.decodeColumn(rs, table, metricCol!!.copy(name = "v"))
    }

    fun exists(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
    ): Boolean {
        val schema = schemaFor(table)
        val builder = PredicateSqlBuilder(registry)
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("SELECT EXISTS(SELECT 1 FROM ")
            .append(quote(table)).append(" AS ").append(baseAlias)

        val combined = predicates.andTogether()
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }
        sql.append(")")
        checkBindLimit(builder.params.size, "exists", table)

        return conn.prepareStatement(sql.toString()).useQuietClose { stmt ->
            for ((i, p) in builder.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().useQuietClose { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

    fun delete(conn: Connection, table: String, id: Any): Boolean {
        val schema = schemaFor(table)
        val sql = "DELETE FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ?"
        return conn.prepareStatement(sql).useQuietClose { stmt ->
            codec.bind(stmt, 1, schema.columnType(schema.idColumn), id)
            stmt.executeUpdate() > 0
        }
    }

    fun insertMany(
        conn: Connection,
        table: String,
        values: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (values.isEmpty()) return emptyList()
        val schema = schemaFor(table)

        val isAutoId = schema.idStrategy == IdStrategy.AUTO_INT ||
            schema.idStrategy == IdStrategy.AUTO_LONG

        // Normalize rows: for auto-id schemas, treat explicit null id the
        // same as omitted id (both should use the serial default).
        val normalized = if (isAutoId) {
            values.map { row ->
                if (row.containsKey(schema.idColumn) && row[schema.idColumn] == null)
                    row - schema.idColumn
                else row
            }
        } else values

        // Group rows by their column sets so each group gets its own
        // multi-row INSERT. This ensures absent columns use database
        // defaults rather than being bound as NULL.
        // Track original indices so we can return results in input order.
        data class IndexedRow(val index: Int, val row: Map<String, Any?>)

        val groups = normalized.mapIndexed { i, row -> IndexedRow(i, row) }
            .groupBy { it.row.keys }

        val results = arrayOfNulls<Map<String, Any?>>(values.size)

        inTransaction(conn) {
            for ((keys, indexedRows) in groups) {
                val rows = indexedRows.map { it.row }
                val skipId = isAutoId && rows.all { it[schema.idColumn] == null }
                val cols = keys.filter { !(skipId && it == schema.idColumn) }.toList()

                if (cols.isEmpty()) {
                    // All rows in this group are empty maps — per-row DEFAULT VALUES.
                    for (ir in indexedRows) {
                        results[ir.index] = insert(conn, table, ir.row)
                    }
                    continue
                }

                // PostgreSQL documents no ordering for RETURNING output
                // (and RETURNING accepts no ORDER BY), so a multi-row
                // statement must not pair returned rows with inputs
                // positionally. Every multi-row statement instead carries
                // an id per input row — caller-supplied, or reserved up
                // front from the id sequence — and returned rows are
                // matched back by id. Single-row statements return
                // exactly one row and need no correlation.
                val allocated = if (rows.size > 1 && skipId) {
                    preallocateSequenceIds(conn, schema, rows.size)
                } else null
                val insertCols = if (allocated != null) listOf(schema.idColumn) + cols else cols
                val correlationIds: List<Any?>? = when {
                    rows.size == 1 -> null
                    allocated != null -> allocated.ids
                    schema.idColumn in insertCols -> rows.map { it[schema.idColumn] }
                    else -> null
                }

                if (rows.size > 1 && correlationIds == null) {
                    // No possible correlation key: ids come from an
                    // opaque database default (no serial/identity
                    // sequence to pre-allocate from). A multi-row
                    // statement would have to trust RETURNING order, so
                    // fall back to per-row statements — one row in, one
                    // row out, nothing to mis-pair. This shape is rare
                    // (multi-row batch on a hand-created default-id
                    // table), so the lost batching is a fair price for
                    // keeping the input-order contract unconditional.
                    for (ir in indexedRows) {
                        results[ir.index] = insert(conn, table, ir.row)
                    }
                    continue
                }

                // GENERATED ALWAYS AS IDENTITY rejects explicit id values
                // unless the statement says OVERRIDING SYSTEM VALUE; the
                // clause is only valid against identity columns, so it is
                // emitted exactly when preallocation saw one.
                val overriding = if (allocated?.overridingSystemValue == true) "OVERRIDING SYSTEM VALUE " else ""
                val maxRowsPerStatement = POSTGRES_BIND_PARAMETER_LIMIT / insertCols.size
                check(maxRowsPerStatement > 0) {
                    "A row for '$table' requires ${insertCols.size} bind parameters, exceeding " +
                        "PostgreSQL's $POSTGRES_BIND_PARAMETER_LIMIT-parameter limit"
                }
                val singlePlaceholders = "(${insertCols.joinToString(", ") { "?" }})"
                val colList = insertCols.joinToString(", ") { quote(it) }

                // A logical insertMany may exceed PostgreSQL's bind limit.
                // Split it into physical statements on this same connection;
                // inTransaction keeps every chunk atomic, while original
                // indices and correlation ids preserve the public order.
                var offset = 0
                while (offset < rows.size) {
                    val end = minOf(offset + maxRowsPerStatement, rows.size)
                    val chunkRows = rows.subList(offset, end)
                    val chunkIndexedRows = indexedRows.subList(offset, end)
                    val chunkCorrelationIds = correlationIds?.subList(offset, end)
                    val allPlaceholders = chunkRows.joinToString(", ") { singlePlaceholders }
                    val sql =
                        "INSERT INTO ${quote(table)} ($colList) ${overriding}VALUES $allPlaceholders RETURNING *"

                    conn.prepareStatement(sql).useQuietClose { stmt ->
                        var idx = 1
                        chunkRows.forEachIndexed { rowI, row ->
                            for (col in insertCols) {
                                val v = if (allocated != null && col == schema.idColumn) {
                                    allocated.ids[offset + rowI]
                                } else {
                                    row[col]
                                }
                                codec.bindColumn(stmt, idx++, schema, col, v)
                            }
                        }
                        stmt.executeQuery().useQuietClose { rs ->
                            if (chunkCorrelationIds == null) {
                                // Only reachable for a single-row group (the
                                // multi-row no-key case took the per-row
                                // branch above): one row in, one row out.
                                for (ir in chunkIndexedRows) {
                                    check(rs.next()) { "INSERT RETURNING produced fewer rows than expected" }
                                    results[ir.index] = codec.decodeRow(rs, schema.table, schema.columns)
                                }
                            } else {
                                val byId = HashMap<Any?, Map<String, Any?>>(chunkRows.size * 2)
                                while (rs.next()) {
                                    val decoded = codec.decodeRow(rs, schema.table, schema.columns)
                                    byId[idKey(schema, decoded[schema.idColumn])] = decoded
                                }
                                check(byId.size == chunkRows.size) {
                                    "INSERT RETURNING produced ${byId.size} distinct ids for ${chunkRows.size} rows"
                                }
                                chunkIndexedRows.forEachIndexed { rowI, ir ->
                                    results[ir.index] = checkNotNull(byId[idKey(schema, chunkCorrelationIds[rowI])]) {
                                        "INSERT RETURNING is missing the row for id ${chunkCorrelationIds[rowI]}"
                                    }
                                }
                            }
                        }
                    }
                    offset = end
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<Map<String, Any?>>
    }

    private data class PreallocatedIds(
        val ids: List<Any>,
        /** True when the id column is GENERATED ALWAYS AS IDENTITY. */
        val overridingSystemValue: Boolean,
    )

    /**
     * Reserve [n] ids from [schema]'s backing id sequence in one round
     * trip, so a multi-row insert can bind explicit ids and correlate
     * its RETURNING rows by id. Returns null when the id column has no
     * serial/identity sequence (`nextval` over a null regclass), letting
     * the caller fall back to one-row statements with unambiguous pairing.
     *
     * Also reports whether the id column is `GENERATED ALWAYS AS
     * IDENTITY` — binding explicit values into one requires
     * `OVERRIDING SYSTEM VALUE` on the insert. The table name is passed
     * to `pg_get_serial_sequence` / `to_regclass` in quoted form because
     * both parse their argument under SQL identifier rules.
     *
     * `nextval` is non-transactional, so reserved ids are burned on
     * rollback — exactly as serial defaults already behave for failed
     * inserts. Reserving the complete batch first and then binding those ids
     * explicitly bypasses the normal id default. Another default or trigger
     * that reads row-local `currval()` / `lastval()` progression can therefore
     * observe the final reserved value for every row; that sequence-sensitive
     * shape is outside the optimized insertMany contract documented for
     * Postgres.
     */
    private fun preallocateSequenceIds(conn: Connection, schema: EntitySchema, n: Int): PreallocatedIds? {
        val quotedTable = "\"" + schema.table.replace("\"", "\"\"") + "\""
        var generatedAlways = false
        conn.prepareStatement(
            """
            SELECT c.identity_generation = 'ALWAYS'
            FROM pg_class cl
            JOIN pg_namespace ns ON ns.oid = cl.relnamespace
            JOIN information_schema.columns c
              ON c.table_name = cl.relname AND c.table_schema = ns.nspname
            WHERE cl.oid = to_regclass(?) AND c.column_name = ?
            """.trimIndent(),
        ).useQuietClose { stmt ->
            stmt.setString(1, quotedTable)
            stmt.setString(2, schema.idColumn)
            stmt.executeQuery().useQuietClose { rs ->
                if (rs.next()) generatedAlways = rs.getBoolean(1) && !rs.wasNull()
            }
        }

        val ids = ArrayList<Any>(n)
        conn.prepareStatement(
            "SELECT nextval(pg_get_serial_sequence(?, ?)) FROM generate_series(1, ?)",
        ).useQuietClose { stmt ->
            stmt.setString(1, quotedTable)
            stmt.setString(2, schema.idColumn)
            stmt.setInt(3, n)
            stmt.executeQuery().useQuietClose { rs ->
                while (rs.next()) {
                    val v = rs.getLong(1)
                    if (rs.wasNull()) return null
                    ids.add(
                        if (schema.idStrategy == IdStrategy.AUTO_INT) {
                            check(v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                                "Preallocated id $v for '${schema.table}.${schema.idColumn}' exceeds the INT range"
                            }
                            v.toInt()
                        } else {
                            v
                        },
                    )
                }
            }
        }
        return if (ids.size == n) PreallocatedIds(ids, generatedAlways) else null
    }

    /**
     * Normalize an id value for correlation-map lookups through the same exact
     * conversion used for JDBC binding. This matters for custom [Number]
     * implementations, whose `toLong()` may disagree with the decimal text
     * accepted by [PostgresValueCodec]. Non-integral ids retain their own
     * equality semantics.
     */
    private fun idKey(schema: EntitySchema, value: Any?): Any? {
        val idType = schema.columns.first { it.name == schema.idColumn }.type
        return codec.idCorrelationKey(idType, value)
    }

    fun updateMany(
        conn: Connection,
        table: String,
        values: Map<String, Any?>,
        predicates: List<Predicate<*>>,
    ): Int {
        val schema = schemaFor(table)
        val cols = values.keys.filter { it != schema.idColumn }
        if (cols.isEmpty()) return 0

        val builder = PredicateSqlBuilder(registry)
        val baseAlias = "t0"

        val setClause = cols.joinToString(", ") { "${quote(it)} = ?" }
        val sql = StringBuilder()
        sql.append("UPDATE ${quote(table)} AS $baseAlias SET $setClause")

        val combined = predicates.andTogether()
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }

        // SET values and predicate parameters share one bind budget.
        checkBindLimit(cols.size + builder.params.size, "updateMany", table)

        return conn.prepareStatement(sql.toString()).useQuietClose { stmt ->
            var idx = 1
            for (col in cols) {
                codec.bindColumn(stmt, idx++, schema, col, values[col])
            }
            for (p in builder.params) {
                codec.bind(stmt, idx++, p.type, p.value)
            }
            stmt.executeUpdate()
        }
    }

    fun deleteMany(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
    ): Int {
        val schema = schemaFor(table)
        val builder = PredicateSqlBuilder(registry)
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("DELETE FROM ${quote(table)} AS $baseAlias")

        val combined = predicates.andTogether()
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }

        checkBindLimit(builder.params.size, "deleteMany", table)

        return conn.prepareStatement(sql.toString()).useQuietClose { stmt ->
            for ((i, p) in builder.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeUpdate()
        }
    }

    fun deleteManyByIds(
        conn: Connection,
        table: String,
        idColumn: String,
        ids: List<Any>,
        predicates: List<Predicate<*>>,
    ): List<Any> {
        if (ids.isEmpty()) return emptyList()

        val schema = schemaFor(table)
        require(idColumn == schema.idColumn) {
            "deleteManyByIds for '$table' requires its registered ID column " +
                "'${schema.idColumn}', got '$idColumn'"
        }
        val idMetadata = schema.columns.first { it.name == schema.idColumn }
        val distinctIds = ids.distinct()

        val predicateBuilder = PredicateSqlBuilder(registry)
        val effectivePredicateSql = predicates.andTogether()?.let {
            predicateBuilder.lower(it, schema, "t0")
        }
        val availableIdParameters = POSTGRES_BIND_PARAMETER_LIMIT - predicateBuilder.params.size
        require(availableIdParameters > 0) {
            "Predicates for '$table' use all available PostgreSQL bind parameters"
        }

        val deletedIds = mutableListOf<Any>()
        for (chunk in distinctIds.chunked(availableIdParameters)) {
            val placeholders = chunk.joinToString(", ") { "?" }
            val sql = buildString {
                append("DELETE FROM ${quote(table)} AS t0 WHERE t0.${quote(idColumn)} IN ($placeholders)")
                if (effectivePredicateSql != null) append(" AND (").append(effectivePredicateSql).append(")")
                append(" RETURNING ${quote(idColumn)}")
            }
            conn.prepareStatement(sql).useQuietClose { stmt ->
                var parameterIndex = 1
                for (id in chunk) codec.bind(stmt, parameterIndex++, schema.idType, id)
                for (parameter in predicateBuilder.params) {
                    codec.bind(stmt, parameterIndex++, parameter.type, parameter.value)
                }
                stmt.executeQuery().useQuietClose { rs ->
                    while (rs.next()) {
                        deletedIds += checkNotNull(codec.decodeColumn(rs, schema.table, idMetadata)) {
                            "DELETE from '$table' returned a null ID"
                        }
                    }
                }
            }
        }
        return deletedIds
    }

    // ---------- Locking primitives (transaction locking) ----------

    /**
     * Lock the row by id with `SELECT ... FOR UPDATE` and return its
     * current contents. Caller is responsible for the surrounding
     * transaction lifecycle. Returns `null` if no row exists.
     */
    fun readRowForUpdate(conn: Connection, table: String, id: Any): Map<String, Any?>? {
        val schema = schemaFor(table)
        val sql = "SELECT * FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ? FOR UPDATE"
        return conn.prepareStatement(sql).useQuietClose { stmt ->
            codec.bind(stmt, 1, schema.idType, id)
            stmt.executeQuery().useQuietClose { rs ->
                if (rs.next()) codec.decodeRow(rs, schema.table, schema.columns) else null
            }
        }
    }

    /**
     * Take a transaction-scoped advisory lock keyed by `(table, id)`,
     * then read the row. The advisory lock serializes other callers
     * using the same discipline against this `(table, id)` pair, but
     * does not block ordinary `UPDATE`/`DELETE` from outside the
     * discipline. Returns `null` if no row exists.
     *
     * The lock key binds the table name's `hashCode` and the id's
     * `hashCode` as the two `int4` arguments of the
     * `pg_advisory_xact_lock(int4, int4)` overload (matching
     * [serializeRelationship]). Postgres advisory locks are released
     * automatically at transaction end, so the duration requirement from
     * transaction locking ("held until the enclosing transaction commits or
     * rolls back") is automatic.
     */
    fun serializeOwnerEdgeAndRead(conn: Connection, table: String, id: Any): Map<String, Any?>? {
        schemaFor(table) // fail fast on an unregistered table, matching the other ops
        // pg_advisory_xact_lock(int4, int4) — bind table-name hash and
        // id hash as the two key columns. Hash collisions only mean
        // over-serialization (false sharing), never under-serialization.
        val tableKey = table.hashCode()
        val idKey = id.hashCode()
        conn.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").useQuietClose { stmt ->
            stmt.setInt(1, tableKey)
            stmt.setInt(2, idKey)
            // The lock is taken by executing the statement; the result
            // set is only discarded. Released through useQuietClose so a
            // close failure can't roll back a transaction whose lock was
            // acquired successfully.
            stmt.executeQuery().useQuietClose { }
        }
        // Then read the row inside the held lock.
        return byId(conn, table, id)
    }

    /**
     * Take a transaction-scoped advisory lock keyed by a canonical
     * [RelationshipLockKey][entkt.runtime.mutation.RelationshipLockKey] (junction
     * table + sorted FK pair), reading no row. Both orientations of the
     * same link table produce an equal key — `fkColumns` is canonically
     * sorted by the key factory — so they serialize against each other.
     *
     * The lock key folds the junction-table name's `hashCode` and the
     * (order-independent, because sorted) `fkColumns` list `hashCode` into
     * the two `int4` arguments of `pg_advisory_xact_lock`. Collisions only
     * over-serialize (false sharing), never under-serialize. The lock
     * releases automatically at transaction end.
     *
     * Distinct from [serializeOwnerEdgeAndRead], whose key is a single
     * owner row and so cannot coordinate the two orientations.
     */
    fun serializeRelationship(conn: Connection, key: entkt.runtime.mutation.RelationshipLockKey) {
        val junctionKey = key.junctionTable.hashCode()
        val columnsKey = key.fkColumns.hashCode()
        conn.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").useQuietClose { stmt ->
            stmt.setInt(1, junctionKey)
            stmt.setInt(2, columnsKey)
            // The lock is taken by executing the statement; the result
            // set is only discarded. Released through useQuietClose so a
            // close failure can't roll back a transaction whose lock was
            // acquired successfully.
            stmt.executeQuery().useQuietClose { }
        }
    }

    /**
     * Run [block] inside a transaction on [conn]. If autocommit is already
     * off (we're inside a transaction), just run the block directly.
     *
     * Unwinds under the same rules as [PostgresDriver.withTransaction] —
     * see [rollbackAttributingFailure] and [restoreAutoCommit] for why a
     * failing rollback must not replace the exception that caused it, and
     * why autocommit is restored only once the transaction has resolved.
     * This helper does not close [conn]; the caller borrowed it and owns
     * its release.
     */
    private fun <T> inTransaction(conn: Connection, block: () -> T): T {
        if (!conn.autoCommit) return block()
        conn.autoCommit = false
        // The exception on its way out, if any — a `finally` can't see it,
        // so the outer catch records it for the cleanup to attach to.
        var propagating: Throwable? = null
        var resolved = false
        try {
            try {
                val result = block()
                conn.commit()
                resolved = true
                return result
            } catch (e: Throwable) {
                resolved = rollbackAttributingFailure(conn, e)
                throw e
            }
        } catch (e: Throwable) {
            propagating = e
            throw e
        } finally {
            restoreAutoCommit(conn, propagating, resolved)
        }
    }
}
