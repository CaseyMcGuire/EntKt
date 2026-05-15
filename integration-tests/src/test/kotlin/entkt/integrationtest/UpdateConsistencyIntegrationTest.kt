package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.runtime.InMemoryDriver
import entkt.runtime.TransactionRequiredException
import entkt.runtime.UnsupportedDriverCapabilityException
import entkt.runtime.UpdateConsistency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * End-to-end coverage for [UpdateConsistency.Pessimistic] (RFC #4
 * Phase 3). The two preflights — transaction required, driver
 * capability required — fire before the owner-row load, hooks,
 * privacy, validation, or driver writes; the actual lock-and-read
 * lands the owner row through `Driver.readRowForUpdate(...)` rather
 * than the `ReadCurrent` `byId(...)` path.
 *
 * The InMemoryDriver advertises both row-lock capabilities and
 * delegates them to `byId` — that's enough to exercise the codegen
 * branches end-to-end. Concurrent-correctness tests against true
 * SQL-level locking belong to the Postgres driver suite.
 */
class UpdateConsistencyIntegrationTest {

    private fun freshDriver(): InMemoryDriver = InMemoryDriver()

    @Test
    fun `default consistency is ReadCurrent and update succeeds outside a transaction`() {
        val driver = freshDriver()
        val client = EntClient(driver)
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val updated = client.users.update(user.id) {
            name = "Renamed"
        }.save()

        assertNotNull(updated)
        assertEquals("Renamed", updated.name)
    }

    @Test
    fun `Pessimistic update outside a transaction throws TransactionRequiredException`() {
        val driver = freshDriver()
        val client = EntClient(driver)
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val ex = assertFailsWith<TransactionRequiredException> {
            client.users.update(user.id, consistency = UpdateConsistency.Pessimistic) {
                name = "Renamed"
            }.save()
        }
        assertEquals(true, ex.message!!.contains("Pessimistic"))
    }

    @Test
    fun `Pessimistic update inside a transaction succeeds`() {
        val driver = freshDriver()
        val client = EntClient(driver)
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val updated = client.withTransaction { tx ->
            tx.users.update(user.id, consistency = UpdateConsistency.Pessimistic) {
                name = "Renamed Pessimistic"
            }.save()
        }
        assertNotNull(updated)
        assertEquals("Renamed Pessimistic", updated.name)
    }

    @Test
    fun `Pessimistic update returns null when the owner row is missing`() {
        val driver = freshDriver()
        val client = EntClient(driver)

        val result = client.withTransaction { tx ->
            tx.users.update(9999L, consistency = UpdateConsistency.Pessimistic) {
                name = "Ghost"
            }.save()
        }
        // `readRowForUpdate(...)` on a missing id returns null → save returns null (NotFound).
        assertEquals(null, result)
    }

    @Test
    fun `Pessimistic preflight fires before before-hooks`() {
        // The transaction-required check runs before any observable
        // work, so a Pessimistic save outside a transaction never
        // reaches the hook stage. Guard with a hook that would crash
        // if reached, then assert it doesn't fire.
        val driver = freshDriver()
        var hookFired = false
        val client = EntClient(driver) {
            hooks {
                users {
                    beforeUpdate { hookFired = true }
                }
            }
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        assertFailsWith<TransactionRequiredException> {
            client.users.update(user.id, consistency = UpdateConsistency.Pessimistic) {
                name = "Renamed"
            }.save()
        }
        assertEquals(false, hookFired, "before-hooks must not fire when the Pessimistic preflight rejects the save")
    }

    @Test
    fun `defaultUpdateConsistency on the client is honored when no per-save override is passed`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            defaultUpdateConsistency = UpdateConsistency.Pessimistic
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        // Outside a transaction → defaultUpdateConsistency = Pessimistic
        // means the no-arg `update(id) { ... }` call inherits Pessimistic
        // and rejects with TransactionRequiredException.
        assertFailsWith<TransactionRequiredException> {
            client.users.update(user.id) { name = "Renamed" }.save()
        }

        // Inside a transaction → the inherited default works.
        val updated = client.withTransaction { tx ->
            tx.users.update(user.id) { name = "Renamed Pessimistic Default" }.save()
        }
        assertNotNull(updated)
        assertEquals("Renamed Pessimistic Default", updated.name)
    }

