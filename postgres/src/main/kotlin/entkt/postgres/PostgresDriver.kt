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
import entkt.schema.FieldType
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
        if (schemas.putIfAbsent(schema.table, schema) != null) return
        val ddl = createTableSql(schema)
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(ddl) }
        }
    }

    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
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

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                for ((i, col) in cols.withIndex()) {
                    bind(stmt, i + 1, columnTypeOf(schema, col), values[col])
                }
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT into $table returned no row" }
                    decodeRow(rs, schema.columns)
                }
            }
        }
    }

    override fun update(
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
            return byId(table, id)
        }
        val setClause = cols.joinToString(", ") { "${quote(it)} = ?" }
        val sql = "UPDATE ${quote(table)} SET $setClause WHERE ${quote(schema.idColumn)} = ? RETURNING *"

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                for ((i, col) in cols.withIndex()) {
                    bind(stmt, i + 1, columnTypeOf(schema, col), values[col])
                }
                bind(stmt, cols.size + 1, columnTypeOf(schema, schema.idColumn), id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) decodeRow(rs, schema.columns) else null
                }
            }
        }
    }

    override fun byId(table: String, id: Any): Map<String, Any?>? {
        val schema = schemaFor(table)
        val sql = "SELECT * FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ?"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                bind(stmt, 1, columnTypeOf(schema, schema.idColumn), id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) decodeRow(rs, schema.columns) else null
                }
            }
        }
    }

    override fun query(
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

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql.toString()).use { stmt ->
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
    }

    override fun delete(table: String, id: Any): Boolean {
        val schema = schemaFor(table)
        val sql = "DELETE FROM ${quote(table)} WHERE ${quote(schema.idColumn)} = ?"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                bind(stmt, 1, columnTypeOf(schema, schema.idColumn), id)
                stmt.executeUpdate() > 0
            }
        }
    }

    // ---------- Schema lookup ----------

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
        }.joinToString(" ")
        val tail = if (constraints.isEmpty()) "" else " $constraints"
        return "${quote(col.name)} $sqlType$tail"
    }

    private fun isAutoSerial(schema: EntitySchema, col: ColumnMetadata): Boolean {
        if (!col.primaryKey) return false
        return schema.idStrategy == IdStrategy.AUTO_INT ||
            schema.idStrategy == IdStrategy.AUTO_LONG
    }

    private fun sqlTypeFor(schema: EntitySchema, col: ColumnMetadata): String {
        if (col.primaryKey) {
            when (schema.idStrategy) {
                IdStrategy.AUTO_INT -> return "serial"
                IdStrategy.AUTO_LONG -> return "bigserial"
                else -> Unit
            }
        }
        return when (col.type) {
            FieldType.STRING, FieldType.TEXT, FieldType.ENUM -> "text"
            FieldType.BOOL -> "boolean"
            FieldType.INT -> "integer"
            FieldType.LONG -> "bigint"
            FieldType.FLOAT -> "real"
            FieldType.DOUBLE -> "double precision"
            FieldType.TIME -> "timestamptz"
            FieldType.UUID -> "uuid"
            FieldType.BYTES -> "bytea"
        }
    }

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

    // ---------- Identifier quoting ----------

    /**
     * Wrap a snake_case identifier in PG's `"..."` quoting so reserved
     * words don't collide. Identifiers come from generated schema and
     * codegen — never from user input — so we don't need to escape
     * embedded quotes.
     */
    private fun quote(identifier: String): String = "\"$identifier\""
}
