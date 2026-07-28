package entkt.postgres

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.query.QueryExplanation
import entkt.runtime.driver.Driver
import entkt.runtime.driver.EntitySchema
import java.sql.Connection

/**
 * A [Driver] that runs all I/O on a single JDBC [Connection] with
 * `autoCommit = false`. Every operation delegates to the shared
 * [PostgresOperations] core with the pinned connection; [register]
 * delegates to [root] so DDL never runs inside user transactions.
 * Nested [withTransaction] reuses the same transaction. The driver is
 * block-scoped — [closed] is set to true when
 * [PostgresDriver.withTransaction]'s block exits and subsequent calls throw.
 */
internal class PostgresTransactionalDriver(
    private val conn: Connection,
    private val root: PostgresDriver,
    private val ops: PostgresOperations,
) : Driver {
    @Volatile var closed = false

    private fun checkOpen() {
        check(!closed) { "Transaction driver used after transaction block returned" }
    }

    override fun register(schema: EntitySchema) {
        checkOpen()
        root.register(schema)
    }

    /**
     * Forwarded to the root driver, like [register].
     *
     * Registration state belongs to the root, not to a connection: a
     * generated client built inside `withTransaction` re-registers the
     * same schemas, and the root's cache makes that a no-op with no I/O.
     * Forwarding explicitly (rather than inheriting any sequential
     * default) also keeps the create-all-tables-then-all-constraints
     * ordering intact if a batch ever does reach here with new schemas.
     */
    override fun registerAll(schemas: List<EntitySchema>) {
        checkOpen()
        root.registerAll(schemas)
    }

    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
        checkOpen(); return ops.insert(conn, table, values)
    }

    override fun insertIgnore(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
    ): Map<String, Any?>? {
        checkOpen(); return ops.insertIgnore(conn, table, values, conflictColumns)
    }

    override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? {
        checkOpen(); return ops.update(conn, table, id, values)
    }

    override fun byId(table: String, id: Any): Map<String, Any?>? {
        checkOpen(); return ops.byId(conn, table, id)
    }

    override fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> {
        checkOpen(); return ops.query(conn, table, predicates, orderBy, limit, offset)
    }

    override fun count(table: String, predicates: List<Predicate<*>>): Long {
        checkOpen(); return ops.count(conn, table, predicates)
    }

    override fun exists(table: String, predicates: List<Predicate<*>>): Boolean {
        checkOpen(); return ops.exists(conn, table, predicates)
    }

    override fun aggregate(
        table: String,
        function: AggregateFunction,
        column: String?,
        predicates: List<Predicate<*>>,
        groupBy: String?,
    ): List<AggregateResultRow> {
        checkOpen(); return ops.aggregate(conn, table, function, column, predicates, groupBy)
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
        checkOpen(); return ops.delete(conn, table, id)
    }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> {
        checkOpen(); return ops.insertMany(conn, table, values)
    }

    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate<*>>): Int {
        checkOpen(); return ops.updateMany(conn, table, values, predicates)
    }

    override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int {
        checkOpen(); return ops.deleteMany(conn, table, predicates)
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
        checkOpen(); return ops.readRowForUpdate(conn, table, id)
    }

    override val supportsOwnerEdgeSerialization: Boolean
        get() = root.supportsOwnerEdgeSerialization

    override fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? {
        checkOpen(); return ops.serializeOwnerEdgeAndRead(conn, table, id)
    }

    override val supportsInsertIgnore: Boolean
        get() = root.supportsInsertIgnore

    override fun supportsNativeStorage(codec: String): Boolean = root.supportsNativeStorage(codec)

    override fun supportsTypedJson(): Boolean = root.supportsTypedJson()

    override fun supportsAggregates(): Boolean = root.supportsAggregates()

    override val supportsRelationshipSerialization: Boolean
        get() = root.supportsRelationshipSerialization

    override fun serializeRelationship(key: entkt.runtime.mutation.RelationshipLockKey) {
        checkOpen(); ops.serializeRelationship(conn, key)
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