    @Test
    fun `Pessimistic update on a driver without supportsReadRowForUpdate throws UnsupportedDriverCapabilityException`() {
        // Wrap the InMemoryDriver with a thin proxy that reports
        // `supportsReadRowForUpdate = false` so the capability preflight
        // fires. Other driver methods delegate to the wrapped instance.
        val real = freshDriver()
        val noLockDriver = NoLockSupportDriver(real)
        val client = EntClient(noLockDriver)
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val ex = client.withTransaction { tx ->
            assertFailsWith<UnsupportedDriverCapabilityException> {
                tx.users.update(user.id, consistency = UpdateConsistency.Pessimistic) {
                    name = "Renamed"
                }.save()
            }
        }
        assertEquals(true, ex.message!!.contains("supportsReadRowForUpdate"))
    }
}

/**
 * A thin wrapper that delegates everything to a real [InMemoryDriver]
 * but advertises `supportsReadRowForUpdate = false`. Lets the
 * capability-rejection branch of the Pessimistic preflight be
 * exercised without needing a second real driver implementation.
 */
private class NoLockSupportDriver(private val real: InMemoryDriver) : entkt.runtime.Driver {
    override fun register(schema: entkt.runtime.EntitySchema) = real.register(schema)
    override fun insert(table: String, values: Map<String, Any?>) = real.insert(table, values)
    override fun update(table: String, id: Any, values: Map<String, Any?>) = real.update(table, id, values)
    override fun byId(table: String, id: Any) = real.byId(table, id)
    override fun query(
        table: String,
        predicates: List<entkt.query.Predicate>,
        orderBy: List<entkt.query.OrderField>,
        limit: Int?,
        offset: Int?,
    ) = real.query(table, predicates, orderBy, limit, offset)
    override fun count(table: String, predicates: List<entkt.query.Predicate>) = real.count(table, predicates)
    override fun exists(table: String, predicates: List<entkt.query.Predicate>) = real.exists(table, predicates)
    override fun delete(table: String, id: Any) = real.delete(table, id)
    override fun insertMany(table: String, values: List<Map<String, Any?>>) = real.insertMany(table, values)
    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<entkt.query.Predicate>) =
        real.updateMany(table, values, predicates)
    override fun deleteMany(table: String, predicates: List<entkt.query.Predicate>) = real.deleteMany(table, predicates)
    override fun <T> withTransaction(block: (entkt.runtime.Driver) -> T): T =
        real.withTransaction { txReal ->
            // Wrap the tx driver too, so the in-tx Pessimistic preflight
            // sees the same false capability flag.
            block(NoLockSupportTxDriver(txReal))
        }

    override val supportsReadRowForUpdate: Boolean get() = false
    override val supportsOwnerEdgeSerialization: Boolean get() = real.supportsOwnerEdgeSerialization
}

private class NoLockSupportTxDriver(private val txReal: entkt.runtime.Driver) : entkt.runtime.Driver {
    override fun register(schema: entkt.runtime.EntitySchema) = txReal.register(schema)
    override fun insert(table: String, values: Map<String, Any?>) = txReal.insert(table, values)
    override fun update(table: String, id: Any, values: Map<String, Any?>) = txReal.update(table, id, values)
    override fun byId(table: String, id: Any) = txReal.byId(table, id)
    override fun query(
        table: String,
        predicates: List<entkt.query.Predicate>,
        orderBy: List<entkt.query.OrderField>,
        limit: Int?,
        offset: Int?,
    ) = txReal.query(table, predicates, orderBy, limit, offset)
    override fun count(table: String, predicates: List<entkt.query.Predicate>) = txReal.count(table, predicates)
    override fun exists(table: String, predicates: List<entkt.query.Predicate>) = txReal.exists(table, predicates)
    override fun delete(table: String, id: Any) = txReal.delete(table, id)
    override fun insertMany(table: String, values: List<Map<String, Any?>>) = txReal.insertMany(table, values)
    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<entkt.query.Predicate>) =
        txReal.updateMany(table, values, predicates)
    override fun deleteMany(table: String, predicates: List<entkt.query.Predicate>) = txReal.deleteMany(table, predicates)
    override fun <T> withTransaction(block: (entkt.runtime.Driver) -> T): T = block(this)

    override val inTransaction: Boolean get() = true
    override val supportsReadRowForUpdate: Boolean get() = false
    override val supportsOwnerEdgeSerialization: Boolean get() = txReal.supportsOwnerEdgeSerialization
}
