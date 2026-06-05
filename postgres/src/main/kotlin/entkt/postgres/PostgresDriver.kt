package entkt.postgres

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.ColumnMetadata
import entkt.runtime.Driver
import entkt.runtime.EdgeMetadata
import entkt.runtime.EntitySchema
import entkt.runtime.IdStrategy
import entkt.runtime.QueryExplanation
import entkt.schema.FieldType
import entkt.schema.OnDelete
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * AND together a list of erased predicates into a single erased
 * predicate, or null when the list is empty. Used by the SQL builders
 * to combine the driver-call's `List<Predicate<*>>` into a single
 * predicate tree for [PostgresDriver.SqlBuilder.lower].
 *
 * The runtime representation of `Predicate.And<E>(left, right)` is a
 * plain wrapper that doesn't inspect E, so combining two erased
 * predicates into an erased And is sound — drivers consume predicates
 * structurally (field/op/value/edge name/source table) and don't
 * introspect the phantom type. The unchecked cast localizes the
 * "phantom doesn't matter at the driver" invariant to one place.
 */
@Suppress("UNCHECKED_CAST")
private fun List<Predicate<*>>.andTogether(): Predicate<*>? =
    reduceOrNull { left, right ->
        Predicate.And(left as Predicate<Any>, right as Predicate<Any>)
    }

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
) : Driver {

    private val schemas: MutableMap<String, EntitySchema> = ConcurrentHashMap()

    override fun register(schema: EntitySchema) {
        if (schemas.containsKey(schema.table)) return

        // Reject native-storage columns whose codec this driver can't handle
        // (Postgres supports postgres.vector; everything else fails here).
        checkNativeStorageSupported(schema)

        if (autoDdl) {
            // Required extensions (e.g. pgvector) must exist before a column
            // using their type is created.
            val extensions = schema.columns
                .mapNotNull { (it.storage as? entkt.schema.ColumnStorage.Native)?.requiredExtension }
                .distinct()
            val ddl = createTableSql(schema)
            val indexDdl = createIndexesSql(schema)
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    for (ext in extensions) stmt.execute("CREATE EXTENSION IF NOT EXISTS ${quote(ext)}")
                    stmt.execute(ddl)
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
                bind(stmt, i + 1, columnTypeOf(schema, col), values[col])
            }
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "INSERT into $table returned no row" }
                decodeRow(rs, schema.columns)
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
                bind(stmt, i + 1, columnTypeOf(schema, col), values[col])
            }
            stmt.executeQuery().use { rs ->
                // No assertion: a conflict legitimately produces zero rows.
                if (rs.next()) decodeRow(rs, schema.columns) else null
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
                bind(stmt, i + 1, columnTypeOf(schema, col), values[col])
            }
            bind(stmt, cols.size + 1, columnTypeOf(schema, schema.idColumn), id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) decodeRow(rs, schema.columns) else null
            }
        }
    }

    private fun byIdWith(conn: Connection, table: String, id: Any): Map<String, Any?>? {
        val schema = schemaFor(table)
        val sql = "SELECT * FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ?"
        return conn.prepareStatement(sql).use { stmt ->
            bind(stmt, 1, columnTypeOf(schema, schema.idColumn), id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) decodeRow(rs, schema.columns) else null
            }
        }
    }

    private data class PreparedSql(val sql: String, val params: List<Param>)

    private fun buildSelectSql(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): PreparedSql {
        val schema = schemaFor(table)
        val builder = SqlBuilder()
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
                        builder.params.add(Param(FieldType.PGVECTOR, distance.operand))
                        "$baseAlias.${quote(of.field)} ${distance.operator.sql} ? $dir"
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
                bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().use { rs ->
                val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) out.add(decodeRow(rs, schema.columns))
                out
            }
        }
    }

    private fun buildCountSql(table: String, predicates: List<Predicate<*>>): PreparedSql {
        val schema = schemaFor(table)
        val builder = SqlBuilder()
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
                bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun existsWith(
        conn: Connection,
        table: String,
        predicates: List<Predicate<*>>,
    ): Boolean {
        val schema = schemaFor(table)
        val builder = SqlBuilder()
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
                bind(stmt, i + 1, p.type, p.value)
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
            bind(stmt, 1, columnTypeOf(schema, schema.idColumn), id)
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
                            bind(stmt, idx++, columnTypeOf(schema, col), row[col])
                        }
                    }
                    stmt.executeQuery().use { rs ->
                        for (ir in indexedRows) {
                            check(rs.next()) { "INSERT RETURNING produced fewer rows than expected" }
                            results[ir.index] = decodeRow(rs, schema.columns)
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

        val builder = SqlBuilder()
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
                bind(stmt, idx++, columnTypeOf(schema, col), values[col])
            }
            for (p in builder.params) {
                bind(stmt, idx++, p.type, p.value)
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
        val builder = SqlBuilder()
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
                bind(stmt, i + 1, p.type, p.value)
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

    private fun columnTypeOf(schema: EntitySchema, name: String): FieldType? =
        schema.columns.firstOrNull { it.name == name }?.type

    // ---------- DDL ----------

    private fun createTableSql(schema: EntitySchema): String {
        val cols = schema.columns.joinToString(",\n  ") { col ->
            renderColumnDdl(schema, col)
        }
        return "CREATE TABLE IF NOT EXISTS ${quote(schema.table)} (\n  $cols\n)"
    }

    private fun renderColumnDdl(schema: EntitySchema, col: ColumnMetadata): String {
        val sqlType = sqlTypeFor(schema, col)
        val constraints = buildList {
            if (col.primaryKey) add("PRIMARY KEY")
            if (!col.nullable && !col.primaryKey && !isAutoSerial(schema, col)) add("NOT NULL")
            val ref = col.references
            if (ref != null) {
                val onDelete = ref.onDelete.toSql(col.nullable)
                add("REFERENCES ${quote(ref.table)}(${quote(ref.column)}) ON DELETE $onDelete")
            }
        }.joinToString(" ")
        val tail = if (constraints.isEmpty()) "" else " $constraints"
        return "${quote(col.name)} $sqlType$tail"
    }

    /**
     * Build `CREATE [UNIQUE] INDEX IF NOT EXISTS` statements for both
     * composite indexes declared via [EntitySchema.indexes] and
     * single-column unique constraints from [ColumnMetadata.unique].
     * Using standalone index DDL (rather than inline `UNIQUE` in
     * `CREATE TABLE`) ensures the constraint is applied even when the
     * table already exists.
     */
    private fun createIndexesSql(schema: EntitySchema): List<String> {
        val columnUniques = schema.columns
            .filter { it.unique && !it.primaryKey }
            .map { col ->
                val name = typeMapper.normalizeIdentifier("idx_${schema.table}_${col.name}_unique")
                "CREATE UNIQUE INDEX IF NOT EXISTS ${quote(name)} ON ${quote(schema.table)} (${quote(col.name)})"
            }

        val compositeIndexes = schema.indexes.map { idx ->
            val name = typeMapper.normalizeIdentifier(idx.name)
            val keyword = if (idx.unique) "CREATE UNIQUE INDEX" else "CREATE INDEX"
            // Native (pgvector) index: USING <method> (col opclass[, ...]) WITH (...).
            // Btree: (col[, ...]) with an optional partial WHERE.
            val cols = if (idx.using != null) {
                idx.columns.mapIndexed { i, c -> "${quote(c)} ${idx.opclasses?.getOrNull(i).orEmpty()}".trim() }
                    .joinToString(", ")
            } else {
                idx.columns.joinToString(", ") { quote(it) }
            }
            val usingClause = if (idx.using != null) " USING ${idx.using}" else ""
            val withClause = idx.with?.takeIf { it.isNotEmpty() }
                ?.entries?.joinToString(", ") { "${it.key} = ${it.value}" }
                ?.let { " WITH ($it)" } ?: ""
            val whereSuffix = if (idx.where != null) " WHERE ${idx.where}" else ""
            "$keyword IF NOT EXISTS ${quote(name)} ON ${quote(schema.table)}$usingClause ($cols)$withClause$whereSuffix"
        }

        return columnUniques + compositeIndexes
    }

    private fun isAutoSerial(schema: EntitySchema, col: ColumnMetadata): Boolean {
        if (!col.primaryKey) return false
        return schema.idStrategy == IdStrategy.AUTO_INT ||
            schema.idStrategy == IdStrategy.AUTO_LONG
    }

    private val typeMapper = PostgresTypeMapper()

    private fun sqlTypeFor(schema: EntitySchema, col: ColumnMetadata): String =
        typeMapper.sqlTypeFor(col.type, col.primaryKey, schema.idStrategy, col.storage)

    // ---------- Predicate lowering ----------

    /**
     * Accumulates parameter bindings as the predicate tree is walked.
     * Each placeholder in the produced SQL corresponds to one entry in
     * [params], in order, so binding is just `bind(stmt, i+1, p.type, p.value)`.
     */
    private inner class SqlBuilder {
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
            }

        private fun lowerLeaf(leaf: Predicate.Leaf<*>, schema: EntitySchema, alias: String): String {
            val col = "$alias.${quote(leaf.field)}"
            val type = columnTypeOf(schema, leaf.field)
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
                Op.IN -> lowerInList(col, leaf.value, type, negated = false)
                Op.NOT_IN -> lowerInList(col, leaf.value, type, negated = true)
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
            negated: Boolean,
        ): String {
            val items = (value as Collection<*>).toList()
            if (items.isEmpty()) {
                // Empty IN: matches nothing. Empty NOT IN: matches everything.
                return if (negated) "TRUE" else "FALSE"
            }
            val placeholders = items.joinToString(", ") { "?" }
            for (item in items) params.add(Param(type, item))
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
            val targetSchema = schemas[edge.targetTable]
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
            val sourceSchema = schemas[predicate.sourceTable]
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

    private data class Param(val type: FieldType?, val value: Any?)

    // ---------- Binding & decoding ----------

    private fun bind(stmt: PreparedStatement, idx: Int, type: FieldType?, value: Any?) {
        if (value == null) {
            stmt.setNull(idx, jdbcTypeFor(type))
            return
        }
        when (type) {
            FieldType.STRING, FieldType.TEXT, FieldType.ENUM ->
                stmt.setString(idx, value as String)
            FieldType.BOOL -> stmt.setBoolean(idx, value as Boolean)
            FieldType.INT -> stmt.setInt(idx, (value as Number).toInt())
            FieldType.LONG -> stmt.setLong(idx, (value as Number).toLong())
            FieldType.FLOAT -> stmt.setFloat(idx, (value as Number).toFloat())
            FieldType.DOUBLE -> stmt.setDouble(idx, (value as Number).toDouble())
            FieldType.TIME -> {
                val instant = when (value) {
                    is Instant -> value
                    is OffsetDateTime -> value.toInstant()
                    else -> error("Unsupported TIME value: ${value::class}")
                }
                stmt.setObject(idx, instant.atOffset(ZoneOffset.UTC))
            }
            FieldType.UUID -> stmt.setObject(idx, value as UUID)
            FieldType.BYTES -> stmt.setBytes(idx, value as ByteArray)
            // pgvector: bind as a PGobject of type "vector" with the canonical
            // "[f0,f1,...]" text. Postgres rejects a wrong-dimension literal
            // against a vector(n) column, so this is the defensive backstop to
            // the generated setter's dimension check.
            FieldType.PGVECTOR -> {
                val obj = org.postgresql.util.PGobject()
                obj.type = "vector"
                obj.value = pgVectorLiteral(value as entkt.postgres.vector.PgVector)
                stmt.setObject(idx, obj)
            }
            null -> stmt.setObject(idx, value)
        }
    }

    private fun jdbcTypeFor(type: FieldType?): Int = when (type) {
        FieldType.STRING, FieldType.TEXT, FieldType.ENUM -> Types.VARCHAR
        FieldType.BOOL -> Types.BOOLEAN
        FieldType.INT -> Types.INTEGER
        FieldType.LONG -> Types.BIGINT
        FieldType.FLOAT -> Types.REAL
        FieldType.DOUBLE -> Types.DOUBLE
        FieldType.TIME -> Types.TIMESTAMP_WITH_TIMEZONE
        FieldType.UUID -> Types.OTHER
        FieldType.BYTES -> Types.BINARY
        FieldType.PGVECTOR -> Types.OTHER
        null -> Types.OTHER
    }

    private fun decodeRow(rs: ResultSet, columns: List<ColumnMetadata>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(columns.size)
        for (col in columns) {
            out[col.name] = decodeColumn(rs, col)
        }
        return out
    }

    private fun decodeColumn(rs: ResultSet, col: ColumnMetadata): Any? {
        return when (col.type) {
            FieldType.STRING, FieldType.TEXT, FieldType.ENUM -> rs.getString(col.name)
            FieldType.BOOL -> {
                val v = rs.getBoolean(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.INT -> {
                val v = rs.getInt(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.LONG -> {
                val v = rs.getLong(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.FLOAT -> {
                val v = rs.getFloat(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.DOUBLE -> {
                val v = rs.getDouble(col.name)
                if (rs.wasNull()) null else v
            }
            FieldType.TIME ->
                rs.getObject(col.name, OffsetDateTime::class.java)?.toInstant()
            FieldType.UUID -> rs.getObject(col.name, UUID::class.java)
            FieldType.BYTES -> rs.getBytes(col.name)
            // pgvector decodes to its "[f0,f1,...]" text; parse back to PgVector.
            FieldType.PGVECTOR -> rs.getString(col.name)?.let { parsePgVector(it) }
        }
    }

    private fun pgVectorLiteral(v: entkt.postgres.vector.PgVector): String =
        v.toFloatArray().joinToString(",", "[", "]")

    private fun parsePgVector(text: String): entkt.postgres.vector.PgVector {
        val inner = text.trim().removeSurrounding("[", "]").trim()
        val floats = if (inner.isEmpty()) FloatArray(0)
        else inner.split(",").map { it.trim().toFloat() }.toFloatArray()
        return entkt.postgres.vector.PgVector.of(floats)
    }

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

    // Like the other lock primitives, the relationship lock only does
    // useful work inside a transaction (an advisory lock taken in
    // auto-commit releases immediately), so the root driver advertises the
    // capability but rejects the call; callers reach it via the
    // transaction-scoped driver.
    override val supportsRelationshipSerialization: Boolean
        get() = true

    override fun serializeRelationship(key: entkt.runtime.RelationshipLockKey) {
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
            bind(stmt, 1, schema.idType, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) decodeRow(rs, schema.columns) else null
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
     * [RelationshipLockKey][entkt.runtime.RelationshipLockKey] (junction
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
    internal fun serializeRelationshipWith(conn: Connection, key: entkt.runtime.RelationshipLockKey) {
        val junctionKey = key.junctionTable.hashCode()
        val columnsKey = key.fkColumns.hashCode()
        conn.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").use { stmt ->
            stmt.setInt(1, junctionKey)
            stmt.setInt(2, columnsKey)
            stmt.executeQuery().close()
        }
    }

    private val EntitySchema.idType: FieldType?
        get() = columns.firstOrNull { it.name == idColumn }?.type

    // ---------- Identifier quoting ----------

    /**
     * Wrap an identifier in PG's `"..."` quoting and escape embedded
     * quotes defensively. Most identifiers originate in generated
     * schema metadata, but callers can still construct raw predicates
     * and order fields by hand.
     */
    private fun quote(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""

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
        operation: entkt.runtime.EntOperation,
    ): entkt.runtime.EntError? {
        if (throwable !is org.postgresql.util.PSQLException) return null
        val state = throwable.sqlState ?: return null
        if (!state.startsWith("23")) return null
        val server = throwable.serverErrorMessage
        return entkt.runtime.EntError.ConstraintViolation(
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

        override val supportsRelationshipSerialization: Boolean
            get() = root.supportsRelationshipSerialization

        override fun serializeRelationship(key: entkt.runtime.RelationshipLockKey) {
            checkOpen(); root.serializeRelationshipWith(conn, key)
        }

        // Exception classification delegates to root — the PSQLException
        // shape is the same whether thrown from a tx-scoped or root-
        // scoped statement.
        override fun classifyException(
            throwable: Throwable,
            entity: String,
            operation: entkt.runtime.EntOperation,
        ): entkt.runtime.EntError? = root.classifyException(throwable, entity, operation)
    }
}
