package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.mutation.UpdateConsistency
import entkt.runtime.result.EntTargetAbsentException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [UpdateConsistency.Pessimistic]. The two
 * preflights — transaction required, driver capability required — fire
 * before the owner-row load, hooks, privacy, validation, or driver
 * writes; the actual lock-and-read lands the owner row through
 * `DatabaseDriver.readRowForUpdate(...)` rather than the `ReadCurrent`
 * `byId(...)` path.
 *
 * An unsatisfied preflight no longer throws out of the terminal: it is
 * `Failed(EntUnexpectedMutationException(NotPersisted, cause =
 * TransactionRequiredException / UnsupportedDriverCapabilityException))`
 * (deliberate reversal of the old propagate contract).
 *
 * [PostgresDriver] reports `supportsReadRowForUpdate = true` natively
 * (SELECT ... FOR UPDATE under a transaction), so both the happy-path
 * Pessimistic-inside-tx branches and the negative-capability branch
 * (via the [NoLockSupportDriver] wrapper) run against the production
 * driver.
 */
class UpdateConsistencyIntegrationTest : PostgresTestBase() {

    /** PostgresDriver natively reports `supportsReadRowForUpdate = true`. */
    private fun freshDriver(): PostgresDriver = resetAndDriver()

    /**
     * Same as [freshDriver] — kept as a named alias so test bodies
     * make their intent ("this branch needs row-lock support") clear.
     */
    private fun lockingDriver(): DatabaseDriver = resetAndDriver()

    /** Assert the captured-preflight failure shape and return the cause. */
    private inline fun <reified C : Exception> assertPreflightFailure(result: MutationResult<*>): C {
        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        return assertIs<C>(ex.cause)
    }

    @Test
    fun `default consistency is ReadCurrent and update succeeds outside a transaction`() {
        val driver = freshDriver()
        val client = EntClient(driver)
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()

        val updated = client.users.update(user.id) {
            name = "Renamed"
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals("Renamed", updated.name)
    }

    @Test
    fun `Pessimistic update outside a transaction fails with TransactionRequiredException as cause`() {
        val driver = freshDriver()
        val client = EntClient(driver)
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()

        val cause = assertPreflightFailure<TransactionRequiredException>(
            client.users.update(user.id, consistency = UpdateConsistency.Pessimistic) {
                name = "Renamed"
            }.save(testViewerContext),
        )
        assertTrue(cause.message!!.contains("Pessimistic"))
    }

    @Test
    fun `Pessimistic update inside a transaction succeeds`() {
        // PostgresDriver natively advertises `supportsReadRowForUpdate
        // = true`, so the capability gate accepts the save and the
        // SELECT ... FOR UPDATE actually runs against the row.
        val client = EntClient(lockingDriver())
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()

        val result = client.withTransaction { tx ->
            tx.users.update(user.id, consistency = UpdateConsistency.Pessimistic) {
                name = "Renamed Pessimistic"
            }.saveAndLoad(testViewerContext).orRollback()
        }
        val success = assertIs<TransactionResult.Success<User>>(result)
        assertEquals("Renamed Pessimistic", success.value.name)
    }

    @Test
    fun `Pessimistic update of a missing owner row is Failed(EntTargetAbsentException)`() {
        val client = EntClient(lockingDriver())

        val result = client.withTransaction { tx ->
            tx.users.update(9999L, consistency = UpdateConsistency.Pessimistic) {
                name = "Ghost"
            }.save(testViewerContext)
        }
        // `readRowForUpdate(...)` on a missing id returns null →
        // Failed(EntTargetAbsentException) recorded through the tx
        // client → the scope goes rollback-only and the boundary reports
        // the recorded failure.
        val failed = assertIs<TransactionResult.Failed>(result)
        val absent = assertIs<EntTargetAbsentException>(failed.exception)
        assertEquals("User", absent.entityType)
        assertEquals(9999L, absent.key.value)
    }

    @Test
    fun `Pessimistic preflight fires before before-hooks`() {
        // The transaction-required check runs before any observable
        // work, so a Pessimistic save outside a transaction never
        // reaches the hook stage.
        val driver = freshDriver()
        var hookFired = false
        val client = EntClient(driver) {
            hooks {
                users {
                    beforeUpdate { state -> hookFired = true; state }
                }
            }
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertPreflightFailure<TransactionRequiredException>(
            client.users.update(user.id, consistency = UpdateConsistency.Pessimistic) {
                name = "Renamed"
            }.save(testViewerContext),
        )
        assertFalse(hookFired, "before-hooks must not fire when the Pessimistic preflight rejects the save")
    }

    @Test
    fun `defaultUpdateConsistency on the client is honored when no per-save override is passed`() {
        val client = EntClient(lockingDriver()) {
            defaultUpdateConsistency = UpdateConsistency.Pessimistic
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()

        // Outside a transaction → the no-arg `update(id) { ... }` call
        // inherits Pessimistic and fails the requirement preflight.
        assertPreflightFailure<TransactionRequiredException>(
            client.users.update(user.id) { name = "Renamed" }.save(testViewerContext),
        )

        // Inside a transaction → the inherited default works.
        val result = client.withTransaction { tx ->
            tx.users.update(user.id) { name = "Renamed Pessimistic Default" }
                .saveAndLoad(testViewerContext).orRollback()
        }
        val success = assertIs<TransactionResult.Success<User>>(result)
        assertEquals("Renamed Pessimistic Default", success.value.name)
    }

    @Test
    fun `Pessimistic update on a driver without supportsReadRowForUpdate fails with UnsupportedDriverCapabilityException as cause`() {
        // Wrap a real PostgresDriver with a thin proxy that reports
        // `supportsReadRowForUpdate = false` so the capability preflight
        // fires. Other driver methods delegate to the wrapped instance.
        val real = freshDriver()
        val noLockDriver = NoLockSupportDriver(real)
        val client = EntClient(noLockDriver)
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()

        val txResult = client.withTransaction { tx ->
            tx.users.update(user.id, consistency = UpdateConsistency.Pessimistic) {
                name = "Renamed"
            }.save(testViewerContext)
        }
        // The mutation failure was recorded through the tx client →
        // rollback-only → the boundary reports it.
        val failed = assertIs<TransactionResult.Failed>(txResult)
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        val cause = assertIs<UnsupportedDriverCapabilityException>(ex.cause)
        assertTrue(cause.message!!.contains("supportsReadRowForUpdate"))
    }
}

/**
 * A thin wrapper that delegates everything to a real [DatabaseDriver] but
 * advertises `supportsReadRowForUpdate = false`. Lets the
 * capability-rejection branch of the Pessimistic preflight be
 * exercised without needing a second real driver implementation.
 * `registerAll` is forwarded (not re-derived) so the batch DDL
 * guarantee of the wrapped driver is preserved.
 */
private class NoLockSupportDriver(private val real: DatabaseDriver) : DatabaseDriver by real {
    override val supportsReadRowForUpdate: Boolean get() = false

    override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
        real.withTransaction { txReal ->
            // Wrap the tx driver too, so the in-tx Pessimistic preflight
            // sees the same false capability flag.
            block(NoLockSupportTxDriver(txReal))
        }
}

private class NoLockSupportTxDriver(txReal: DatabaseDriver) : DatabaseDriver by txReal {
    override val supportsReadRowForUpdate: Boolean get() = false
}
