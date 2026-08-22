package entkt.integrationtest.support

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow

/**
 * Delegating [DatabaseDriver] wrapper that records every data-path call while
 * forwarding to a real driver — the sanctioned "narrow fake DatabaseDriver
 * local to the test" pattern (see
 * docs/implemented-features/tooling/remove-in-memory-driver.md). Used
 * to prove no-I/O contracts (projections perform zero driver calls;
 * assignment-free updates never write) without weakening the Postgres
 * backing of the rest of the suite.
 *
 * Transaction-scoped sub-drivers share the same call list so calls
 * made inside `withTransaction` are still counted.
 */
class RecordingDriver private constructor(
    private val delegate: DatabaseDriver,
    val calls: MutableList<String>,
) : DatabaseDriver by delegate {

    constructor(delegate: DatabaseDriver) : this(delegate, mutableListOf())

    fun reset() = calls.clear()

    fun callCount(prefix: String? = null): Int =
        if (prefix == null) calls.size else calls.count { it.startsWith(prefix) }

    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
        calls += "insert:$table"
        return delegate.insert(table, values)
    }

    override fun insertIgnore(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
    ): Map<String, Any?>? {
        calls += "insertIgnore:$table"
        return delegate.insertIgnore(table, values, conflictColumns)
    }

    override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? {
        calls += "update:$table"
        return delegate.update(table, id, values)
    }

    override fun byId(table: String, id: Any): Map<String, Any?>? {
        calls += "byId:$table"
        return delegate.byId(table, id)
    }

    override fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> {
        calls += "query:$table"
        return delegate.query(table, predicates, orderBy, limit, offset)
    }

    // Capability probes are pure and unrecorded; the read itself is
    // the data-path call. Recorded under its own name so call-list
    // pins distinguish the native lowering from an ordinary query.
    @OptIn(entkt.query.EntktInternal::class)
    override fun queryDirectToMany(
        query: entkt.runtime.driver.DirectToManyQuery,
    ): entkt.runtime.driver.RelatedRows {
        calls += "queryDirectToMany:${query.targetTable}"
        return delegate.queryDirectToMany(query)
    }

    override fun count(table: String, predicates: List<Predicate<*>>): Long {
        calls += "count:$table"
        return delegate.count(table, predicates)
    }

    override fun exists(table: String, predicates: List<Predicate<*>>): Boolean {
        calls += "exists:$table"
        return delegate.exists(table, predicates)
    }

    override fun aggregate(
        table: String,
        function: AggregateFunction,
        column: String?,
        predicates: List<Predicate<*>>,
        groupBy: String?,
    ): List<AggregateResultRow> {
        calls += "aggregate:$table"
        return delegate.aggregate(table, function, column, predicates, groupBy)
    }

    override fun delete(table: String, id: Any): Boolean {
        calls += "delete:$table"
        return delegate.delete(table, id)
    }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> {
        calls += "insertMany:$table"
        return delegate.insertMany(table, values)
    }

    override fun updateMany(
        table: String,
        values: Map<String, Any?>,
        predicates: List<Predicate<*>>,
    ): Int {
        calls += "updateMany:$table"
        return delegate.updateMany(table, values, predicates)
    }

    override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int {
        calls += "deleteMany:$table"
        return delegate.deleteMany(table, predicates)
    }

    override fun deleteManyByIds(
        table: String,
        idColumn: String,
        ids: List<Any>,
        predicates: List<Predicate<*>>,
    ): List<Any> {
        calls += "deleteManyByIds:$table"
        return delegate.deleteManyByIds(table, idColumn, ids, predicates)
    }

    override fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? {
        calls += "readRowForUpdate:$table"
        return delegate.readRowForUpdate(table, id)
    }

    override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> {
        calls += "withTransaction"
        return delegate.withTransaction { txDriver ->
            block(RecordingDriver(txDriver, calls))
        }
    }
}
