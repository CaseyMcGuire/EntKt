package entkt.postgres

import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.Driver
import entkt.runtime.driver.EdgeMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.query.QueryExplanation
import entkt.schema.ColumnStorage
import entkt.schema.FieldType
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

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
 * A [Driver] backed by a JDBC [DataSource] talking to PostgreSQL.
 *
 * Each call borrows one connection from the pool and runs a single
 * statement. The driver does no caching beyond the per-table
 * [EntitySchema] registry — every [insert]/[update]/[query] hits the
 * database.
 *
 * The schema registry is populated by [register]. By default this is
 * metadata-only: it caches the [EntitySchema] needed for query lowering
 * and persistence, but does not mutate the database. When [autoDdl] is
 * true, [register] also issues `CREATE TABLE IF NOT EXISTS` and index
 * DDL derived from the schema.
 *
 * Predicate lowering produces parameterized SQL: leaves become
 * `"col" op ?`, edge predicates become `EXISTS (... )` subqueries
 * walking the registered [EdgeMetadata]. No string concatenation of
 * user values ever happens — only of column and table identifiers
 * (which originate in generated code, never user input).
 */
class PostgresDriver(
    private val dataSource: DataSource,
    private val autoDdl: Boolean = false,
    /**
     * `Json` instance used for all typed JSON encode/decode. Defaults to
     * `Json.Default`; pass e.g. `Json { ignoreUnknownKeys
     * = true }` to configure behavior. Serializers come from column metadata,
     * not from here.
     */
    json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json.Default,
) : Driver {

    private val schemas: MutableMap<String, EntitySchema> = ConcurrentHashMap()
    private val codec = PostgresValueCodec(json)
    private val ddl = PostgresDdl()

    override fun register(schema: EntitySchema) {
        if (schemas.containsKey(schema.table)) return

        // Reject native-storage columns whose codec this driver can't handle
        // (Postgres supports postgres.vector; everything else fails here).
        checkNativeStorageSupported(schema)
        // Reject typed JSON only if unsupported (Postgres supports it).
        checkTypedJsonSupported(schema)

        if (autoDdl) {
            // Required extensions (e.g. pgvector) must exist before a column
            // using their type is created.
            val extensions = schema.columns
                .mapNotNull { (it.storage as? entkt.schema.ColumnStorage.Native)?.requiredExtension }
                .distinct()
            val tableDdl = ddl.createTableSql(schema)
            val indexDdl = ddl.createIndexesSql(schema)
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    for (ext in extensions) stmt.execute("CREATE EXTENSION IF NOT EXISTS ${quote(ext)}")
                    stmt.execute(tableDdl)
                    for (sql in indexDdl) stmt.execute(sql)
                }
            }
        }

        // Cache only after optional DDL succeeds so a failed register()
        // can be retried.
        schemas.putIfAbsent(schema.table, schema)
    }

    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> =
        dataSource.connection.use { insertWith(it, table, values) }

    override fun insertIgnore(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
    ): Map<String, Any?>? =
        dataSource.connection.use { insertIgnoreWith(it, table, values, conflictColumns) }

    override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? =
        dataSource.connection.use { updateWith(it, table, id, values) }

    override fun byId(table: String, id: Any): Map<String, Any?>? =
        dataSource.connection.use { byIdWith(it, table, id) }

    override fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> =
        dataSource.connection.use { queryWith(it, table, predicates, orderBy, limit, offset) }

    override fun explainQuery(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): QueryExplanation {
        val prepared = buildSelectSql(table, predicates, orderBy, limit, offset)
        return PostgresQueryExplanation(prepared.sql, prepared.params.map { it.value })
    }

    override fun explainCount(
        table: String,
        predicates: List<Predicate<*>>,
    ): QueryExplanation {
        val prepared = buildCountSql(table, predicates)
        return PostgresQueryExplanation(prepared.sql, prepared.params.map { it.value })
    }

    override fun count(table: String, predicates: List<Predicate<*>>): Long =
        dataSource.connection.use { countWith(it, table, predicates) }

    override fun exists(table: String, predicates: List<Predicate<*>>): Boolean =
        dataSource.connection.use { existsWith(it, table, predicates) }

    override fun aggregate(
        table: String,
        function: AggregateFunction,
        column: String?,
        predicates: List<Predicate<*>>,
        groupBy: String?,
    ): List<AggregateResultRow> =
        dataSource.connection.use { aggregateWith(it, table, function, column, predicates, groupBy) }

    override fun delete(table: String, id: Any): Boolean =
        dataSource.connection.use { deleteWith(it, table, id) }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> =
        dataSource.connection.use { insertManyWith(it, table, values) }

    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate<*>>): Int =
        dataSource.connection.use { updateManyWith(it, table, values, predicates) }

    override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int =
        dataSource.connection.use { deleteManyWith(it, table, predicates) }

    // ---------- Connection-taking internals ----------

    private fun insertWith(
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

        return conn.prepareStatement(sql).use { stmt ->
            for ((i, col) in cols.withIndex()) {
                codec.bindColumn(stmt, i + 1, schema, col, values[col])
            }
            stmt.executeQuery().use { rs ->
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
     * Mirrors [insertWith], with two differences: the targeted
     * `ON CONFLICT` clause, and — critically — it does NOT assert a row
     * came back. `DO NOTHING` produces zero result rows on a conflict, and
     * that skip is the whole point (re-adding an existing junction pair is
     * a no-op), so `rs.next() == false` maps to `null` rather than an error.
     * The conflict target is scoped to [conflictColumns]; any *other*
     * constraint violation still propagates as a thrown exception.
     */
    private fun insertIgnoreWith(
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

        return conn.prepareStatement(sql).use { stmt ->
            for ((i, col) in cols.withIndex()) {
                codec.bindColumn(stmt, i + 1, schema, col, values[col])
            }
            stmt.executeQuery().use { rs ->
                // No assertion: a conflict legitimately produces zero rows.
                if (rs.next()) codec.decodeRow(rs, schema.table, schema.columns) else null
            }
        }
    }

    private fun updateWith(
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
            return byIdWith(conn, table, id)
        }
        val setClause = cols.joinToString(", ") { "${quote(it)} = ?" }
        val sql = "UPDATE ${quote(table)} SET $setClause WHERE ${quote(schema.idColumn)} = ? RETURNING *"

        return conn.prepareStatement(sql).use { stmt ->
            for ((i, col) in cols.withIndex()) {
                codec.bindColumn(stmt, i + 1, schema, col, values[col])
            }
            codec.bind(stmt, cols.size + 1, schema.columnType(schema.idColumn), id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) codec.decodeRow(rs, schema.table, schema.columns) else null
            }
        }
    }

    private fun byIdWith(conn: Connection, table: String, id: Any): Map<String, Any?>? {
        val schema = schemaFor(table)
        val sql = "SELECT * FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ?"
        return conn.prepareStatement(sql).use { stmt ->
            codec.bind(stmt, 1, schema.columnType(schema.idColumn), id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) codec.decodeRow(rs, schema.table, schema.columns) else null
            }
        }
    }

    private fun buildSelectSql(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): PreparedSql {
        val schema = schemaFor(table)
        val builder = PredicateSqlBuilder(schemas)
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

    private fun queryWith(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> {
        val schema = schemaFor(table)
        val prepared = buildSelectSql(table, predicates, orderBy, limit, offset)

        return conn.prepareStatement(prepared.sql).use { stmt ->
            for ((i, p) in prepared.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().use { rs ->
                val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) out.add(codec.decodeRow(rs, schema.table, schema.columns))
                out
            }
        }
    }

    private fun buildCountSql(table: String, predicates: List<Predicate<*>>): PreparedSql {
        val schema = schemaFor(table)
        val builder = PredicateSqlBuilder(schemas)
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

    private fun countWith(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
    ): Long {
        val prepared = buildCountSql(table, predicates)

        return conn.prepareStatement(prepared.sql).use { stmt ->
            for ((i, p) in prepared.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun aggregateWith(
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

        val builder = PredicateSqlBuilder(schemas)
        val baseAlias = "t0"
        val metricSql = when (function) {
            AggregateFunction.COUNT -> "COUNT(*)"
            AggregateFunction.SUM -> "SUM($baseAlias.${quote(column!!)})"
            AggregateFunction.AVG -> "AVG($baseAlias.${quote(column!!)})"
            AggregateFunction.MIN -> "MIN($baseAlias.${quote(column!!)})"
            AggregateFunction.MAX -> "MAX($baseAlias.${quote(column!!)})"
        }

        // Alias the group key as "k" and the metric as "v" so the by-name
        // [decodeColumn] / getX("v") below read the right ResultSet columns.
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

        return conn.prepareStatement(sql.toString()).use { stmt ->
            for ((i, p) in builder.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().use { rs ->
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

    private fun existsWith(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
    ): Boolean {
        val schema = schemaFor(table)
        val builder = PredicateSqlBuilder(schemas)
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

        return conn.prepareStatement(sql.toString()).use { stmt ->
            for ((i, p) in builder.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

    private fun deleteWith(conn: Connection, table: String, id: Any): Boolean {
        val schema = schemaFor(table)
        val sql = "DELETE FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ?"
        return conn.prepareStatement(sql).use { stmt ->
            codec.bind(stmt, 1, schema.columnType(schema.idColumn), id)
            stmt.executeUpdate() > 0
        }
    }

    private fun insertManyWith(
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
                        results[ir.index] = insertWith(conn, table, ir.row)
                    }
                    continue
                }

                val singlePlaceholders = "(${cols.joinToString(", ") { "?" }})"
                val allPlaceholders = rows.joinToString(", ") { singlePlaceholders }
                val colList = cols.joinToString(", ") { quote(it) }
                val sql = "INSERT INTO ${quote(table)} ($colList) VALUES $allPlaceholders RETURNING *"

                conn.prepareStatement(sql).use { stmt ->
                    var idx = 1
                    for (row in rows) {
                        for (col in cols) {
                            codec.bindColumn(stmt, idx++, schema, col, row[col])
                        }
                    }
                    stmt.executeQuery().use { rs ->
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

    private fun updateManyWith(
        conn: Connection,
        table: String,
        values: Map<String, Any?>,
        predicates: List<Predicate<*>>,
    ): Int {
        val schema = schemaFor(table)
        val cols = values.keys.filter { it != schema.idColumn }
        if (cols.isEmpty()) return 0

        val builder = PredicateSqlBuilder(schemas)
        val baseAlias = "t0"

        val setClause = cols.joinToString(", ") { "${quote(it)} = ?" }
        val sql = StringBuilder()
        sql.append("UPDATE ${quote(table)} AS $baseAlias SET $setClause")

        val combined = predicates.andTogether()
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }

        return conn.prepareStatement(sql.toString()).use { stmt ->
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

    private fun deleteManyWith(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
    ): Int {
        val schema = schemaFor(table)
        val builder = PredicateSqlBuilder(schemas)
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("DELETE FROM ${quote(table)} AS $baseAlias")

        val combined = predicates.andTogether()
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }

        return conn.prepareStatement(sql.toString()).use { stmt ->
            for ((i, p) in builder.params.withIndex()) {
                codec.bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeUpdate()
        }
    }

    // ---------- Schema lookup ----------

    /**
     * Run [block] inside a transaction on [conn]. If autocommit is already
     * off (we're inside a transaction), just run the block directly.
     */
    private fun <T> inTransaction(conn: Connection, block: () -> T): T {
        if (!conn.autoCommit) return block()
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun schemaFor(table: String): EntitySchema =
        schemas[table] ?: error("Unregistered table: $table")

    // ---------- Transactions ----------

    override fun <T> withTransaction(block: (Driver) -> T): T {
        val conn = dataSource.connection
        try {
            conn.autoCommit = false
            val txDriver = PostgresTransactionalDriver(conn, this)
            try {
                val result = block(txDriver)
                conn.commit()
                return result
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                txDriver.closed = true
            }
        } finally {
            conn.autoCommit = true
            conn.close()
        }
    }

    // ---------- Locking capabilities (transaction locking) ----------
    //
    // The root (non-transactional) driver advertises both row-lock
    // capabilities so the generated capability-preflight at save-start
    // accepts saves that need them — but the methods themselves only
    // do useful work inside a transaction (a `SELECT ... FOR UPDATE`
    // in auto-commit immediately releases the lock when the statement
    // completes, defeating the purpose). Callers must reach the
    // locking methods through the [PostgresTransactionalDriver]
    // returned inside [withTransaction]; the root driver enforces the
    // contract via the shared `requireTransactionForLocking` helper.

    override val supportsReadRowForUpdate: Boolean
        get() = true

    override fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? {
        // Uniform contract enforcement (transaction locking): the
        // `Driver.readRowForUpdate` interface contract requires
        // implementations to reject non-transactional callers. The
        // helper throws IllegalStateException when `inTransaction`
        // is false, which on the root driver is always.
        requireTransactionForLocking("readRowForUpdate")
        // Unreachable — the helper above always throws on the root
        // because root.inTransaction == false. The explicit error
        // here is defensive: if a future subclass overrides
        // `inTransaction` to return true on a root-class instance,
        // this still surfaces the impossible call site.
        error(
            "PostgresDriver.readRowForUpdate reached the root-class body despite passing the " +
                "transaction check — root must not advertise inTransaction = true.",
        )
    }

    override val supportsOwnerEdgeSerialization: Boolean
        get() = true

    override fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? {
        requireTransactionForLocking("serializeOwnerEdgeAndRead")
        error(
            "PostgresDriver.serializeOwnerEdgeAndRead reached the root-class body despite passing " +
                "the transaction check — root must not advertise inTransaction = true.",
        )
    }

    // Postgres expresses targeted upsert-skip via `ON CONFLICT DO NOTHING`,
    // so insertIgnore is supported on the root (auto-commit) driver too —
    // it's a single idempotent statement, not a multi-step lock.
    override val supportsInsertIgnore: Boolean
        get() = true

    // Phase 1 supports the Postgres pgvector codec; other native codecs are
    // not understood (a schema using one is rejected at register).
    override fun supportsNativeStorage(codec: String): Boolean = codec == "postgres.vector"

    override fun supportsTypedJson(): Boolean = true

    override fun supportsAggregates(): Boolean = true

    // Like the other lock primitives, the relationship lock only does
    // useful work inside a transaction (an advisory lock taken in
    // auto-commit releases immediately), so the root driver advertises the
    // capability but rejects the call; callers reach it via the
    // transaction-scoped driver.
    override val supportsRelationshipSerialization: Boolean
        get() = true

    override fun serializeRelationship(key: entkt.runtime.mutation.RelationshipLockKey) {
        requireTransactionForLocking("serializeRelationship")
        error(
            "PostgresDriver.serializeRelationship reached the root-class body despite passing " +
                "the transaction check — root must not advertise inTransaction = true.",
        )
    }

    /**
     * Lock the row by id with `SELECT ... FOR UPDATE` and return its
     * current contents. Caller is responsible for the surrounding
     * transaction lifecycle. Returns `null` if no row exists.
     */
    internal fun readRowForUpdateWith(conn: Connection, table: String, id: Any): Map<String, Any?>? {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val sql = "SELECT * FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ? FOR UPDATE"
        return conn.prepareStatement(sql).use { stmt ->
            codec.bind(stmt, 1, schema.idType, id)
            stmt.executeQuery().use { rs ->
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
     * [serializeRelationshipWith]). Postgres advisory locks are released
     * automatically at transaction end, so the duration requirement from
     * transaction locking ("held until the enclosing transaction commits or
     * rolls back") is automatic.
     */
    internal fun serializeOwnerEdgeAndReadWith(conn: Connection, table: String, id: Any): Map<String, Any?>? {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        // pg_advisory_xact_lock(int4, int4) — bind table-name hash and
        // id hash as the two key columns. Hash collisions only mean
        // over-serialization (false sharing), never under-serialization.
        val tableKey = table.hashCode()
        val idKey = id.hashCode()
        conn.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").use { stmt ->
            stmt.setInt(1, tableKey)
            stmt.setInt(2, idKey)
            stmt.executeQuery().close()
        }
        // Then read the row inside the held lock.
        return byIdWith(conn, table, id)
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
     * Distinct from [serializeOwnerEdgeAndReadWith], whose key is a single
     * owner row and so cannot coordinate the two orientations.
     */
    internal fun serializeRelationshipWith(conn: Connection, key: entkt.runtime.mutation.RelationshipLockKey) {
        val junctionKey = key.junctionTable.hashCode()
        val columnsKey = key.fkColumns.hashCode()
        conn.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").use { stmt ->
            stmt.setInt(1, junctionKey)
            stmt.setInt(2, columnsKey)
            stmt.executeQuery().close()
        }
    }

    // ---------- Driver exception classification (result variants) ----------

    /**
     * Map a [PSQLException] thrown by this driver to a structured
     * [EntError]. SQLSTATE classes covered in V1:
     *
     *  - `23xxx` (integrity constraint violation): UNIQUE (`23505`),
     *    FOREIGN KEY (`23503`), CHECK (`23514`), NOT NULL (`23502`),
     *    EXCLUSION (`23P01`) — all map to
     *    [EntError.ConstraintViolation] with the SQLSTATE preserved
     *    as `code`. When the server attached `ServerErrorMessage`
     *    metadata (typical for constraint errors), `constraint` and
     *    `field` are populated from it; otherwise they're `null`.
     *
     * Serialization-failure SQLSTATEs (`40001`, `40P01`) deliberately
     * return `null` in V1 — they're the natural fit for
     * [EntError.Conflict] but that variant has no generated path
     * surfacing it yet (the optimistic-locking support will land that).
     * Returning null falls through to `EntError.DriverFailure`, which
     * is the right shape until Conflict has a real consumer.
     *
     * Returns `null` for anything that isn't a PSQLException —
     * `classifyDriverError` will wrap those as `DriverFailure`.
     */
    override fun classifyException(
        throwable: Throwable,
        entity: String,
        operation: entkt.runtime.result.EntOperation,
    ): entkt.runtime.result.EntError? {
        if (throwable !is org.postgresql.util.PSQLException) return null
        val state = throwable.sqlState ?: return null
        if (!state.startsWith("23")) return null
        val server = throwable.serverErrorMessage
        return entkt.runtime.result.EntError.ConstraintViolation(
            entity = entity,
            operation = operation,
            constraint = server?.constraint,
            field = server?.column,
            code = state,
            message = throwable.message ?: "constraint violation",
        )
    }

    // ---------- Transaction-scoped driver ----------

    /**
     * A [Driver] that runs all I/O on a single JDBC [Connection] with
     * `autoCommit = false`. [register] delegates to [root] so DDL never
     * runs inside user transactions. Nested [withTransaction] reuses the
     * same transaction. The driver is block-scoped — [closed] is set to
     * true when the block exits and subsequent calls throw.
     */
    private inner class PostgresTransactionalDriver(
        private val conn: Connection,
        private val root: PostgresDriver,
    ) : Driver {
        @Volatile var closed = false

        private fun checkOpen() {
            check(!closed) { "Transaction driver used after transaction block returned" }
        }

        override fun register(schema: EntitySchema) {
            checkOpen()
            root.register(schema)
        }

        override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
            checkOpen(); return insertWith(conn, table, values)
        }

        override fun insertIgnore(
            table: String,
            values: Map<String, Any?>,
            conflictColumns: List<String>,
        ): Map<String, Any?>? {
            checkOpen(); return insertIgnoreWith(conn, table, values, conflictColumns)
        }

        override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? {
            checkOpen(); return updateWith(conn, table, id, values)
        }

        override fun byId(table: String, id: Any): Map<String, Any?>? {
            checkOpen(); return byIdWith(conn, table, id)
        }

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            checkOpen(); return queryWith(conn, table, predicates, orderBy, limit, offset)
        }

        override fun count(table: String, predicates: List<Predicate<*>>): Long {
            checkOpen(); return countWith(conn, table, predicates)
        }

        override fun exists(table: String, predicates: List<Predicate<*>>): Boolean {
            checkOpen(); return existsWith(conn, table, predicates)
        }

        override fun aggregate(
            table: String,
            function: AggregateFunction,
            column: String?,
            predicates: List<Predicate<*>>,
            groupBy: String?,
        ): List<AggregateResultRow> {
            checkOpen(); return aggregateWith(conn, table, function, column, predicates, groupBy)
        }

        override fun explainQuery(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): QueryExplanation {
            checkOpen(); return root.explainQuery(table, predicates, orderBy, limit, offset)
        }

        override fun explainCount(table: String, predicates: List<Predicate<*>>): QueryExplanation {
            checkOpen(); return root.explainCount(table, predicates)
        }

        override fun delete(table: String, id: Any): Boolean {
            checkOpen(); return deleteWith(conn, table, id)
        }

        override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> {
            checkOpen(); return insertManyWith(conn, table, values)
        }

        override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate<*>>): Int {
            checkOpen(); return updateManyWith(conn, table, values, predicates)
        }

        override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int {
            checkOpen(); return deleteManyWith(conn, table, predicates)
        }

        override fun <T> withTransaction(block: (Driver) -> T): T {
            checkOpen()
            // Nested: reuse the same transaction.
            return block(this)
        }

        // ---------- transaction locking capability surface ----------

        override val inTransaction: Boolean
            get() = true

        override val supportsReadRowForUpdate: Boolean
            get() = root.supportsReadRowForUpdate

        override fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? {
            checkOpen(); return root.readRowForUpdateWith(conn, table, id)
        }

        override val supportsOwnerEdgeSerialization: Boolean
            get() = root.supportsOwnerEdgeSerialization

        override fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? {
            checkOpen(); return root.serializeOwnerEdgeAndReadWith(conn, table, id)
        }

        override val supportsInsertIgnore: Boolean
            get() = root.supportsInsertIgnore

        override fun supportsNativeStorage(codec: String): Boolean = root.supportsNativeStorage(codec)

        override fun supportsTypedJson(): Boolean = root.supportsTypedJson()

        override fun supportsAggregates(): Boolean = root.supportsAggregates()

        override val supportsRelationshipSerialization: Boolean
            get() = root.supportsRelationshipSerialization

        override fun serializeRelationship(key: entkt.runtime.mutation.RelationshipLockKey) {
            checkOpen(); root.serializeRelationshipWith(conn, key)
        }

        // Exception classification delegates to root — the PSQLException
        // shape is the same whether thrown from a tx-scoped or root-
        // scoped statement.
        override fun classifyException(
            throwable: Throwable,
            entity: String,
            operation: entkt.runtime.result.EntOperation,
        ): entkt.runtime.result.EntError? = root.classifyException(throwable, entity, operation)
    }
}
