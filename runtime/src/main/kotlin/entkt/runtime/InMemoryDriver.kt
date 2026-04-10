package entkt.runtime

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A real driver that keeps every table in process memory. Useful for
 * tests, demos, and validating the API surface end to end before any
 * SQL backend exists.
 *
 * Rows are stored as `Map<String, Any?>` with their typed values
 * intact (`Instant`, `UUID`, `Boolean`, etc.) so predicate evaluation
 * is plain Kotlin comparison — no string coercion.
 *
 * Edge predicates (`HasEdge` / `HasEdgeWith`) are evaluated by
 * recursively scanning the related table using the [EdgeMetadata]
 * registered for the source schema. The same machinery handles
 * traversal — generated `queryX()` methods just lower into a
 * `HasEdgeWith` against the inverse edge.
 *
 * Thread-safe: tables and id counters use concurrent collections, but
 * note that compound operations (read-then-write) are not atomic. The
 * driver is good enough for tests and single-process demos; it is not
 * a database.
 */
class InMemoryDriver : Driver {

    private val schemas: MutableMap<String, EntitySchema> = ConcurrentHashMap()

    /**
     * Tables keyed by table name. Each table is a list of row maps.
     * Order matters for the implicit insertion-order ordering when no
     * `orderBy` is supplied.
     */
    private val tables: MutableMap<String, MutableList<MutableMap<String, Any?>>> =
        ConcurrentHashMap()

