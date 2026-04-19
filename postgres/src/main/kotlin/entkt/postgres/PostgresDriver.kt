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
import entkt.runtime.UpsertResult
import entkt.schema.FieldType
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
 * A [Driver] backed by a JDBC [DataSource] talking to PostgreSQL.
 *
 * Each call borrows one connection from the pool and runs a single
 * statement. The driver does no caching beyond the per-table
 * [EntitySchema] registry — every [insert]/[update]/[query] hits the
 * database.
 *
 * The schema registry is populated by [register], which also issues a
 * `CREATE TABLE IF NOT EXISTS` derived from the entity's [ColumnMetadata]
 * list. Driver consumers can opt out of auto-DDL by simply not calling
 * `client.users` etc. before running their own migrations — the second
 * `register` is idempotent and won't try to recreate the table.
 *
 * Predicate lowering produces parameterized SQL: leaves become
 * `"col" op ?`, edge predicates become `EXISTS (... )` subqueries
 * walking the registered [EdgeMetadata]. No string concatenation of
 * user values ever happens — only of column and table identifiers
 * (which originate in generated code, never user input).
 */
class PostgresDriver(
    private val dataSource: DataSource,
) : Driver {

    private val schemas: MutableMap<String, EntitySchema> = ConcurrentHashMap()

    override fun register(schema: EntitySchema) {
        if (schemas.containsKey(schema.table)) return
        val ddl = createTableSql(schema)
        val indexDdl = createIndexesSql(schema)
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(ddl)
                for (sql in indexDdl) stmt.execute(sql)
            }
        }
        // Cache only after all DDL succeeds so a failed register() can
        // be retried.
        schemas.putIfAbsent(schema.table, schema)
    }

    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> =
        dataSource.connection.use { insertWith(it, table, values) }

    override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? =
        dataSource.connection.use { updateWith(it, table, id, values) }

    override fun byId(table: String, id: Any): Map<String, Any?>? =
        dataSource.connection.use { byIdWith(it, table, id) }

    override fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> =
        dataSource.connection.use { queryWith(it, table, predicates, orderBy, limit, offset) }

    override fun count(table: String, predicates: List<Predicate>): Long =
        dataSource.connection.use { countWith(it, table, predicates) }

    override fun exists(table: String, predicates: List<Predicate>): Boolean =
        dataSource.connection.use { existsWith(it, table, predicates) }

    override fun upsert(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
        immutableColumns: List<String>,
    ): UpsertResult =
        dataSource.connection.use { upsertWith(it, table, values, conflictColumns, immutableColumns) }

    override fun delete(table: String, id: Any): Boolean =
        dataSource.connection.use { deleteWith(it, table, id) }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> =
        dataSource.connection.use { insertManyWith(it, table, values) }

    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int =
        dataSource.connection.use { updateManyWith(it, table, values, predicates) }

    override fun deleteMany(table: String, predicates: List<Predicate>): Int =
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

    private fun upsertWith(
        conn: Connection,
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
        immutableColumns: List<String> = emptyList(),
    ): UpsertResult {
        require(conflictColumns.isNotEmpty()) { "upsert requires at least one conflict column" }
        val schema = schemaFor(table)
        val skipId = (schema.idStrategy == IdStrategy.AUTO_INT ||
            schema.idStrategy == IdStrategy.AUTO_LONG) &&
            values[schema.idColumn] == null

        val cols = values.keys.filter { !(skipId && it == schema.idColumn) }
        val placeholders = cols.joinToString(", ") { "?" }
        val colList = cols.joinToString(", ") { quote(it) }
        val conflictList = conflictColumns.joinToString(", ") { quote(it) }

        val updateCols = cols.filter { it != schema.idColumn && it !in conflictColumns && it !in immutableColumns }
        // Always produce a SET clause so RETURNING * works on conflict.
        // If there's nothing meaningful to update, do a no-op write on
        // the first conflict column.
        val setClause = if (updateCols.isEmpty()) {
            "${quote(conflictColumns.first())} = EXCLUDED.${quote(conflictColumns.first())}"
        } else {
            updateCols.joinToString(", ") { "${quote(it)} = EXCLUDED.${quote(it)}" }
        }
        // xmax is a PostgreSQL system column: 0 for freshly inserted rows,
        // non-zero when the row was updated via the ON CONFLICT path.
        val sql = "INSERT INTO ${quote(table)} ($colList) VALUES ($placeholders)" +
            " ON CONFLICT ($conflictList) DO UPDATE SET $setClause RETURNING *, (xmax = 0) AS _inserted"

        return conn.prepareStatement(sql).use { stmt ->
            for ((i, col) in cols.withIndex()) {
                bind(stmt, i + 1, columnTypeOf(schema, col), values[col])
            }
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "UPSERT into $table returned no row" }
                val inserted = rs.getBoolean("_inserted")
                UpsertResult(decodeRow(rs, schema.columns), inserted)
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
        // Never let an update rewrite the primary key — same contract as
        // InMemoryDriver.
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

    private fun queryWith(
        conn: Connection,
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> {
        val schema = schemaFor(table)
        val builder = SqlBuilder()
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("SELECT ").append(baseAlias).append(".* FROM ")
            .append(quote(table)).append(" AS ").append(baseAlias)

        val combined = predicates.reduceOrNull(Predicate::And)
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }

        if (orderBy.isNotEmpty()) {
            sql.append(" ORDER BY ")
            sql.append(
                orderBy.joinToString(", ") { of ->
                    val dir = if (of.direction == OrderDirection.ASC) "ASC" else "DESC"
                    "$baseAlias.${quote(of.field)} $dir"
                },
            )
        }

        if (limit != null) sql.append(" LIMIT ").append(limit)
        if (offset != null) sql.append(" OFFSET ").append(offset)

        return conn.prepareStatement(sql.toString()).use { stmt ->
            for ((i, p) in builder.params.withIndex()) {
                bind(stmt, i + 1, p.type, p.value)
            }
            stmt.executeQuery().use { rs ->
                val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) out.add(decodeRow(rs, schema.columns))
                out
            }
        }
    }

    private fun countWith(
        conn: Connection,
        table: String,
        predicates: List<Predicate>,
    ): Long {
        val schema = schemaFor(table)
        val builder = SqlBuilder()
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("SELECT COUNT(*) FROM ")
            .append(quote(table)).append(" AS ").append(baseAlias)

        val combined = predicates.reduceOrNull(Predicate::And)
        if (combined != null) {
            val whereSql = builder.lower(combined, schema, baseAlias)
            sql.append(" WHERE ").append(whereSql)
        }

        return conn.prepareStatement(sql.toString()).use { stmt ->
            for ((i, p) in builder.params.withIndex()) {
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
        predicates: List<Predicate>,
    ): Boolean {
        val schema = schemaFor(table)
        val builder = SqlBuilder()
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("SELECT EXISTS(SELECT 1 FROM ")
            .append(quote(table)).append(" AS ").append(baseAlias)

        val combined = predicates.reduceOrNull(Predicate::And)
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
        predicates: List<Predicate>,
    ): Int {
        val schema = schemaFor(table)
        val cols = values.keys.filter { it != schema.idColumn }
        if (cols.isEmpty()) return 0

        val builder = SqlBuilder()
        val baseAlias = "t0"

        val setClause = cols.joinToString(", ") { "${quote(it)} = ?" }
        val sql = StringBuilder()
        sql.append("UPDATE ${quote(table)} AS $baseAlias SET $setClause")

        val combined = predicates.reduceOrNull(Predicate::And)
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
        predicates: List<Predicate>,
    ): Int {
        val schema = schemaFor(table)
        val builder = SqlBuilder()
        val baseAlias = "t0"

        val sql = StringBuilder()
        sql.append("DELETE FROM ${quote(table)} AS $baseAlias")

        val combined = predicates.reduceOrNull(Predicate::And)
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
                val onDelete = if (col.nullable) "SET NULL" else "RESTRICT"
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
            val cols = idx.columns.joinToString(", ") { quote(it) }
            val rawName = idx.storageKey
                ?: buildString {
                    append("idx_${schema.table}")
                    for (col in idx.columns) append("_$col")
                    if (idx.unique) append("_unique")
                    if (idx.where != null) {
                        append("_w")
                        append(idx.where.hashCode().toUInt().toString(16).take(8))
                    }
                }
            val name = typeMapper.normalizeIdentifier(rawName)
            val keyword = if (idx.unique) "CREATE UNIQUE INDEX" else "CREATE INDEX"
            val whereSuffix = if (idx.where != null) " WHERE ${idx.where}" else ""
            "$keyword IF NOT EXISTS ${quote(name)} ON ${quote(schema.table)} ($cols)$whereSuffix"
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
        typeMapper.sqlTypeFor(col.type, col.primaryKey, schema.idStrategy)

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

        fun lower(predicate: Predicate, schema: EntitySchema, alias: String): String =
            when (predicate) {
                is Predicate.Leaf -> lowerLeaf(predicate, schema, alias)
                is Predicate.And ->
                    "(${lower(predicate.left, schema, alias)} AND ${lower(predicate.right, schema, alias)})"
                is Predicate.Or ->
                    "(${lower(predicate.left, schema, alias)} OR ${lower(predicate.right, schema, alias)})"
                is Predicate.HasEdge -> lowerHasEdge(predicate.edge, null, schema, alias)
                is Predicate.HasEdgeWith ->
                    lowerHasEdge(predicate.edge, predicate.inner, schema, alias)
            }

        private fun lowerLeaf(leaf: Predicate.Leaf, schema: EntitySchema, alias: String): String {
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
                    params.add(Param(FieldType.STRING, "%${leaf.value as String}%"))
                    "$col LIKE ?"
                }
                Op.HAS_PREFIX -> {
                    params.add(Param(FieldType.STRING, "${leaf.value as String}%"))
                    "$col LIKE ?"
                }
                Op.HAS_SUFFIX -> {
                    params.add(Param(FieldType.STRING, "%${leaf.value as String}"))
                    "$col LIKE ?"
                }
            }
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
            inner: Predicate?,
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
        }
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

    // ---------- Identifier quoting ----------

    /**
     * Wrap an identifier in PG's `"..."` quoting and escape embedded
     * quotes defensively. Most identifiers originate in generated
     * schema metadata, but callers can still construct raw predicates
     * and order fields by hand.
     */
    private fun quote(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""

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

        override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? {
            checkOpen(); return updateWith(conn, table, id, values)
        }

        override fun byId(table: String, id: Any): Map<String, Any?>? {
            checkOpen(); return byIdWith(conn, table, id)
        }

        override fun query(
            table: String,
            predicates: List<Predicate>,
            orderBy: List<OrderField>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            checkOpen(); return queryWith(conn, table, predicates, orderBy, limit, offset)
        }

        override fun count(table: String, predicates: List<Predicate>): Long {
            checkOpen(); return countWith(conn, table, predicates)
        }

        override fun exists(table: String, predicates: List<Predicate>): Boolean {
            checkOpen(); return existsWith(conn, table, predicates)
        }

        override fun upsert(
            table: String,
            values: Map<String, Any?>,
            conflictColumns: List<String>,
            immutableColumns: List<String>,
        ): UpsertResult {
            checkOpen(); return upsertWith(conn, table, values, conflictColumns, immutableColumns)
        }

        override fun delete(table: String, id: Any): Boolean {
            checkOpen(); return deleteWith(conn, table, id)
        }

        override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> {
            checkOpen(); return insertManyWith(conn, table, values)
        }

        override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int {
            checkOpen(); return updateManyWith(conn, table, values, predicates)
        }

        override fun deleteMany(table: String, predicates: List<Predicate>): Int {
            checkOpen(); return deleteManyWith(conn, table, predicates)
        }

        override fun <T> withTransaction(block: (Driver) -> T): T {
            checkOpen()
            // Nested: reuse the same transaction.
            return block(this)
        }
    }
}
