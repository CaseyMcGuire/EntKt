package entkt.runtime

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.schema.OnDelete
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

    override fun count(table: String, predicates: List<Predicate>): Long {
        schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        val snapshot = synchronized(rows) { rows.map { it.toMap() } }
        return snapshot.count { row -> predicates.all { evaluate(row, it, table) } }.toLong()
    }

    override fun exists(table: String, predicates: List<Predicate>): Boolean {
        schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        val snapshot = synchronized(rows) { rows.map { it.toMap() } }
        return snapshot.any { row -> predicates.all { evaluate(row, it, table) } }
    }

    override fun upsert(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
        immutableColumns: List<String>,
    ): UpsertResult {
        require(conflictColumns.isNotEmpty()) { "upsert requires at least one conflict column" }
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        synchronized(rows) {
            val existing = rows.firstOrNull { row ->
                conflictColumns.all { col -> row[col] == values[col] }
            }
            if (existing != null) {
                for ((k, v) in values) {
                    if (k == schema.idColumn || k in conflictColumns || k in immutableColumns) continue
                    existing[k] = v
                }
                return UpsertResult(existing.toMap(), inserted = false)
            }
            // No conflict — insert a new row.
            val row = values.toMutableMap()
            if (row[schema.idColumn] == null) {
                row[schema.idColumn] = when (schema.idStrategy) {
                    IdStrategy.AUTO_INT -> numericIds.getValue(table).incrementAndGet().toInt()
                    IdStrategy.AUTO_LONG -> numericIds.getValue(table).incrementAndGet()
                    else -> error("insert into $table requires an id (strategy=${schema.idStrategy})")
                }
            }
            rows.add(row)
            return UpsertResult(row.toMap(), inserted = true)
        }
    }

    override fun delete(table: String, id: Any): Boolean {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        synchronized(rows) {
            val matched = rows.filter { it[schema.idColumn] == id }
            if (matched.isEmpty()) return false
            applyReferentialActions(table, matched)
            rows.removeAll(matched.toSet())
            return true
        }
    }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (values.isEmpty()) return emptyList()
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        // Build all rows first, then add atomically — if any row fails
        // (e.g. missing id for EXPLICIT strategy), none are persisted.
        val built = values.map { v ->
            val row = v.toMutableMap()
            if (row[schema.idColumn] == null) {
                row[schema.idColumn] = when (schema.idStrategy) {
                    IdStrategy.AUTO_INT -> numericIds.getValue(table).incrementAndGet().toInt()
                    IdStrategy.AUTO_LONG -> numericIds.getValue(table).incrementAndGet()
                    else -> error("insert into $table requires an id (strategy=${schema.idStrategy})")
                }
            }
            row
        }
        synchronized(rows) { rows.addAll(built) }
        return built.map { it.toMap() }
    }

    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val cols = values.keys.filter { it != schema.idColumn }
        if (cols.isEmpty()) return 0
        val rows = tables.getValue(table)
        synchronized(rows) {
            // Collect matches before mutating so edge predicates that
            // reference the same table see a consistent snapshot.
            val matched = rows.filter { row -> predicates.all { evaluate(row, it, table) } }
            for (row in matched) {
                for (k in cols) {
                    row[k] = values[k]
                }
            }
            return matched.size
        }
    }

    override fun deleteMany(table: String, predicates: List<Predicate>): Int {
        val schema = schemas[table] ?: error("Unregistered table: $table")
        val rows = tables.getValue(table)
        synchronized(rows) {
            // Collect matches before removing so edge predicates that
            // reference the same table see a consistent snapshot.
            val matched = rows.filter { row -> predicates.all { evaluate(row, it, table) } }
            if (matched.isEmpty()) return 0
            applyReferentialActions(table, matched)
            rows.removeAll(matched.toSet())
            return matched.size
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

    // ---------- Referential actions ----------

    /**
     * Enforce ON DELETE referential actions for rows being removed from
     * [sourceTable]. Scans all registered schemas for FK columns that
     * reference [sourceTable] and applies the declared action:
     *
     * - **RESTRICT**: throws if any referencing rows exist.
     * - **SET_NULL**: nulls out FK columns on referencing rows.
     * - **CASCADE**: removes referencing rows and recurses.
     *
     * When no explicit [OnDelete] is declared, the default is inferred
     * from column nullability (SET_NULL for nullable, RESTRICT for required).
     *
     * Must be called *before* the source rows are removed so that a
     * RESTRICT violation prevents the delete.
     */
    private fun applyReferentialActions(sourceTable: String, deletedRows: List<Map<String, Any?>>) {
        if (deletedRows.isEmpty()) return
        // Two-pass: check all RESTRICT constraints first (recursively
        // following CASCADE chains) so that no mutations occur if any
        // RESTRICT violation exists. Then apply SET_NULL and CASCADE.
        // Both passes carry a visited set to guard against cycles
        // (self-referential or circular CASCADE relationships).
        checkRestrict(sourceTable, deletedRows, mutableSetOf())
        applyCascadeAndSetNull(sourceTable, deletedRows, mutableSetOf())
    }

    /** Recursively verify no RESTRICT constraint blocks the delete. */
    private fun checkRestrict(
        sourceTable: String,
        deletedRows: List<Map<String, Any?>>,
        visited: MutableSet<Pair<String, Any>>,
    ) {
        for ((refTable, refSchema) in schemas) {
            for (col in refSchema.columns) {
                val ref = col.references ?: continue
                if (ref.table != sourceTable) continue
                // Build the set of referenced-column values from the deleted rows
                val refValues = deletedRows.mapNotNull { it[ref.column] }.toSet()
                if (refValues.isEmpty()) continue
                val effective = ref.onDelete
                    ?: if (col.nullable) OnDelete.SET_NULL else OnDelete.RESTRICT
                val refRows = tables.getValue(refTable)
                synchronized(refRows) {
                    when (effective) {
                        OnDelete.RESTRICT -> {
                            if (refRows.any { it[col.name] in refValues }) {
                                error(
                                    "foreign key constraint: cannot delete from $sourceTable — " +
                                        "referenced by $refTable.${col.name}",
                                )
                            }
                        }
                        OnDelete.CASCADE -> {
                            // CASCADE would delete these rows — check their RESTRICT constraints too
                            val cascadeRows = refRows.filter { it[col.name] in refValues }
                            if (cascadeRows.isNotEmpty()) {
                                val unvisited = cascadeRows.filter {
                                    visited.add(refTable to it[refSchema.idColumn]!!)
                                }
                                if (unvisited.isNotEmpty()) {
                                    checkRestrict(refTable, unvisited, visited)
                                }
                            }
                        }
                        OnDelete.SET_NULL -> {
                            if (!col.nullable) {
                                error(
                                    "invalid schema: ON DELETE SET NULL on non-nullable column " +
                                        "$refTable.${col.name}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Apply SET_NULL and CASCADE mutations after RESTRICT checks pass. */
    private fun applyCascadeAndSetNull(
        sourceTable: String,
        deletedRows: List<Map<String, Any?>>,
        visited: MutableSet<Pair<String, Any>>,
    ) {
        for ((refTable, refSchema) in schemas) {
            for (col in refSchema.columns) {
                val ref = col.references ?: continue
                if (ref.table != sourceTable) continue
                val refValues = deletedRows.mapNotNull { it[ref.column] }.toSet()
                if (refValues.isEmpty()) continue
                val effective = ref.onDelete
                    ?: if (col.nullable) OnDelete.SET_NULL else OnDelete.RESTRICT
                val refRows = tables.getValue(refTable)
                synchronized(refRows) {
                    when (effective) {
                        OnDelete.RESTRICT -> { /* already checked */ }
                        OnDelete.SET_NULL -> {
                            // Nullability already validated in checkRestrict
                            for (row in refRows) {
                                if (row[col.name] in refValues) row[col.name] = null
                            }
                        }
                        OnDelete.CASCADE -> {
                            val toDelete = refRows.filter { it[col.name] in refValues }
                            val unvisited = toDelete.filter {
                                visited.add(refTable to it[refSchema.idColumn]!!)
                            }
                            if (unvisited.isNotEmpty()) {
                                applyCascadeAndSetNull(refTable, unvisited, visited)
                            }
                            refRows.removeAll(toDelete.toSet())
                        }
                    }
                }
            }
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

        val sourceValue = sourceRow[edge.sourceColumn] ?: return false

        // M2M edge: walk through the junction table.
        if (edge.junctionTable != null) {
            val junctionRows = tables[edge.junctionTable] ?: return false
            val targetRows = tables[edge.targetTable] ?: return false
            val jSrcCol = edge.junctionSourceColumn!!
            val jTgtCol = edge.junctionTargetColumn!!
            synchronized(junctionRows) {
                val matchingJunctions = junctionRows.filter { it[jSrcCol] == sourceValue }
                return synchronized(targetRows) {
                    matchingJunctions.any { jr ->
                        val targetJoinValue = jr[jTgtCol]
                        targetRows.any { tr ->
                            tr[edge.targetColumn] == targetJoinValue &&
                                (innerPredicate == null || evaluate(tr, innerPredicate, edge.targetTable))
                        }
                    }
                }
            }
        }

        // Direct edge: simple column join.
        val targetRows = tables[edge.targetTable] ?: return false
        synchronized(targetRows) {
            return targetRows.any { targetRow ->
                targetRow[edge.targetColumn] == sourceValue &&
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

    override fun count(table: String, predicates: List<Predicate>): Long {
        checkOpen(); return root.count(table, predicates)
    }

    override fun exists(table: String, predicates: List<Predicate>): Boolean {
        checkOpen(); return root.exists(table, predicates)
    }

    override fun upsert(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
        immutableColumns: List<String>,
    ): UpsertResult {
        checkOpen(); return root.upsert(table, values, conflictColumns, immutableColumns)
    }

    override fun delete(table: String, id: Any): Boolean {
        checkOpen(); return root.delete(table, id)
    }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> {
        checkOpen(); return root.insertMany(table, values)
    }

    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int {
        checkOpen(); return root.updateMany(table, values, predicates)
    }

    override fun deleteMany(table: String, predicates: List<Predicate>): Int {
        checkOpen(); return root.deleteMany(table, predicates)
    }

    override fun <T> withTransaction(block: (Driver) -> T): T {
        checkOpen()
        // Nested: reuse the same transaction.
        return block(this)
    }
}