    /** Per-table numeric id counters for AUTO_INT / AUTO_LONG strategies. */
    private val numericIds: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    override fun register(schema: EntitySchema) {
        schemas.putIfAbsent(schema.table, schema)
        tables.putIfAbsent(schema.table, mutableListOf())
        if (schema.idStrategy == IdStrategy.AUTO_INT || schema.idStrategy == IdStrategy.AUTO_LONG) {
            numericIds.putIfAbsent(schema.table, AtomicLong(0L))
        }
    }

    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)

        val row = values.toMutableMap()
        if (row[schema.idColumn] == null) {
            // Caller didn't supply an id; mint one according to strategy.
            row[schema.idColumn] = when (schema.idStrategy) {
                IdStrategy.AUTO_INT -> numericIds.getValue(table).incrementAndGet().toInt()
                IdStrategy.AUTO_LONG -> numericIds.getValue(table).incrementAndGet()
                else -> error(
                    "insert into $table requires an id (strategy=${schema.idStrategy})",
                )
            }
        }
        synchronized(rows) { rows.add(row) }
        return row.toMap()
    }

    override fun update(
        table: String,
        id: Any,
        values: Map<String, Any?>,
    ): Map<String, Any?>? {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        synchronized(rows) {
            val existing = rows.firstOrNull { it[schema.idColumn] == id } ?: return null
            for ((k, v) in values) {
                if (k == schema.idColumn) continue // never let an update rewrite the id
                existing[k] = v
            }
            return existing.toMap()
        }
    }

    override fun byId(table: String, id: Any): Map<String, Any?>? {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        synchronized(rows) {
            return rows.firstOrNull { it[schema.idColumn] == id }?.toMap()
        }
    }

    override fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> {
        schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        val snapshot = synchronized(rows) { rows.map { it.toMap() } }

        val filtered = snapshot.filter { row -> predicates.all { evaluate(row, it, table) } }
        val ordered = if (orderBy.isEmpty()) filtered else filtered.sortedWith(comparatorFor(orderBy))
        val skipped = if (offset != null) ordered.drop(offset) else ordered
        val limited = if (limit != null) skipped.take(limit) else skipped
        return limited
    }

    override fun delete(table: String, id: Any): Boolean {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        synchronized(rows) {
            return rows.removeAll { it[schema.idColumn] == id }
        }
    }

    override fun <T> withTransaction(block: (Driver) -> T): T {
        // Snapshot current state: table names, their contents, and id counters.
        val knownTables = tables.keys.toSet()
        val tableSnapshot = mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()
        for ((name, rows) in tables) {
            synchronized(rows) {
                tableSnapshot[name] = rows.map { it.toMutableMap() }.toMutableList()
            }
        }
        val knownCounters = numericIds.keys.toSet()
        val idSnapshot = numericIds.mapValues { (_, counter) -> counter.get() }

        val txDriver = InMemoryTransactionalDriver(this)
        try {
            return block(txDriver)
        } catch (e: Throwable) {
            // Remove tables/schemas/counters that were first registered
            // inside the transaction — they shouldn't survive rollback.
            for (name in tables.keys - knownTables) {
                tables.remove(name)
                schemas.remove(name)
            }
            for (name in numericIds.keys - knownCounters) {
                numericIds.remove(name)
            }
            // Restore pre-existing tables from snapshot.
            for ((name, snapshot) in tableSnapshot) {
                val rows = tables[name] ?: continue
                synchronized(rows) {
                    rows.clear()
                    rows.addAll(snapshot)
                }
            }
            // Restore id counters.
            for ((name, value) in idSnapshot) {
                numericIds[name]?.set(value)
            }
            throw e
        } finally {
            txDriver.closed = true
        }
    }

    // ---------- Predicate evaluation ----------

    /**
     * Evaluate [predicate] against [row]. [sourceTable] is the name of
     * the table the row was loaded from — needed so we can resolve
     * `HasEdge`/`HasEdgeWith` predicates through the source schema's
     * edge metadata.
     */
    private fun evaluate(
        row: Map<String, Any?>,
        predicate: Predicate,
        sourceTable: String,
    ): Boolean {
        return when (predicate) {
            is Predicate.Leaf -> evaluateLeaf(row, predicate)
            is Predicate.And ->
                evaluate(row, predicate.left, sourceTable) &&
                    evaluate(row, predicate.right, sourceTable)
            is Predicate.Or ->
                evaluate(row, predicate.left, sourceTable) ||
                    evaluate(row, predicate.right, sourceTable)
            is Predicate.HasEdge -> hasAnyRelated(row, predicate.edge, sourceTable, null)
            is Predicate.HasEdgeWith ->
                hasAnyRelated(row, predicate.edge, sourceTable, predicate.inner)
        }
    }

    private fun evaluateLeaf(row: Map<String, Any?>, leaf: Predicate.Leaf): Boolean {
        val value = row[leaf.field]
        return when (leaf.op) {
            Op.EQ -> value == leaf.value
            Op.NEQ -> value != leaf.value
            Op.GT -> compare(value, leaf.value) > 0
            Op.GTE -> compare(value, leaf.value) >= 0
            Op.LT -> compare(value, leaf.value) < 0
            Op.LTE -> compare(value, leaf.value) <= 0
            Op.IN -> (leaf.value as Collection<*>).contains(value)
            Op.NOT_IN -> !(leaf.value as Collection<*>).contains(value)
            Op.IS_NULL -> value == null
            Op.IS_NOT_NULL -> value != null
            Op.CONTAINS -> (value as? String)?.contains(leaf.value as String) == true
            Op.HAS_PREFIX -> (value as? String)?.startsWith(leaf.value as String) == true
            Op.HAS_SUFFIX -> (value as? String)?.endsWith(leaf.value as String) == true
        }
    }

    /**
     * Returns true iff there exists at least one row in the target
     * table that joins to [sourceRow] via [edgeName] and matches
     * [innerPredicate] (or any row if [innerPredicate] is null).
     */
    private fun hasAnyRelated(
        sourceRow: Map<String, Any?>,
        edgeName: String,
        sourceTable: String,
        innerPredicate: Predicate?,
    ): Boolean {
        val sourceSchema = schemas[sourceTable]
            ?: error("Unregistered source table: $sourceTable")
        val edge = sourceSchema.edges[edgeName]
            ?: error("Edge $sourceTable.$edgeName has no metadata — was the schema registered?")

        val joinValue = sourceRow[edge.sourceColumn] ?: return false
        val targetRows = tables[edge.targetTable] ?: return false

        synchronized(targetRows) {
            return targetRows.any { targetRow ->
                targetRow[edge.targetColumn] == joinValue &&
                    (innerPredicate == null || evaluate(targetRow, innerPredicate, edge.targetTable))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(a: Any?, b: Any?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        return (a as Comparable<Any>).compareTo(b)
    }

    private fun comparatorFor(orderBy: List<OrderField>): Comparator<Map<String, Any?>> {
        return Comparator { left, right ->
            for (of in orderBy) {
                val cmp = compare(left[of.field], right[of.field])
                if (cmp != 0) {
                    return@Comparator if (of.direction == OrderDirection.ASC) cmp else -cmp
                }
            }
            0
        }
    }
}

/**
 * Thin wrapper returned by [InMemoryDriver.withTransaction]. All I/O
 * methods delegate to the root driver so mutations are visible within
 * the transaction. [register] also delegates to the root so schema
 * state is shared. Nested [withTransaction] reuses the same
 * transaction (no savepoints). The wrapper is block-scoped — [closed]
 * is set to true when the block exits and any subsequent call throws.
 */
private class InMemoryTransactionalDriver(
    private val root: InMemoryDriver,
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
        checkOpen(); return root.insert(table, values)
    }

    override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? {
        checkOpen(); return root.update(table, id, values)
    }

    override fun byId(table: String, id: Any): Map<String, Any?>? {
        checkOpen(); return root.byId(table, id)
    }

    override fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> {
        checkOpen(); return root.query(table, predicates, orderBy, limit, offset)
    }

    override fun delete(table: String, id: Any): Boolean {
        checkOpen(); return root.delete(table, id)
    }

    override fun <T> withTransaction(block: (Driver) -> T): T {
        checkOpen()
        // Nested: reuse the same transaction.
        return block(this)
    }
}
