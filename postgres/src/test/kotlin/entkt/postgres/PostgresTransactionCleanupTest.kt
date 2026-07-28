package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.schema.FieldType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val LEDGER = EntitySchema(
    table = "tx_ledger",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("memo", FieldType.STRING, nullable = false),
    ),
    edges = emptyMap(),
)

/**
 * `withTransaction` cleanup must never be what the caller observes.
 *
 * The original shape had three ways to lie about the outcome:
 * `conn.rollback()` throwing replaced the exception that caused the
 * rollback; a failure restoring `autoCommit` or closing the connection
 * replaced whatever was propagating; and — worst — either of those after
 * a successful `commit()` reported failure for durably committed work,
 * inviting a retry wrapper to apply it twice. The two cleanup calls also
 * shared one `finally`, so a failed `autoCommit` restore skipped
 * `close()` and leaked the pooled connection.
 *
 * Failures are injected through a [Connection] proxy so each unwind path
 * can be exercised against a real server.
 */
class PostgresTransactionCleanupTest {

    private val realDataSource: DataSource = SharedPostgres.dataSource

    /** Delegates whose `close()` we suppressed, cleaned up after each test. */
    private val leaked = mutableListOf<Connection>()

    @AfterTest
    fun closeLeakedDelegates() {
        leaked.forEach { runCatching { it.close() } }
        leaked.clear()
    }

    private fun freshLedger(): PostgresDriver {
        realDataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP TABLE IF EXISTS tx_ledger") }
        }
        return PostgresDriver(realDataSource, autoDdl = true).also { it.register(LEDGER) }
    }

    private fun memos(): List<String> =
        realDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT memo FROM tx_ledger ORDER BY id").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    /**
     * A driver whose connections fail on the named operations.
     * `setAutoCommit` is keyed by argument so the restore
     * (`setAutoCommit(true)`) can fail while the transaction still
     * starts normally.
     */
    private fun driverFailingOn(vararg operations: String): Pair<PostgresDriver, Recorder> {
        val recorder = Recorder()
        val failing = object : DataSource by realDataSource {
            override fun getConnection(): Connection {
                val delegate = realDataSource.connection
                leaked += delegate
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                    FailingHandler(delegate, operations.toSet(), recorder),
                ) as Connection
            }
        }
        val driver = PostgresDriver(failing, autoDdl = false).also { it.register(LEDGER) }
        return driver to recorder
    }

    // ---------- rollback failures ----------

    @Test
    fun `a failing rollback does not replace the exception that caused it`() {
        freshLedger()
        val (driver, _) = driverFailingOn("rollback")

        val thrown = assertFailsWith<IllegalStateException> {
            driver.withTransaction { tx ->
                tx.insert("tx_ledger", mapOf("memo" to "doomed"))
                error("business rule violated")
            }
        }

        // The reason for the rollback is what the caller needs; the
        // rollback's own failure rides along.
        assertEquals("business rule violated", thrown.message)
        assertTrue(
            thrown.suppressed.any { it.message?.contains("rollback") == true },
            "rollback failure should be suppressed on the original; got ${thrown.suppressed.toList()}",
        )
        // The proxy throws before delegating, so the real connection still
        // has the insert open. Restoring autocommit on a connection with a
        // live transaction COMMITS it (JDBC 4.3 §10.1.1) — which would
        // durably persist work the caller was just told had failed.
        assertEquals(emptyList(), memos(), "a failed rollback must not leave the work committed")
    }

    @Test
    fun `a failing rollback does not restore autocommit`() {
        freshLedger()
        val (driver, recorder) = driverFailingOn("rollback")

        assertFailsWith<IllegalStateException> {
            driver.withTransaction<Unit> { tx ->
                tx.insert("tx_ledger", mapOf("memo" to "doomed"))
                error("business rule violated")
            }
        }

        // The mechanism behind the assertion above: switching to
        // autocommit commits an open transaction, so the only safe move
        // once rollback has failed is to leave it alone and close.
        assertFalse(
            recorder.calls.contains("setAutoCommit(true)"),
            "must not restore autocommit while the transaction is unresolved; saw ${recorder.calls}",
        )
        assertTrue(recorder.calls.contains("close"), "connection must still be released; saw ${recorder.calls}")
    }

    @Test
    fun `autocommit is restored on the paths where the transaction did resolve`() {
        freshLedger()
        val (commitDriver, commitRecorder) = driverFailingOn()
        commitDriver.withTransaction { tx -> tx.insert("tx_ledger", mapOf("memo" to "committed")) }
        assertTrue(
            commitRecorder.calls.contains("setAutoCommit(true)"),
            "a committed transaction leaves nothing open, so the restore is safe; saw ${commitRecorder.calls}",
        )

        val (rollbackDriver, rollbackRecorder) = driverFailingOn()
        assertFailsWith<IllegalStateException> {
            rollbackDriver.withTransaction<Unit> { error("business rule violated") }
        }
        assertTrue(
            rollbackRecorder.calls.contains("setAutoCommit(true)"),
            "a successful rollback also leaves nothing open; saw ${rollbackRecorder.calls}",
        )
    }

    // ---------- cleanup after a SUCCESSFUL commit ----------

    @Test
    fun `a failing autoCommit restore does not fail a committed transaction`() {
        freshLedger()
        val (driver, _) = driverFailingOn("setAutoCommit(true)")

        // Reporting failure here would invite a retry of work that is
        // already durable.
        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("committed"), memos())
    }

    @Test
    fun `a failing close does not fail a committed transaction`() {
        freshLedger()
        val (driver, _) = driverFailingOn("close")

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("committed"), memos())
    }

    // ---------- cleanup while an exception is already propagating ----------

    @Test
    fun `a failing close does not replace a propagating exception`() {
        freshLedger()
        val (driver, _) = driverFailingOn("close")

        val thrown = assertFailsWith<IllegalStateException> {
            driver.withTransaction<Unit> { error("business rule violated") }
        }

        assertEquals("business rule violated", thrown.message)
        assertTrue(
            thrown.suppressed.any { it.message?.contains("close") == true },
            "close failure should be suppressed on the original; got ${thrown.suppressed.toList()}",
        )
        assertEquals(emptyList(), memos())
    }

    // ---------- connection release ----------

    @Test
    fun `a failing autoCommit restore still closes the connection`() {
        freshLedger()
        val (driver, recorder) = driverFailingOn("setAutoCommit(true)")

        driver.withTransaction { tx -> tx.insert("tx_ledger", mapOf("memo" to "committed")) }

        // Sharing one finally between the restore and the close leaked
        // the connection whenever the restore threw.
        assertTrue(
            recorder.calls.contains("close"),
            "close() must run even when restoring autoCommit fails; saw ${recorder.calls}",
        )
    }

    @Test
    fun `the connection is released when the block throws`() {
        freshLedger()
        val (driver, recorder) = driverFailingOn()

        assertFailsWith<IllegalStateException> {
            driver.withTransaction<Unit> { error("business rule violated") }
        }

        assertTrue(recorder.calls.contains("close"), "close() must run on the failure path; saw ${recorder.calls}")
    }

    private class Recorder {
        val calls = mutableListOf<String>()
    }

    private class FailingHandler(
        private val delegate: Connection,
        private val failOn: Set<String>,
        private val recorder: Recorder,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val key = if (method.name == "setAutoCommit") "setAutoCommit(${args?.firstOrNull()})" else method.name
            recorder.calls += key
            if (key in failOn) throw SQLException("injected failure: $key")
            return try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
