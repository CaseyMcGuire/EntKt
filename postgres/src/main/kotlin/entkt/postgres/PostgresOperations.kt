package entkt.postgres

import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
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
            sql.append(" ORDER BY ")
            sql.append(
                orderBy.joinToString(", ") { of ->
                    val dir = if (of.direction == OrderDirection.ASC) "ASC" else "DESC"
                    val distance = of.distance
                    if (distance != null) {
                        // pgvector distance ordering: `col <op> ?`, operand bound
                        // as a vector parameter (never inlined). Appended after the
                        // WHERE params (which are already in builder.params) so the
                        // placeholder order matches the SQL text.
                        //
                        // Validate the operand dimension against the column's
                        // declared dimensions here — bind() only sees FieldType,
                        // so without this a wrong-size query vector would surface
                        // as an opaque Postgres error instead of a field-named one.
                        val operand = distance.operand
                        val native = schema.nativeStorage(of.field)
                        checkVectorDimensions(native, operand, "orderBy distance on '$table.${of.field}'")
                        builder.params.add(Param(FieldType.PGVECTOR, operand))
                        // NULLS LAST always: a null embedding has no distance, so it
                        // belongs at the end for both nearest-first (asc) and
                        // farthest-first (desc). Postgres otherwise defaults nulls
                        // FIRST on DESC, which would surface missing embeddings ahead
                        // of the actual farthest vectors.
                        "$baseAlias.${quote(of.field)} ${distance.operator.sql} ? $dir NULLS LAST"
                    } else {
                        "$baseAlias.${quote(of.field)} $dir"
                    }
                },
            )
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

                val singlePlaceholders = "(${cols.joinToString(", ") { "?" }})"
                val allPlaceholders = rows.joinToString(", ") { singlePlaceholders }
                val colList = cols.joinToString(", ") { quote(it) }
                val sql = "INSERT INTO ${quote(table)} ($colList) VALUES $allPlaceholders RETURNING *"

                conn.prepareStatement(sql).useQuietClose { stmt ->
                    var idx = 1
                    for (row in rows) {
                        for (col in cols) {
                            codec.bindColumn(stmt, idx++, schema, col, row[col])
                        }
                    }
                    stmt.executeQuery().useQuietClose { rs ->
                        for (ir in indexedRows) {
                            check(rs.next()) { "INSERT RETURNING produced fewer rows than expected" }
                            results[ir.index] = codec.decodeRow(rs, schema.table, schema.columns)
                        }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<Map<String, Any?>>
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

        return conn.prepareStatement(sql.toString()).useQuietClose { stmt ->
            for ((i, p) in builder.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeUpdate()
        }
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
