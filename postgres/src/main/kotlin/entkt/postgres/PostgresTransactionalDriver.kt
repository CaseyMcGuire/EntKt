@file:OptIn(entkt.query.EntktInternal::class)

package entkt.postgres

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.RelatedRows
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EntitySchema
import entkt.runtime.result.NestedTransactionUnsupportedException
import java.sql.Connection

/**
 * A [DatabaseDriver] that runs all I/O on a single JDBC [Connection] with
 * `autoCommit = false`. Every operation delegates to the shared
 * [PostgresOperations] core with the pinned connection; [register]
 * delegates to [root] so DDL never runs inside user transactions.
 * Nested [withTransaction] is unsupported and throws
 * [NestedTransactionUnsupportedException] before any transaction I/O.
 * The driver is block-scoped — [closed] is set to true when
 * [PostgresDriver.withTransaction]'s block exits and subsequent calls throw.
 */
internal class PostgresTransactionalDriver(
    private val conn: Connection,
    private val root: PostgresDriver,
    private val ops: PostgresOperations,
) : DatabaseDriver {
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

    override fun registeredIdColumn(table: String): String {
        checkOpen()
        return root.registeredIdColumn(table)
    }

    override fun <T> copyJsonValue(table: String, column: String, value: T): T {
        checkOpen()
        return ops.copyJsonValue(table, column, value)
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

    override fun requireBindCapacity(minimumParameters: Long, table: String) =
        requirePostgresBindCapacity(minimumParameters, table)

    override fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> {
        checkOpen(); return ops.query(conn, table, predicates, orderBy, limit, offset)
    }

    override fun directToManyWindowCapability(): DirectToManyWindowCapability {
        checkOpen(); return root.directToManyWindowCapability()
    }

    // Runs on the pinned transaction connection, so the one-statement
    // native read shares the transaction's snapshot.
    override fun queryDirectToMany(query: DirectToManyQuery): RelatedRows {
        checkOpen(); return ops.queryDirectToMany(conn, query)
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

    override fun deleteManyByIds(
        table: String,
        idColumn: String,
        ids: List<Any>,
        predicates: List<Predicate<*>>,
    ): List<Any> {
        checkOpen(); return ops.deleteManyByIds(conn, table, idColumn, ids, predicates)
    }

    override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> {
        // Nested transactions are unsupported: the guard throws before
        // the nested block runs, before any savepoint is created, and
        // before any transaction I/O — the outer transaction is
        // unchanged unless the caller lets this exception escape.
        // Savepoint, reuse, and nested-rejection options belong to a
        // separate transaction-client design.
        throw NestedTransactionUnsupportedException()
    }

    // ---------- transaction locking capability surface ----------

    override val inTransaction: Boolean
        get() {
            // A leaked reference used after the block returned must
            // fail at the posture read — inside the terminal's capture
            // boundary, before any work — so generated pipelines report
            // NotPersisted rather than classifying a doomed later
            // statement as PersistenceUnknown.
            checkOpen()
            return true
        }

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
    override fun classifyMutationException(
        exception: Exception,
        entity: String,
        operation: entkt.runtime.result.EntOperation,
    ): entkt.runtime.result.EntMutationException? =
        root.classifyMutationException(exception, entity, operation)
}

/**
 * Whether [conn]'s transaction is in PostgreSQL's aborted state: a
 * statement failed and the server refuses everything until a rollback.
 * pgjdbc silently turns `COMMIT` into `ROLLBACK` in this state, so a
 * transaction block that swallowed a statement failure and "committed"
 * would report success while persisting nothing — both commit paths
 * check this first. Non-pgjdbc connections (unlikely in this module)
 * report false and keep the legacy behavior.
 */
internal fun transactionAborted(conn: Connection): Boolean = try {
    conn.unwrap(org.postgresql.jdbc.PgConnection::class.java).transactionState ==
        org.postgresql.core.TransactionState.FAILED
} catch (_: java.sql.SQLException) {
    // Wrapped/non-pgjdbc connection: fail CLOSED behind a probe. In
    // PostgreSQL's aborted state every statement fails (SQLSTATE
    // 25P02), so a trivial statement distinguishes the two — and if
    // the probe fails for any other reason the connection cannot be
    // trusted to COMMIT either, so it is treated as aborted rather
    // than letting pgjdbc silently turn COMMIT into ROLLBACK while
    // Success is reported.
    try {
        conn.createStatement().useQuietClose { it.execute("SELECT 1") }
        false
    } catch (_: java.sql.SQLException) {
        true
    }
}

internal const val TRANSACTION_ABORTED_MESSAGE: String =
    "A statement inside this transaction block failed and its error was handled without ending " +
        "the block. The transaction is in PostgreSQL's aborted state: every later statement fails, " +
        "and COMMIT would be silently turned into ROLLBACK while reporting success. Stop at the " +
        "first failed operation — project its result through orRollback(), or let the exception " +
        "propagate — instead of continuing past it inside the transaction block."
