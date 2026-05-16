package entkt.integrationtest.support

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.Driver
import entkt.runtime.EntitySchema
import entkt.runtime.InMemoryDriver

/**
 * Test-only wrapper that delegates everything to an [InMemoryDriver]
 * but advertises both row-lock capabilities so codegen Pessimistic and
 * link-table M2M happy-paths can be exercised end-to-end against the
 * in-memory backend.
 *
 * The forwarded `readRowForUpdate` / `serializeOwnerEdgeAndRead` calls
 * don't hold a lock until transaction end — they just delegate to
 * `byId`. Real concurrency tests against true row-lock semantics belong
 * on the Postgres driver suite, where the lock primitives
 * (`SELECT ... FOR UPDATE`, `pg_advisory_xact_lock`) actually serialize
 * concurrent writers.
 *
 * Shared between RFC #4 (`UpdateConsistencyIntegrationTest`) and RFC #5
 * (`LinkTableM2MIntegrationTest`) test suites.
 */
public class LockSupportInMemoryDriver(private val real: InMemoryDriver) : Driver {
    override fun register(schema: EntitySchema) = real.register(schema)
    override fun insert(table: String, values: Map<String, Any?>) = real.insert(table, values)
    override fun update(table: String, id: Any, values: Map<String, Any?>) = real.update(table, id, values)
    override fun byId(table: String, id: Any) = real.byId(table, id)
    override fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ) = real.query(table, predicates, orderBy, limit, offset)
    override fun count(table: String, predicates: List<Predicate>) = real.count(table, predicates)
    override fun exists(table: String, predicates: List<Predicate>) = real.exists(table, predicates)
    override fun delete(table: String, id: Any) = real.delete(table, id)
    override fun insertMany(table: String, values: List<Map<String, Any?>>) = real.insertMany(table, values)
    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>) =
        real.updateMany(table, values, predicates)
    override fun deleteMany(table: String, predicates: List<Predicate>) = real.deleteMany(table, predicates)
    override fun <T> withTransaction(block: (Driver) -> T): T =
        real.withTransaction { txReal -> block(LockSupportInMemoryTxDriver(txReal)) }

    override val supportsReadRowForUpdate: Boolean get() = true
    override fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? = real.byId(table, id)
    override val supportsOwnerEdgeSerialization: Boolean get() = true
    override fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? = real.byId(table, id)
}

/**
 * Transaction-scoped sibling of [LockSupportInMemoryDriver]. Reports
 * `inTransaction = true` and the same capability flags, so the M2M
 * preflight (RFC #5 Phase 4) accepts it.
 */
public class LockSupportInMemoryTxDriver(private val txReal: Driver) : Driver {
    override fun register(schema: EntitySchema) = txReal.register(schema)
    override fun insert(table: String, values: Map<String, Any?>) = txReal.insert(table, values)
    override fun update(table: String, id: Any, values: Map<String, Any?>) = txReal.update(table, id, values)
    override fun byId(table: String, id: Any) = txReal.byId(table, id)
    override fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ) = txReal.query(table, predicates, orderBy, limit, offset)
    override fun count(table: String, predicates: List<Predicate>) = txReal.count(table, predicates)
    override fun exists(table: String, predicates: List<Predicate>) = txReal.exists(table, predicates)
    override fun delete(table: String, id: Any) = txReal.delete(table, id)
    override fun insertMany(table: String, values: List<Map<String, Any?>>) = txReal.insertMany(table, values)
    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>) =
        txReal.updateMany(table, values, predicates)
    override fun deleteMany(table: String, predicates: List<Predicate>) = txReal.deleteMany(table, predicates)
    override fun <T> withTransaction(block: (Driver) -> T): T = block(this)

    override val inTransaction: Boolean get() = true
    override val supportsReadRowForUpdate: Boolean get() = true
    override fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? = txReal.byId(table, id)
    override val supportsOwnerEdgeSerialization: Boolean get() = true
    override fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? = txReal.byId(table, id)
}
