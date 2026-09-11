package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.postgres.PostgresDriver
import entkt.runtime.driver.IsolationLevel
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntConflictFailure
import entkt.runtime.result.EntDatabaseConflictException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntTransactionOutcomeUnknownException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.visibleOrNull
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real database conflicts propagated through generated reads, mutations, and transaction clients. */
class TransactionConflictIntegrationTest : PostgresTestBase() {
    private val viewer = testBypassContext("transaction conflict integration test")

    @Test
    fun `locking read serialization failure preserves its type through projections and orRollback`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording)
        val selected = user(client, "Before")
        val other = EntClient(newDriver())
        var readFailure: EntDatabaseConflictException? = null
        var attempts = 0
        recording.reset()

        val result = client.withTransaction(isolation = IsolationLevel.RepeatableRead) { tx ->
            attempts++
            assertEquals("Before", tx.users.findById(viewer, selected.id).orRollback()?.name)
            other.users.update(selected.id) { name = "After" }.save(viewer).getOrThrow()

            val read = tx.users.query { where(User.id eq selected.id) }
                .forUpdate().firstOrNull(viewer)
            val conflict = assertIs<EntDatabaseConflictException>(assertIs<ReadResult.Failed>(read).exception)
            readFailure = conflict
            assertIs<EntConflictFailure>(conflict)
            assertEquals("40001", conflict.code)
            assertEquals("40001", assertIs<PSQLException>(conflict.cause).sqlState)
            val calls = recording.calls.toList()
            assertSame(read, read.visibleOrNull())
            assertSame(conflict, assertFailsWith<EntDatabaseConflictException> { read.getOrThrow() })
            assertEquals(calls, recording.calls, "projections must not issue another read")
            read.orRollback()
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(assertNotNull(readFailure), failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertSame(failed.exception, assertFailsWith<EntDatabaseConflictException> { result.getOrThrow() })
        assertEquals(1, attempts)
        assertEquals(1, recording.callCount("withTransaction"))
        assertEquals("After", client.users.findById(viewer, selected.id).getOrThrow()?.name)
    }

    @Test
    fun `mutation serialization conflicts retain their mutation state and roll back earlier writes`() {
        val client = EntClient(resetAndDriver())
        val selected = user(client, "Before")
        val other = EntClient(newDriver())
        var mutationFailure: EntConflictException? = null
        var attempts = 0

        val result = client.withTransaction(isolation = IsolationLevel.RepeatableRead) { tx ->
            attempts++
            tx.users.findById(viewer, selected.id).orRollback()
            tx.users.create { name = "Temporary"; email = "temporary@example.com" }.save(viewer).orRollback()
            other.users.update(selected.id) { name = "After" }.save(viewer).getOrThrow()

            val mutation = tx.users.update(selected.id) { name = "Stale" }.save(viewer)
            val conflict = assertIs<EntConflictException>(assertIs<MutationResult.Failed>(mutation).exception)
            mutationFailure = conflict
            assertIs<EntConflictFailure>(conflict)
            assertEquals("40001", conflict.code)
            assertEquals("40001", assertIs<PSQLException>(conflict.cause).sqlState)
            assertEquals("User", conflict.entityType)
            assertEquals(EntOperation.UPDATE, conflict.operation)
            assertEquals(MutationWriteState.NotPersisted, conflict.writeState)
            mutation.orRollback()
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(assertNotNull(mutationFailure), failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertEquals(listOf("After"), client.users.query().all(viewer).getOrThrow().map { it.name })
        assertEquals(1, attempts)
    }

    @Test
    fun `serialization failure at commit reaches the generated transaction result as a confirmed conflict`() {
        val client = EntClient(resetAndDriver())
        val first = user(client, "First")
        val second = user(client, "Second")
        val other = EntClient(newDriver())
        var blockCompleted = false
        var attempts = 0

        val result = client.withTransaction(isolation = IsolationLevel.Serializable) { tx ->
            attempts++
            assertEquals(2, tx.users.query().all(viewer).orRollback().size)
            other.withTransaction(isolation = IsolationLevel.Serializable) { concurrent ->
                assertEquals(2, concurrent.users.query().all(viewer).orRollback().size)
                tx.users.update(first.id) { name = "First changed" }.save(viewer).getOrThrow()
                concurrent.users.update(second.id) { name = "Second changed" }.save(viewer).orRollback()
            }.getOrThrow()
            blockCompleted = true
        }

        assertTrue(blockCompleted, "the application block must finish before commit fails")
        val failed = assertIs<TransactionResult.Failed>(result)
        val conflict = assertIs<EntDatabaseConflictException>(failed.exception)
        assertIs<EntConflictFailure>(conflict)
        assertEquals("40001", conflict.code)
        assertNotNull(assertIs<PSQLException>(conflict.cause).serverErrorMessage)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertSame(conflict, assertFailsWith<EntDatabaseConflictException> { result.getOrThrow() })
        assertEquals(listOf("First", "Second changed"), client.users.query { orderBy(User.id.asc()) }
            .all(viewer).getOrThrow().map { it.name })
        assertEquals(1, attempts)
    }

    @Test
    fun `deadlocking locking reads report one typed victim and allow the surviving transaction to commit`() {
        val setup = EntClient(resetAndDriver())
        val first = user(setup, "First")
        val second = user(setup, "Second")
        val clients = listOf(EntClient(timedDriver()), EntClient(timedDriver()))
        val orders = listOf(first.id to second.id, second.id to first.id)
        val ready = CountDownLatch(2)
        val attempts = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)

        try {
            val futures = clients.zip(orders).map { (client, order) ->
                pool.submit<TransactionResult<User?>> {
                    client.withTransaction { tx ->
                        attempts.incrementAndGet()
                        tx.users.query { where(User.id eq order.first) }
                            .forUpdate().firstOrNull(viewer).orRollback()
                        ready.countDown()
                        check(ready.await(5, TimeUnit.SECONDS)) { "both transactions must acquire their first lock" }

                        val read = tx.users.query { where(User.id eq order.second) }
                            .forUpdate().firstOrNull(viewer)
                        if (read is ReadResult.Failed) {
                            val conflict = assertIs<EntDatabaseConflictException>(read.exception)
                            assertEquals("40P01", conflict.code)
                        }
                        read.orRollback()
                    }
                }
            }
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }
            val failed = results.filterIsInstance<TransactionResult.Failed>().single()
            val conflict = assertIs<EntDatabaseConflictException>(failed.exception)
            assertIs<EntConflictFailure>(conflict)
            assertEquals("40P01", assertIs<PSQLException>(conflict.cause).sqlState)
            assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
            assertEquals(1, results.count { it is TransactionResult.Success })
            assertEquals(2, attempts.get())
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `lost commit acknowledgement remains unknown even when the write committed and rollback succeeds`() {
        val observer = EntClient(resetAndDriver())
        val lostAcknowledgement = SQLException("lost commit acknowledgement", "08006")
        var commitCalls = 0
        val source = object : DataSource by dataSource {
            override fun getConnection(): Connection {
                val connection = dataSource.connection
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, method, arguments ->
                    val value = try {
                        method.invoke(connection, *(arguments ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                    if (method.name == "commit") {
                        commitCalls++
                        throw lostAcknowledgement
                    }
                    value
                } as Connection
            }
        }
        val client = EntClient(PostgresDriver(source))
        var attempts = 0

        val result = client.withTransaction { tx ->
            attempts++
            tx.users.create { name = "Committed"; email = "committed@example.com" }.save(viewer).orRollback()
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(lostAcknowledgement, failed.exception)
        assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
        val projected = assertFailsWith<EntTransactionOutcomeUnknownException> { result.getOrThrow() }
        assertSame(lostAcknowledgement, projected.exception)
        assertSame(lostAcknowledgement, projected.cause)
        assertEquals(listOf("Committed"), observer.users.query().all(viewer).getOrThrow().map { it.name })
        assertEquals(1, commitCalls)
        assertEquals(1, attempts)
    }

    @Test
    fun `database-shaped exceptions from LOAD rules are not classified as database conflicts`() {
        val driver = resetAndDriver()
        val selected = user(EntClient(driver), "Before")
        val applicationFailure = PSQLException("application rule failed", PSQLState.SERIALIZATION_FAILURE)
        val client = EntClient(driver) {
            policies {
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run {
                        privacy { load(UserLoadPrivacyRule { _, _ -> throw applicationFailure }) }
                    }
                })
            }
        }

        val result = client.withTransaction { tx ->
            val read = tx.users.query { where(User.id eq selected.id) }
                .forUpdate().firstOrNull(ViewerContext(Viewer.User(selected.id)))
            val failure = assertIs<ReadResult.Failed>(read)
            assertSame(applicationFailure, failure.exception)
            assertFalse(failure.exception is EntConflictFailure)
            read.orRollback()
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(applicationFailure, failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
    }

    private fun user(client: EntClient, name: String): User = client.users.create {
        this.name = name
        email = "$name@example.com"
    }.saveAndLoad(viewer).getOrThrow()

    /** Bound blocking SQL as well as the worker futures, so failed synchronization cannot hang the suite. */
    private fun timedDriver(): PostgresDriver {
        val source = object : DataSource by dataSource {
            override fun getConnection(): Connection = dataSource.connection.also { connection ->
                connection.createStatement().use { it.execute("SET statement_timeout = '10s'") }
            }
        }
        return PostgresDriver(source)
    }
}
