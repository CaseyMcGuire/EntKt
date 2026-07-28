package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.mutation.UpdateConsistency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * End-to-end coverage for [UpdateConsistency.Pessimistic] (transaction locking
 *). The two preflights — transaction required, driver
 * capability required — fire before the owner-row load, hooks,
 * privacy, validation, or driver writes; the actual lock-and-read
 * lands the owner row through `Driver.readRowForUpdate(...)` rather
 * than the `ReadCurrent` `byId(...)` path.
 *
 * [PostgresDriver] reports `supportsReadRowForUpdate = true` natively
 * (SELECT ... FOR UPDATE under a transaction), so both the happy-path
 * Pessimistic-inside-tx branches and the negative-capability branch
 * (via the [NoLockSupportDriver] wrapper) can be exercised against
 * the production driver.
 */
class UpdateConsistencyIntegrationTest : PostgresTestBase() {

    /** PostgresDriver natively reports `supportsReadRowForUpdate = true`. */
    private fun freshDriver(): PostgresDriver = resetAndDriver()

    /**
     * Same as [freshDriver] — kept as a named alias so test bodies
     * make their intent ("this branch needs row-lock support") clear.
     */
    private fun lockingDriver(): entkt.runtime.driver.Driver = resetAndDriver()

    @Test
    fun `default consistency is ReadCurrent and update succeeds outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver)
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
        val client = sysClient(driver)
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
        // PostgresDriver natively advertises `supportsReadRowForUpdate
        // = true`, so the capability gate accepts the save and the
        // SELECT ... FOR UPDATE actually runs against the row.
        val client = sysClient(lockingDriver())
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
        val client = sysClient(lockingDriver())

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
        val client = sysClient(driver) {
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
        // PostgresDriver's native lock support lets the inside-tx
        // case actually run the SELECT ... FOR UPDATE.
        val client = sysClient(lockingDriver()) {
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
        // Wrap a real PostgresDriver with a thin proxy that reports
        // `supportsReadRowForUpdate = false` so the capability preflight
        // fires. Other driver methods delegate to the wrapped instance.
        val real = freshDriver()
        val noLockDriver = NoLockSupportDriver(real)
        val client = sysClient(noLockDriver)
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
 * A thin wrapper that delegates everything to a real [entkt.runtime.driver.Driver]
 * but advertises `supportsReadRowForUpdate = false`. Lets the
 * capability-rejection branch of the Pessimistic preflight be
 * exercised without needing a second real driver implementation.
 */
private class NoLockSupportDriver(private val real: entkt.runtime.driver.Driver) : entkt.runtime.driver.Driver {
    override fun register(schema: entkt.runtime.driver.EntitySchema) = real.register(schema)

    // Forwarded, not re-derived: turning the batch back into a loop
    // would drop the create-all-tables-before-any-constraint guarantee
    // the wrapped driver relies on.
    override fun registerAll(schemas: List<entkt.runtime.driver.EntitySchema>) = real.registerAll(schemas)
    override fun insert(table: String, values: Map<String, Any?>) = real.insert(table, values)
    override fun update(table: String, id: Any, values: Map<String, Any?>) = real.update(table, id, values)
    override fun byId(table: String, id: Any) = real.byId(table, id)
    override fun query(
        table: String,
        predicates: List<entkt.query.Predicate<*>>,
        orderBy: List<entkt.query.OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ) = real.query(table, predicates, orderBy, limit, offset)
    override fun count(table: String, predicates: List<entkt.query.Predicate<*>>) = real.count(table, predicates)
    override fun exists(table: String, predicates: List<entkt.query.Predicate<*>>) = real.exists(table, predicates)
    override fun delete(table: String, id: Any) = real.delete(table, id)
    override fun insertMany(table: String, values: List<Map<String, Any?>>) = real.insertMany(table, values)
    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<entkt.query.Predicate<*>>) =
        real.updateMany(table, values, predicates)
    override fun deleteMany(table: String, predicates: List<entkt.query.Predicate<*>>) = real.deleteMany(table, predicates)
    override fun <T> withTransaction(block: (entkt.runtime.driver.Driver) -> T): T =
        real.withTransaction { txReal ->
            // Wrap the tx driver too, so the in-tx Pessimistic preflight
            // sees the same false capability flag.
            block(NoLockSupportTxDriver(txReal))
        }

    override val supportsReadRowForUpdate: Boolean get() = false
    override val supportsOwnerEdgeSerialization: Boolean get() = real.supportsOwnerEdgeSerialization
}

private class NoLockSupportTxDriver(private val txReal: entkt.runtime.driver.Driver) : entkt.runtime.driver.Driver {
    override fun register(schema: entkt.runtime.driver.EntitySchema) = txReal.register(schema)

    override fun registerAll(schemas: List<entkt.runtime.driver.EntitySchema>) = txReal.registerAll(schemas)
    override fun insert(table: String, values: Map<String, Any?>) = txReal.insert(table, values)
    override fun update(table: String, id: Any, values: Map<String, Any?>) = txReal.update(table, id, values)
    override fun byId(table: String, id: Any) = txReal.byId(table, id)
    override fun query(
        table: String,
        predicates: List<entkt.query.Predicate<*>>,
        orderBy: List<entkt.query.OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ) = txReal.query(table, predicates, orderBy, limit, offset)
    override fun count(table: String, predicates: List<entkt.query.Predicate<*>>) = txReal.count(table, predicates)
    override fun exists(table: String, predicates: List<entkt.query.Predicate<*>>) = txReal.exists(table, predicates)
    override fun delete(table: String, id: Any) = txReal.delete(table, id)
    override fun insertMany(table: String, values: List<Map<String, Any?>>) = txReal.insertMany(table, values)
    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<entkt.query.Predicate<*>>) =
        txReal.updateMany(table, values, predicates)
    override fun deleteMany(table: String, predicates: List<entkt.query.Predicate<*>>) = txReal.deleteMany(table, predicates)
    override fun <T> withTransaction(block: (entkt.runtime.driver.Driver) -> T): T = block(this)

    override val inTransaction: Boolean get() = true
    override val supportsReadRowForUpdate: Boolean get() = false
    override val supportsOwnerEdgeSerialization: Boolean get() = txReal.supportsOwnerEdgeSerialization
}
