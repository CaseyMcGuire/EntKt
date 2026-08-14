package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.driver.Driver
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.getOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Write-state boundary coverage with fault injection. The stored
 * [entkt.runtime.result.EntMutationException.writeState] must be
 * EntKt's own truthful assessment at the failing boundary:
 *
 *  - a write-path driver failure with no classification →
 *    `EntUnexpectedMutationException(PersistenceUnknown)` — never an
 *    optimistic NotPersisted
 *  - a recognized constraint → typed exception hardcoding NotPersisted
 *  - a pre-persist callback failure → NotPersisted
 *  - a post-persist callback failure after an autocommit write →
 *    `Committed`, and the row IS present
 *  - the same failure inside a caller-owned transaction →
 *    `TransactionPending` + rollback-only (boundary reports
 *    NotCommitted after confirmed rollback)
 *
 * Driver faults are injected by wrapping the real PostgresDriver
 * (delegation, same pattern as postgres' PostgresTransactionCleanupTest).
 */
class MutationWriteStateBoundaryIntegrationTest : PostgresTestBase() {

    // ---- unclassified driver write failure → PersistenceUnknown ----

    @Test
    fun `an unclassified insert failure reports PersistenceUnknown with the original cause`() {
        val boom = RuntimeException("connection dropped mid-flight")
        val failing = object : Driver by resetAndDriver() {
            override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> =
                throw boom
        }
        val client = sysClient(failing)

        val result = client.users.create { name = "A"; email = "a@example.com" }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        // The injected exception is not a PSQLException, so Postgres
        // classification returns null and the write-phase fallback is
        // the honest PersistenceUnknown — never optimistic NotPersisted.
        assertEquals(MutationWriteState.PersistenceUnknown, ex.writeState)
        assertSame(boom, ex.cause)
    }

    @Test
    fun `an unclassified update failure reports PersistenceUnknown`() {
        val real = resetAndDriver()
        val seedClient = sysClient(real)
        val user = seedClient.users.create { name = "A"; email = "a@example.com" }
            .saveAndLoad().getOrThrow()

        val boom = RuntimeException("socket reset during UPDATE")
        val failing = object : Driver by real {
            override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? =
                throw boom
        }
        val client = sysClient(failing)

        val result = client.users.update(user.id) { name = "Renamed" }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.PersistenceUnknown, ex.writeState)
        assertSame(boom, ex.cause)
    }

    // ---- recognized constraint → NotPersisted ----

    @Test
    fun `a recognized constraint failure reports NotPersisted through the same boundary`() {
        val client = sysClient(resetAndDriver())
        client.users.create { name = "A"; email = "dup@example.com" }.save().getOrThrow()

        val result = client.users.create { name = "B"; email = "dup@example.com" }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
    }

    // ---- pre-persist callback failure → NotPersisted ----

    @Test
    fun `a beforeCreate hook failure reports NotPersisted and writes nothing`() {
        val hookBoom = IllegalStateException("beforeCreate blew up")
        val driver = resetAndDriver()
        val client = sysClient(driver) {
            hooks { users { beforeCreate { throw hookBoom } } }
        }

        val result = client.users.create { name = "A"; email = "a@example.com" }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertSame(hookBoom, ex.cause)
        assertEquals(0L, driver.count("users", emptyList()))
    }

    // ---- post-persist callback failure, autocommit → Committed ----

    @Test
    fun `an afterCreate hook failure after an autocommit write reports Committed and the row is present`() {
        val hookBoom = IllegalStateException("afterCreate blew up")
        val driver = resetAndDriver()
        val client = sysClient(driver) {
            hooks { users { afterCreate { throw hookBoom } } }
        }

        val result = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.Committed, ex.writeState)
        assertSame(hookBoom, ex.cause)
        // The write is durable: the callback failure came after commit.
        assertEquals(1L, driver.count("users", emptyList()))
    }

    @Test
    fun `an afterUpdate hook failure after an autocommit write reports Committed and the new value sticks`() {
        val hookBoom = IllegalStateException("afterUpdate blew up")
        val driver = resetAndDriver()
        val seedClient = sysClient(driver)
        val user = seedClient.users.create { name = "A"; email = "a@example.com" }
            .saveAndLoad().getOrThrow()

        val client = sysClient(driver) {
            hooks { users { afterUpdate { throw hookBoom } } }
        }
        val result = client.users.update(user.id) { name = "Renamed" }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.Committed, ex.writeState)
        assertSame(hookBoom, ex.cause)
        assertEquals("Renamed", seedClient.users.findById(user.id).getOrThrow()?.name)
    }

    // ---- post-persist callback failure, caller-owned tx → TransactionPending ----

    @Test
    fun `the same afterCreate failure inside a caller transaction reports TransactionPending and rolls back`() {
        val hookBoom = IllegalStateException("afterCreate blew up")
        val driver = resetAndDriver()
        val client = sysClient(driver) {
            hooks { users { afterCreate { throw hookBoom } } }
        }

        var inner: MutationResult<User>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.create { name = "A"; email = "a@example.com" }.saveAndLoad()
            // Ignore the failure — the rollback-only backstop still fires.
            "completed"
        }

        val innerFailed = assertIs<MutationResult.Failed>(inner!!)
        val ex = assertIs<EntUnexpectedMutationException>(innerFailed.exception)
        assertEquals(MutationWriteState.TransactionPending, ex.writeState)
        assertSame(hookBoom, ex.cause)

        // Rollback-only: the boundary reports the recorded failure after
        // confirmed rollback, and the row is gone.
        val txFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(ex, txFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, txFailed.transactionState)
        assertEquals(0L, driver.count("users", emptyList()))
    }
}
