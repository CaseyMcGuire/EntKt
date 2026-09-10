package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.IsolationLevel
import entkt.runtime.result.NestedTransactionUnsupportedException
import entkt.runtime.result.TransactionFailureState
import entkt.schema.FieldType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CancellationException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostgresTransactionIsolationTest {
    private val rows = EntitySchema(
        table = "tx_isolation_rows",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
        ),
        edges = emptyMap(),
    )

    @Test
    fun `each explicit isolation is active in the block and does not change the pooled default`() {
        SharedPostgres.dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            val probe = ConnectionProbe(connection)
            val driver = PostgresDriver(probe.dataSource)

            for ((level, sqlName) in listOf(
                IsolationLevel.ReadCommitted to "read committed",
                IsolationLevel.RepeatableRead to "repeatable read",
                IsolationLevel.Serializable to "serializable",
            )) {
                probe.calls.clear()
                val result = driver.withTransaction(level) {
                    assertEquals(sqlName, currentIsolation(connection))
                    assertEquals("repeatable read", sessionIsolation(connection))
                    assertFalse(connection.autoCommit)
                    42
                }

                assertEquals(DriverTransactionResult.Success(42), result)
                assertEquals(
                    listOf("SET TRANSACTION ISOLATION LEVEL ${sqlName.uppercase()}"),
                    probe.calls.filter { it.startsWith("SET ") },
                )
                assertTrue(connection.autoCommit)
                assertEquals("repeatable read", currentIsolation(connection))
                assertTrue("close" in probe.calls)

                // Borrow the same physical connection again, without a pool reset
                // that could conceal leaked session settings.
                assertEquals(
                    DriverTransactionResult.Success("repeatable read"),
                    driver.withTransaction { currentIsolation(connection) },
                )
            }
        }
    }

    @Test
    fun `omitted or null isolation uses the configured default without an isolation command`() {
        SharedPostgres.dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
            val probe = ConnectionProbe(connection)
            val driver = PostgresDriver(probe.dataSource)

            assertEquals(
                DriverTransactionResult.Success("serializable"),
                driver.withTransaction { currentIsolation(connection) },
            )
            assertEquals(
                DriverTransactionResult.Success("serializable"),
                driver.withTransaction(isolation = null) { currentIsolation(connection) },
            )
            assertFalse(probe.calls.any { it.startsWith("SET ") })
        }
    }

    @Test
    fun `explicit isolation works when connections initially disable autocommit`() {
        SharedPostgres.dataSource.connection.use { connection ->
            connection.autoCommit = false
            val driver = PostgresDriver(ConnectionProbe(connection).dataSource)
            assertEquals(
                DriverTransactionResult.Success("serializable"),
                driver.withTransaction(IsolationLevel.Serializable) { currentIsolation(connection) },
            )
            assertEquals("read committed", currentIsolation(connection))
        }
    }

    @Test
    fun `rollback cancellation and fatal errors clear the transaction isolation`() {
        for (failure in listOf(
            IllegalStateException("application failed"),
            CancellationException("cancelled"),
            AssertionError("fatal"),
        )) {
            SharedPostgres.dataSource.connection.use { connection ->
                connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                val probe = ConnectionProbe(connection)
                val driver = PostgresDriver(probe.dataSource)
                val execute = {
                    driver.withTransaction(IsolationLevel.Serializable) {
                        assertEquals("serializable", currentIsolation(connection))
                        throw failure
                    }
                }
                when (failure) {
                    is CancellationException ->
                        assertSame(failure, assertFailsWith<CancellationException> { execute() })
                    is Error ->
                        assertSame(failure, assertFailsWith<Error> { execute() })
                    else -> {
                        val result = assertIs<DriverTransactionResult.Failed>(execute())
                        assertSame(failure, result.exception)
                        assertEquals(TransactionFailureState.NotCommitted, result.transactionState)
                    }
                }
                assertEquals("repeatable read", currentIsolation(connection))
                assertTrue(connection.autoCommit)
                assertTrue("rollback" in probe.calls)
                assertTrue("close" in probe.calls)
            }
        }
    }

    @Test
    fun `isolation setup failure rolls back before the block and preserves the original exception`() {
        SharedPostgres.dataSource.connection.use { connection ->
            val failure = SQLException("isolation setup failed")
            val probe = ConnectionProbe(connection, setupFailure = failure)
            val result = PostgresDriver(probe.dataSource).withTransaction(IsolationLevel.Serializable) {
                error("application block must not run")
            }

            val failed = assertIs<DriverTransactionResult.Failed>(result)
            assertSame(failure, failed.exception)
            assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
            assertEquals("read committed", currentIsolation(connection))
            assertTrue("rollback" in probe.calls)
            assertTrue("close" in probe.calls)
        }
    }

    @Test
    fun `cancellation during isolation setup propagates after rollback`() {
        SharedPostgres.dataSource.connection.use { connection ->
            val cancellation = CancellationException("setup cancelled")
            val probe = ConnectionProbe(connection, setupFailure = cancellation)
            assertSame(cancellation, assertFailsWith<CancellationException> {
                PostgresDriver(probe.dataSource).withTransaction(IsolationLevel.Serializable) {
                    error("application block must not run")
                }
            })
            assertEquals("read committed", currentIsolation(connection))
            assertTrue("rollback" in probe.calls)
        }
    }

    @Test
    fun `a commit failure retains OutcomeUnknown even when rollback clears isolation`() {
        SharedPostgres.dataSource.connection.use { connection ->
            val failure = SQLException("commit failed")
            val probe = ConnectionProbe(connection, failures = mapOf("commit" to failure))
            var attempts = 0
            val result = PostgresDriver(probe.dataSource).withTransaction(IsolationLevel.Serializable) {
                attempts++
            }
            val failed = assertIs<DriverTransactionResult.Failed>(result)
            assertSame(failure, failed.exception)
            assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
            assertEquals(1, attempts)
            assertEquals("read committed", currentIsolation(connection))
        }
    }

    @Test
    fun `failed setup rollback does not restore autocommit or misreport certainty`() {
        SharedPostgres.dataSource.connection.use { connection ->
            val setupFailure = SQLException("setup failed")
            val rollbackFailure = SQLException("rollback failed")
            val probe = ConnectionProbe(connection, setupFailure, mapOf("rollback" to rollbackFailure))
            val result = PostgresDriver(probe.dataSource).withTransaction(IsolationLevel.Serializable) {
                error("application block must not run")
            }
            val failed = assertIs<DriverTransactionResult.Failed>(result)
            assertSame(setupFailure, failed.exception)
            assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
            assertTrue(rollbackFailure in setupFailure.suppressed)
            assertFalse(connection.autoCommit)
            assertTrue("close" in probe.calls)
            // This probe retains the physical connection to inspect cleanup;
            // do not reuse it while the transaction is unresolved.
            connection.rollback()
        }
    }

    @Test
    fun `nested drivers reject explicit isolation without changing the outer transaction`() {
        SharedPostgres.dataSource.connection.use { connection ->
            val probe = ConnectionProbe(connection)
            val driver = PostgresDriver(probe.dataSource)
            val result = driver.withTransaction(IsolationLevel.RepeatableRead) { tx ->
                val setupCalls = probe.calls.toList()
                assertFailsWith<NestedTransactionUnsupportedException> {
                    tx.withTransaction(IsolationLevel.Serializable) { error("nested block") }
                }
                assertEquals(setupCalls, probe.calls)
                assertEquals("repeatable read", currentIsolation(connection))
            }
            assertIs<DriverTransactionResult.Success<*>>(result)
        }
    }

    @Test
    fun `read committed sees later commits while repeatable read and serializable retain their snapshot`() {
        for (isolation in IsolationLevel.entries) {
            val driver = freshRows()
            val result = driver.withTransaction(isolation) { tx ->
                assertEquals(1L, tx.count(rows.table, emptyList()))
                PostgresDriver(SharedPostgres.dataSource).also { it.register(rows) }
                    .insert(rows.table, mapOf("name" to "concurrent"))
                tx.count(rows.table, emptyList())
            }
            val expected = if (isolation == IsolationLevel.ReadCommitted) 2L else 1L
            assertEquals(DriverTransactionResult.Success(expected), result)
        }
    }

    @Test
    fun `serializable prevents two transactions from both claiming the final slot without retrying`() {
        val first = freshRows()
        val second = PostgresDriver(SharedPostgres.dataSource).also { it.register(rows) }
        var firstAttempts = 0
        var secondAttempts = 0
        val result = first.withTransaction(IsolationLevel.Serializable) { tx ->
            firstAttempts++
            assertEquals(1L, tx.count(rows.table, emptyList()))
            val concurrent = second.withTransaction(IsolationLevel.Serializable) { other ->
                secondAttempts++
                assertEquals(1L, other.count(rows.table, emptyList()))
                other.insert(rows.table, mapOf("name" to "second"))
            }
            assertIs<DriverTransactionResult.Success<*>>(concurrent)
            tx.insert(rows.table, mapOf("name" to "first"))
        }
        val failed = assertIs<DriverTransactionResult.Failed>(result)
        assertEquals("40001", assertIs<SQLException>(failed.exception).sqlState)
        assertEquals(2L, first.count(rows.table, emptyList()))
        assertEquals(1, firstAttempts)
        assertEquals(1, secondAttempts)
    }

    private fun freshRows(): PostgresDriver {
        SharedPostgres.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP TABLE IF EXISTS tx_isolation_rows") }
        }
        return PostgresDriver(SharedPostgres.dataSource, autoDdl = true).also {
            it.register(rows)
            it.insert(rows.table, mapOf("name" to "initial"))
        }
    }

    private fun currentIsolation(connection: Connection): String =
        setting(connection, "SHOW transaction_isolation")

    private fun sessionIsolation(connection: Connection): String =
        setting(connection, "SHOW default_transaction_isolation")

    private fun setting(connection: Connection, sql: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                assertTrue(result.next())
                result.getString(1)
            }
        }

    /** Logical close releases the lease without resetting the physical connection. */
    private class ConnectionProbe(
        private val physical: Connection,
        private val setupFailure: Throwable? = null,
        private val failures: Map<String, Throwable> = emptyMap(),
    ) {
        val calls = mutableListOf<String>()
        private val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader, arrayOf(Connection::class.java),
        ) { _, method, args ->
            calls += method.name
            failures[method.name]?.let { throw it }
            when (method.name) {
                "close" -> null
                "getTransactionIsolation", "setTransactionIsolation" ->
                    error("Transaction-local isolation must not read or alter the JDBC session setting")
                "createStatement" -> statement(invoke(method, physical, args) as Statement)
                else -> invoke(method, physical, args)
            }
        } as Connection

        val dataSource = object : DataSource by SharedPostgres.dataSource {
            override fun getConnection(): Connection = this@ConnectionProbe.connection
        }

        private fun statement(physicalStatement: Statement): Statement = Proxy.newProxyInstance(
            Statement::class.java.classLoader, arrayOf(Statement::class.java),
        ) { _, method, args ->
            val sql = args?.firstOrNull() as? String
            if (method.name == "execute" && sql != null) calls += sql
            val result = invoke(method, physicalStatement, args)
            if (sql?.startsWith("SET TRANSACTION ISOLATION LEVEL ") == true && setupFailure != null) {
                throw setupFailure
            }
            result
        } as Statement

        private fun invoke(method: Method, receiver: Any, args: Array<out Any?>?): Any? = try {
            method.invoke(receiver, *(args ?: emptyArray()))
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
    }
}
