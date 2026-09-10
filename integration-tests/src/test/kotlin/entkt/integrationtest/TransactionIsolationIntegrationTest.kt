package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.IsolationLevel
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransactionIsolationIntegrationTest : PostgresTestBase() {
    private val viewerContext = testBypassContext("transaction isolation test")

    @Test
    fun `generated clients forward every isolation and compose writes with locking reads`() {
        val driver = IsolationRecordingDriver(resetAndDriver())
        val client = EntClient(driver)

        client.withTransaction { }.getOrThrow()
        client.withTransaction(isolation = null) { }.getOrThrow()
        for (isolation in IsolationLevel.entries) {
            val user = client.withTransaction(isolation = isolation) { tx ->
                val created = tx.users.create {
                    name = isolation.name
                    email = "${isolation.name}@example.com"
                }.saveAndLoad(viewerContext).orRollback()
                val locked = tx.users.query {
                    where(User.id.eq(created.id))
                }.forUpdate().firstOrNull(viewerContext).orRollback()
                assertEquals(created, locked)
                created
            }.getOrThrow()
            assertEquals(isolation.name, user.name)
        }

        assertEquals(listOf(null, null) + IsolationLevel.entries, driver.requests)
        assertEquals(3, client.users.query().all(viewerContext).getOrThrow().size)
    }

    @Test
    fun `unsupported isolation fails before transaction work and releases the client execution guard`() {
        val probe = RecordingDriver(resetAndDriver())
        val driver = IsolationRecordingDriver(probe, supported = emptySet())
        val client = EntClient(driver)
        probe.calls.clear()

        val result = client.withTransaction(isolation = IsolationLevel.Serializable) {
            error("application block must not run")
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        assertIs<UnsupportedDriverCapabilityException>(failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertTrue(probe.calls.isEmpty(), "unsupported isolation must fail before driver work")

        assertEquals(42, client.withTransaction { 42 }.getOrThrow())
        assertEquals(listOf(IsolationLevel.Serializable, null), driver.requests)
    }

    @Test
    fun `explicit isolation retains rollback and exception identity through the generated boundary`() {
        val client = EntClient(resetAndDriver())
        for (isolation in IsolationLevel.entries) {
            val failure = IllegalStateException("reject $isolation")
            val result = client.withTransaction(isolation = isolation) { tx ->
                tx.users.create {
                    name = isolation.name
                    email = "${isolation.name}@example.com"
                }.save(viewerContext).orRollback()
                throw failure
            }
            val failed = assertIs<TransactionResult.Failed>(result)
            assertSame(failure, failed.exception)
            assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        }
        assertTrue(client.users.query().all(viewerContext).getOrThrow().isEmpty())
    }

    private class IsolationRecordingDriver(
        private val delegate: DatabaseDriver,
        private val supported: Set<IsolationLevel> = IsolationLevel.entries.toSet(),
    ) : DatabaseDriver by delegate {
        val requests = mutableListOf<IsolationLevel?>()

        override fun <T> withTransaction(
            isolation: IsolationLevel?,
            block: (DatabaseDriver) -> T,
        ): DriverTransactionResult<T> {
            requests += isolation
            if (isolation != null && isolation !in supported) {
                throw UnsupportedDriverCapabilityException("Test driver does not support $isolation")
            }
            return delegate.withTransaction(isolation = isolation, block = block)
        }
    }
}
