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

    override fun explainQuery(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): QueryExplanation {
        schemas[table] ?: error("Unregistered table: $table")
        return InMemoryQueryExplanation(table, predicates, orderBy, limit, offset)
    }

    override fun explainCount(table: String, predicates: List<Predicate>): QueryExplanation {
        schemas[table] ?: error("Unregistered table: $table")
        return InMemoryCountExplanation(table, predicates)
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

    // ---------- Locking capabilities (RFC #4) ----------
    //
    // The in-memory driver does NOT support true row-lock or
    // cooperative owner-edge serialization semantics — its per-table
    // `synchronized` blocks scope only individual driver calls, not
    // "until transaction end". A row read here is not held against
    // concurrent updates from other transactions; a single transaction
    // worth of compound read-then-write is not atomic (see the class
    // doc above).
    //
    // Both capability flags therefore report `false` and the methods
    // throw [UnsupportedOperationException] — generated saves under
    // `UpdateConsistency.Pessimistic` (RFC #4 Phase 3) are rejected
    // at the capability gate when running on this driver, and the
    // link-table M2M owner-edge serialization (RFC #5) is similarly
    // rejected.
    //
    // Tests that need to exercise the codegen Pessimistic happy path
    // wrap this driver in a thin capability-advertising adapter (see
    // `LockSupportInMemoryDriver` in the integration-tests module);
    // tests that need real concurrent-correctness checks belong in
    // the Postgres driver suite, where the lock semantics are real.

    override val supportsReadRowForUpdate: Boolean
        get() = false

    override val supportsOwnerEdgeSerialization: Boolean
        get() = false

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
            is Predicate.HasM2MEdgeFrom ->
                hasInverseM2M(row, predicate)
        }
    }

    /**
     * Returns true iff at least one row in [Predicate.HasM2MEdgeFrom.sourceTable]
     * matching [Predicate.HasM2MEdgeFrom.sourceFilter] has the candidate
     * `targetRow` as its M2M target via the forward edge declared on
     * the source schema. Walks the junction backwards: candidate.id →
     * junctionTargetColumn → matching junction rows → junctionSourceColumn
     * → source row → optional inner-predicate check.
     */
    private fun hasInverseM2M(
        targetRow: Map<String, Any?>,
        predicate: Predicate.HasM2MEdgeFrom,
    ): Boolean {
        val sourceSchema = schemas[predicate.sourceTable]
            ?: error("HasM2MEdgeFrom: unregistered source table ${predicate.sourceTable}")
        val edge = sourceSchema.edges[predicate.edgeName]
            ?: error("HasM2MEdgeFrom: edge ${predicate.sourceTable}.${predicate.edgeName} has no metadata")
        val junctionTable = edge.junctionTable
            ?: error("HasM2MEdgeFrom: edge ${predicate.sourceTable}.${predicate.edgeName} is not M2M")
        val jSrcCol = edge.junctionSourceColumn!!
        val jTgtCol = edge.junctionTargetColumn!!

        val candidateJoinValue = targetRow[edge.targetColumn] ?: return false
        val junctionRows = tables[junctionTable] ?: return false
        val sourceRows = tables[predicate.sourceTable] ?: return false

        synchronized(junctionRows) {
            val matchingJunctions = junctionRows.filter { it[jTgtCol] == candidateJoinValue }
            val sourceFilter = predicate.sourceFilter
            return synchronized(sourceRows) {
                matchingJunctions.any { jr ->
                    val sourceJoinValue = jr[jSrcCol]
                    sourceRows.any { sr ->
                        sr[edge.sourceColumn] == sourceJoinValue &&
                            (sourceFilter == null || evaluate(sr, sourceFilter, predicate.sourceTable))
                    }
                }
            }
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

    override fun delete(table: String, id: Any): Boolean {
        checkOpen(); return root.delete(table, id)
    }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> {
        checkOpen(); return root.insertMany(table, values)
    }

    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int {
        checkOpen(); return root.updateMany(table, values, predicates)
    }

    override fun explainQuery(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): QueryExplanation {
        checkOpen(); return root.explainQuery(table, predicates, orderBy, limit, offset)
    }

    override fun explainCount(table: String, predicates: List<Predicate>): QueryExplanation {
        checkOpen(); return root.explainCount(table, predicates)
    }

    override fun deleteMany(table: String, predicates: List<Predicate>): Int {
        checkOpen(); return root.deleteMany(table, predicates)
    }

    override fun <T> withTransaction(block: (Driver) -> T): T {
        checkOpen()
        // Nested: reuse the same transaction.
        return block(this)
    }

    // ---------- RFC #4 capability surface ----------

    override val inTransaction: Boolean
        get() = true

    // Both row-lock capabilities are inherited from the root driver
    // (which reports `false`). The transactional wrapper would need
    // to implement transaction-scoped lock tracking to honestly
    // advertise true row-lock semantics; in-memory is not that
    // driver — concurrent-correctness tests belong on Postgres.
}

/**
 * Explanation returned by [InMemoryDriver.explainQuery]. Since
 * InMemoryDriver doesn't produce SQL, this exposes the raw query
 * parameters the driver would evaluate in memory.
 */
data class InMemoryQueryExplanation(
    val table: String,
    val predicates: List<Predicate>,
    val orderBy: List<OrderField>,
    val limit: Int?,
    val offset: Int?,
) : QueryExplanation {
    override fun describe(): String = buildString {
        append("InMemory($table")
        if (predicates.isNotEmpty()) append(", predicates=$predicates")
        if (orderBy.isNotEmpty()) append(", orderBy=$orderBy")
        if (limit != null) append(", limit=$limit")
        if (offset != null) append(", offset=$offset")
        append(")")
    }
}

data class InMemoryCountExplanation(
    val table: String,
    val predicates: List<Predicate>,
) : QueryExplanation {
    override fun describe(): String = buildString {
        append("InMemoryCount($table")
        if (predicates.isNotEmpty()) append(", predicates=$predicates")
        append(")")
    }
}
