package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.mutation.RelationshipLockKey
import entkt.runtime.result.TransactionFailureState
import entkt.schema.FieldType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CancellationException
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
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

    private fun driverThrowingOn(vararg failures: Pair<String, Throwable>): Pair<PostgresDriver, Recorder> {
        val recorder = Recorder()
        val failing = object : DataSource by realDataSource {
            override fun getConnection(): Connection {
                val delegate = realDataSource.connection
                leaked += delegate
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                    ThrowableFailingHandler(delegate, failures.toMap(), recorder),
                ) as Connection
            }
        }
        val driver = PostgresDriver(failing, autoDdl = false).also { it.register(LEDGER) }
        return driver to recorder
    }

    private fun driverWithFallbackProbeCloseFailure(
        closeFailure: () -> Throwable = { SQLException("injected fallback probe Statement.close failure") },
    ): Pair<PostgresDriver, Recorder> {
        val recorder = Recorder()
        val failing = object : DataSource by realDataSource {
            override fun getConnection(): Connection {
                val delegate = realDataSource.connection
                leaked += delegate
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                    FallbackProbeCloseFailingHandler(delegate, recorder, closeFailure),
                ) as Connection
            }
        }
        val driver = PostgresDriver(failing, autoDdl = false).also { it.register(LEDGER) }
        return driver to recorder
    }

    // ---------- rollback failures ----------

    @Test
    fun `a failing rollback yields OutcomeUnknown with the rollback failure suppressed`() {
        freshLedger()
        val (driver, _) = driverFailingOn("rollback")

        val result = driver.withTransaction<Unit> { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "doomed"))
            error("business rule violated")
        }

        // The reason for the rollback is what the caller needs; the
        // rollback's own failure rides along as suppressed. And with
        // neither commit nor rollback confirmed, the outcome is unknown.
        val failed = assertIs<DriverTransactionResult.Failed>(result)
        val stored = assertIs<IllegalStateException>(failed.exception)
        assertEquals("business rule violated", stored.message)
        assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
        assertTrue(
            stored.suppressed.any { it.message?.contains("rollback") == true },
            "rollback failure should be suppressed on the stored exception; got ${stored.suppressed.toList()}",
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

        val result = driver.withTransaction<Unit> { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "doomed"))
            error("business rule violated")
        }
        assertIs<DriverTransactionResult.Failed>(result)

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
    fun `cancellation with an unconfirmed rollback reports OutcomeUnknown`() {
        freshLedger()
        val cancellation = CancellationException("cancelled inside transaction block")
        val (driver, recorder) = driverThrowingOn(
            "rollback" to SQLException("injected rollback failure"),
        )

        val result = driver.withTransaction<Unit> { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "unconfirmed"))
            throw cancellation
        }

        val failed = assertIs<DriverTransactionResult.Failed>(result)
        assertSame(cancellation, failed.exception)
        assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
        assertTrue(recorder.calls.contains("rollback"), "cancellation must trigger rollback; saw ${recorder.calls}")
    }

    @Test
    fun `autocommit is restored on the paths where the transaction did resolve`() {
        freshLedger()
        val (commitDriver, commitRecorder) = driverFailingOn()
        val committed = commitDriver.withTransaction { tx -> tx.insert("tx_ledger", mapOf("memo" to "committed")) }
        assertIs<DriverTransactionResult.Success<*>>(committed)
        assertTrue(
            commitRecorder.calls.contains("setAutoCommit(true)"),
            "a committed transaction leaves nothing open, so the restore is safe; saw ${commitRecorder.calls}",
        )

        val (rollbackDriver, rollbackRecorder) = driverFailingOn()
        val rolledBack = rollbackDriver.withTransaction<Unit> { error("business rule violated") }
        val failed = assertIs<DriverTransactionResult.Failed>(rolledBack)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertTrue(
            rollbackRecorder.calls.contains("setAutoCommit(true)"),
            "a successful rollback also leaves nothing open; saw ${rollbackRecorder.calls}",
        )
    }

    // ---------- commit failures ----------

    @Test
    fun `a failing commit reports OutcomeUnknown even though the hygiene rollback succeeds`() {
        freshLedger()
        val (driver, recorder) = driverFailingOn("commit")

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "unconfirmed"))
            "value"
        }

        // A failed COMMIT may already have reached the server, so a later
        // apparently-successful rollback must never downgrade the outcome
        // to NotCommitted.
        val failed = assertIs<DriverTransactionResult.Failed>(result)
        assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
        assertTrue(
            failed.exception.message?.contains("commit") == true,
            "the commit failure is what the caller must see; got: ${failed.exception.message}",
        )
        assertTrue(
            recorder.calls.contains("rollback"),
            "the connection-hygiene rollback still runs after a failed commit; saw ${recorder.calls}",
        )
    }

    @Test
    fun `cleanup failures after a failed commit are suppressed onto the stored exception`() {
        freshLedger()
        val (driver, _) = driverFailingOn("commit", "setAutoCommit(true)", "close")

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "unconfirmed"))
            "value"
        }

        val failed = assertIs<DriverTransactionResult.Failed>(result)
        assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
        val suppressed = failed.exception.suppressed.mapNotNull { it.message }
        assertTrue(
            suppressed.any { "setAutoCommit(true)" in it },
            "the autocommit-restore failure rides along as suppressed; got $suppressed",
        )
        assertTrue(
            suppressed.any { "close" in it },
            "the close failure rides along as suppressed; got $suppressed",
        )
    }

    @Test
    fun `cancellation thrown by commit reports OutcomeUnknown`() {
        freshLedger()
        val cancellation = CancellationException("commit was cancelled")
        val (driver, recorder) = driverThrowingOn("commit" to cancellation)

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "unconfirmed"))
            "value"
        }

        val failed = assertIs<DriverTransactionResult.Failed>(result)
        assertSame(cancellation, failed.exception)
        assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
        assertTrue(recorder.calls.contains("rollback"), "failed commit must trigger hygiene rollback; saw ${recorder.calls}")
    }

    @Test
    fun `a JVM Error thrown by commit is rolled back and rethrown, never stored`() {
        freshLedger()
        // An Error-throwing variant of the usual proxy: SQLException-based
        // injection can't exercise the Throwable arm of the commit
        // handler, whose contract is roll back + rethrow for JVM errors.
        class CommitError : AssertionError("injected commit error")
        val recorder = Recorder()
        val failing = object : DataSource by realDataSource {
            override fun getConnection(): Connection {
                val delegate = realDataSource.connection
                leaked += delegate
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                    InvocationHandler { _, method, args ->
                        recorder.calls += method.name
                        if (method.name == "commit") throw CommitError()
                        try {
                            method.invoke(delegate, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    },
                ) as Connection
            }
        }
        val driver = PostgresDriver(failing, autoDdl = false).also { it.register(LEDGER) }

        val thrown = assertFailsWith<CommitError> {
            driver.withTransaction { tx ->
                tx.insert("tx_ledger", mapOf("memo" to "doomed"))
                "value"
            }
        }
        // The commit-time Error was preceded by an explicit rollback —
        // the same roll-back-and-rethrow contract as the block path —
        // and nothing was persisted or stored in a result.
        assertTrue("rollback" in recorder.calls, "commit-time Error must trigger rollback; calls=${recorder.calls}")
        assertEquals(emptyList(), memos(), "work must not survive a commit-time Error")
        assertTrue(thrown.suppressed.isEmpty() || thrown.suppressed.none { it is AssertionError })
    }

    @Test
    fun `a failing commit followed by a failing rollback still reports OutcomeUnknown`() {
        freshLedger()
        val (driver, _) = driverFailingOn("commit", "rollback")

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "unconfirmed"))
            "value"
        }

        val failed = assertIs<DriverTransactionResult.Failed>(result)
        assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
        assertTrue(
            failed.exception.message?.contains("commit") == true,
            "the commit failure stays primary; got: ${failed.exception.message}",
        )
        assertTrue(
            failed.exception.suppressed.any { it.message?.contains("rollback") == true },
            "the rollback failure is suppressed on it; got ${failed.exception.suppressed.toList()}",
        )
    }

    // ---------- insertMany drives its own transaction ----------

    @Test
    fun `insertMany unwinds under the same rules as withTransaction`() {
        freshLedger()
        // insertMany opens its own transaction when handed an autocommit
        // connection, so it has its own unwind path. It had the same three
        // bugs independently; both now share the rules in
        // PostgresTransactions.kt rather than restating them.
        val (driver, recorder) = driverFailingOn("rollback")

        // A NOT NULL violation on the second row rolls the batch back.
        val thrown = assertFailsWith<Exception> {
            driver.insertMany(
                "tx_ledger",
                listOf(mapOf("memo" to "first"), mapOf("memo" to null)),
            )
        }

        // The constraint violation is what the caller needs to see, not
        // the rollback's own failure.
        assertTrue(
            thrown.message?.contains("memo") == true || thrown.message?.contains("null") == true,
            "the original failure should survive; got: ${thrown.message}",
        )
        assertFalse(
            recorder.calls.contains("setAutoCommit(true)"),
            "restoring autocommit would commit the half-written batch; saw ${recorder.calls}",
        )
        assertEquals(emptyList(), memos(), "a failed rollback must not leave the batch committed")
    }

    @Test
    fun `insertMany commits and restores autocommit on success`() {
        freshLedger()
        val (driver, recorder) = driverFailingOn()

        driver.insertMany("tx_ledger", listOf(mapOf("memo" to "a"), mapOf("memo" to "b")))

        assertEquals(listOf("a", "b"), memos())
        assertTrue(
            recorder.calls.contains("setAutoCommit(true)"),
            "a resolved transaction restores autocommit; saw ${recorder.calls}",
        )
    }

    // ---------- connection release on non-transactional writes ----------
    //
    // Every write outside an explicit transaction runs in autocommit, so
    // it is durable the moment the statement returns. Releasing the
    // connection afterwards must not be able to report failure for it:
    // `Closeable.use` suppresses a close failure only when the block
    // threw, and calls close() bare on the success path.

    @Test
    fun `a failing close does not fail a committed insertMany`() {
        freshLedger()
        val (driver, _) = driverFailingOn("close")

        val rows = driver.insertMany("tx_ledger", listOf(mapOf("memo" to "a"), mapOf("memo" to "b")))

        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), memos())
    }

    @Test
    fun `a failing close does not fail a committed single-statement write`() {
        freshLedger()
        val (driver, _) = driverFailingOn("close")

        // Same hazard without a multi-row batch: one autocommit INSERT is
        // already durable when close() fails.
        driver.insert("tx_ledger", mapOf("memo" to "solo"))

        assertEquals(listOf("solo"), memos())
    }

    @Test
    fun `a failing close does not fail an update or delete`() {
        freshLedger()
        val (driver, _) = driverFailingOn("close")
        val row = driver.insert("tx_ledger", mapOf("memo" to "before"))
        val id = row["id"]!!

        driver.update("tx_ledger", id, mapOf("memo" to "after"))
        assertEquals(listOf("after"), memos())

        driver.delete("tx_ledger", id)
        assertEquals(emptyList(), memos())
    }

    @Test
    fun `a failing close does not fail a read`() {
        freshLedger()
        realDataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("INSERT INTO tx_ledger (memo) VALUES ('r')") }
        }
        val (driver, _) = driverFailingOn("close")

        // Reads are retry-safe, so this is a spurious failure rather than
        // a correctness hazard — but the rule is uniform: releasing the
        // connection never changes what the caller observes.
        assertEquals(1, driver.query("tx_ledger", emptyList(), emptyList(), null, null).size)
        assertEquals(1L, driver.count("tx_ledger", emptyList()))
    }

    // ---------- cleanup after a SUCCESSFUL commit ----------

    @Test
    fun `a failing autoCommit restore does not demote a committed transaction`() {
        freshLedger()
        val (driver, _) = driverFailingOn("setAutoCommit(true)")

        // Reporting failure here would invite a retry of work that is
        // already durable.
        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertEquals(listOf("committed"), memos())
    }

    @Test
    fun `a failing close does not demote a committed transaction`() {
        freshLedger()
        val (driver, _) = driverFailingOn("close")

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertEquals(listOf("committed"), memos())
    }

    @Test
    fun `every cleanup step failing after a confirmed commit still returns Success`() {
        freshLedger()
        val (driver, _) = driverFailingOn("setAutoCommit(true)", "close")

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertEquals(listOf("committed"), memos())
    }

    @Test
    fun `a post-commit JVM Error restoring autoCommit neither demotes success nor skips close`() {
        freshLedger()
        class RestoreError : AssertionError("injected restore error")
        val (driver, recorder) = driverThrowingOn("setAutoCommit(true)" to RestoreError())

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertTrue(recorder.calls.contains("close"), "restore failure must not skip close; saw ${recorder.calls}")
        assertEquals(listOf("committed"), memos())
    }

    @Test
    fun `a post-commit cancellation from close does not demote success`() {
        freshLedger()
        val cancellation = CancellationException("injected close cancellation")
        val (driver, _) = driverThrowingOn("close" to cancellation)

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertEquals(listOf("committed"), memos())
    }

    // ---------- aborted-state inspection ----------

    @Test
    fun `a runtime inspection failure with confirmed rollback reports NotCommitted`() {
        freshLedger()
        val inspectionFailure = IllegalStateException("pool failed while unwrapping the connection")
        val (driver, recorder) = driverThrowingOn("unwrap" to inspectionFailure)

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "rolled back"))
            "value"
        }

        val failed = assertIs<DriverTransactionResult.Failed>(result)
        assertSame(inspectionFailure, failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertTrue(recorder.calls.contains("rollback"), "inspection failure must trigger rollback; saw ${recorder.calls}")
        assertEquals(emptyList(), memos())
    }

    @Test
    fun `a runtime inspection failure with unconfirmed rollback reports OutcomeUnknown`() {
        freshLedger()
        val inspectionFailure = IllegalStateException("pool failed while unwrapping the connection")
        val (driver, recorder) = driverThrowingOn(
            "unwrap" to inspectionFailure,
            "rollback" to SQLException("injected rollback failure"),
            "close" to SQLException("injected close failure"),
        )

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "unconfirmed"))
            "value"
        }

        val failed = assertIs<DriverTransactionResult.Failed>(result)
        assertSame(inspectionFailure, failed.exception)
        assertEquals(TransactionFailureState.OutcomeUnknown, failed.transactionState)
        assertTrue(recorder.calls.contains("rollback"), "inspection failure must trigger rollback; saw ${recorder.calls}")
        assertTrue(recorder.calls.contains("close"), "connection must still be released; saw ${recorder.calls}")
    }

    @Test
    fun `a fallback probe close failure does not roll back a healthy transaction`() {
        freshLedger()
        val (driver, recorder) = driverWithFallbackProbeCloseFailure()

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertTrue(recorder.calls.contains("commit"), "healthy transaction must commit; saw ${recorder.calls}")
        assertFalse(recorder.calls.contains("rollback"), "probe cleanup must not trigger rollback; saw ${recorder.calls}")
        assertEquals(listOf("committed"), memos())
    }

    @Test
    fun `fallback probe close cancellation does not roll back a healthy transaction`() {
        freshLedger()
        val (driver, recorder) = driverWithFallbackProbeCloseFailure {
            CancellationException("fallback probe close was cancelled")
        }

        val result = driver.withTransaction { tx ->
            tx.insert("tx_ledger", mapOf("memo" to "committed"))
            "ok"
        }

        assertEquals(DriverTransactionResult.Success("ok"), result)
        assertTrue(recorder.calls.contains("commit"), "healthy transaction must commit; saw ${recorder.calls}")
        assertFalse(recorder.calls.contains("rollback"), "probe cleanup must not trigger rollback; saw ${recorder.calls}")
        assertEquals(listOf("committed"), memos())
    }

    // ---------- cleanup while an exception is already propagating ----------

    @Test
    fun `a failing close does not replace a stored block exception`() {
        freshLedger()
        val (driver, _) = driverFailingOn("close")

        val result = driver.withTransaction<Unit> { error("business rule violated") }

        val failed = assertIs<DriverTransactionResult.Failed>(result)
        val stored = assertIs<IllegalStateException>(failed.exception)
        assertEquals("business rule violated", stored.message)
        // The rollback itself was confirmed; only the close failed.
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertTrue(
            stored.suppressed.any { it.message?.contains("close") == true },
            "close failure should be suppressed on the stored exception; got ${stored.suppressed.toList()}",
        )
        assertEquals(emptyList(), memos())
    }

    // ---------- statement / result-set release ----------
    //
    // The same hazard one layer down from the connection. `use` closes a
    // Statement or ResultSet bare on the success path, so a close failure
    // after an autocommit write has already executed turns durable work
    // into a thrown exception.

    /**
     * A driver whose `Statement`s (and their `ResultSet`s) fail to close.
     * Everything else — including the connection — behaves normally, so
     * only the inner release is under test.
     */
    private fun driverWithUnclosableStatements(
        closeFailure: (String) -> Throwable = { target -> SQLException("injected failure: $target") },
    ): PostgresDriver {
        val failing = object : DataSource by realDataSource {
            override fun getConnection(): Connection {
                val delegate = realDataSource.connection
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                    StatementCloseFailingHandler(delegate, closeFailure),
                ) as Connection
            }
        }
        return PostgresDriver(failing, autoDdl = false).also { it.register(LEDGER) }
    }

    @Test
    fun `a failing statement close does not fail a committed write`() {
        freshLedger()
        val driver = driverWithUnclosableStatements()

        driver.insert("tx_ledger", mapOf("memo" to "durable"))

        assertEquals(listOf("durable"), memos(), "the INSERT ran; the close failure must not mask that")
    }

    @Test
    fun `a failing statement close does not fail insertMany or a read`() {
        freshLedger()
        val driver = driverWithUnclosableStatements()

        driver.insertMany("tx_ledger", listOf(mapOf("memo" to "a"), mapOf("memo" to "b")))
        assertEquals(listOf("a", "b"), memos())

        assertEquals(2, driver.query("tx_ledger", emptyList(), emptyList(), null, null).size)
    }

    @Test
    fun `a JVM Error closing a statement does not fail a durable autocommit write`() {
        freshLedger()
        class StatementCleanupError(target: String) : AssertionError("injected failure: $target")
        val driver = driverWithUnclosableStatements { StatementCleanupError(it) }

        driver.insert("tx_ledger", mapOf("memo" to "durable"))

        assertEquals(listOf("durable"), memos())
    }

    @Test
    fun `a cleanup Error does not replace the primary statement failure`() {
        class StatementCleanupError : AssertionError("injected close failure")
        val primary = IllegalStateException("statement execution failed")
        val cleanup = StatementCleanupError()
        val resource = AutoCloseable { throw cleanup }

        val thrown = assertFailsWith<IllegalStateException> {
            resource.useQuietClose { throw primary }
        }

        assertSame(primary, thrown)
        assertTrue(primary.suppressed.any { it === cleanup })
    }

    @Test
    fun `a failing statement close does not fail the advisory-lock methods`() {
        freshLedger()
        val driver = driverWithUnclosableStatements()

        // Both lock primitives execute a `pg_advisory_xact_lock` query
        // purely for its side effect and discard the result set. Closing
        // that result set is not part of taking the lock, so a close
        // failure must not roll back a transaction whose lock was in fact
        // acquired. They're transaction-scoped, so they only do useful
        // work inside withTransaction.
        val result = driver.withTransaction { tx ->
            val inserted = tx.insert("tx_ledger", mapOf("memo" to "locked"))
            tx.serializeOwnerEdgeAndRead("tx_ledger", inserted["id"]!!)
            tx.serializeRelationship(
                RelationshipLockKey.canonical("tx_ledger", listOf("a_id", "b_id")),
            )
            inserted
        }

        val row = when (result) {
            is DriverTransactionResult.Success -> result.value
            is DriverTransactionResult.Failed ->
                throw AssertionError("expected Success, got $result", result.exception)
        }
        assertTrue(row["id"] != null)
        assertEquals(listOf("locked"), memos(), "the transaction must commit, not roll back")
    }

    // ---------- connection release ----------

    @Test
    fun `a failing autoCommit restore still closes the connection`() {
        freshLedger()
        val (driver, recorder) = driverFailingOn("setAutoCommit(true)")

        val result = driver.withTransaction { tx -> tx.insert("tx_ledger", mapOf("memo" to "committed")) }
        assertIs<DriverTransactionResult.Success<*>>(result)

        // Sharing one finally between the restore and the close leaked
        // the connection whenever the restore threw.
        assertTrue(
            recorder.calls.contains("close"),
            "close() must run even when restoring autoCommit fails; saw ${recorder.calls}",
        )
    }

    @Test
    fun `the connection is released when the block fails`() {
        freshLedger()
        val (driver, recorder) = driverFailingOn()

        val result = driver.withTransaction<Unit> { error("business rule violated") }
        assertIs<DriverTransactionResult.Failed>(result)

        assertTrue(recorder.calls.contains("close"), "close() must run on the failure path; saw ${recorder.calls}")
    }

    private class Recorder {
        val calls = mutableListOf<String>()
    }

    /**
     * Wraps every `Statement` / `PreparedStatement` a connection hands
     * out — and every `ResultSet` those produce — so that `close()`
     * throws while all real work still succeeds.
     */
    private class StatementCloseFailingHandler(
        private val delegate: Connection,
        private val closeFailure: (String) -> Throwable,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val result = try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            return when (result) {
                is java.sql.PreparedStatement -> wrap(result, java.sql.PreparedStatement::class.java)
                is java.sql.Statement -> wrap(result, java.sql.Statement::class.java)
                else -> result
            }
        }

        private fun <T : Any> wrap(target: T, iface: Class<T>): Any =
            Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
                if (method.name == "close") throw closeFailure("${iface.simpleName}.close")
                val inner = try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
                if (inner is java.sql.ResultSet) {
                    Proxy.newProxyInstance(
                        java.sql.ResultSet::class.java.classLoader,
                        arrayOf(java.sql.ResultSet::class.java),
                    ) { _, rsMethod, rsArgs ->
                        if (rsMethod.name == "close") throw closeFailure("ResultSet.close")
                        try {
                            rsMethod.invoke(inner, *(rsArgs ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                } else {
                    inner
                }
            }
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

    private class ThrowableFailingHandler(
        private val delegate: Connection,
        private val failures: Map<String, Throwable>,
        private val recorder: Recorder,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val key = if (method.name == "setAutoCommit") "setAutoCommit(${args?.firstOrNull()})" else method.name
            recorder.calls += key
            failures[key]?.let { throw it }
            return try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private class FallbackProbeCloseFailingHandler(
        private val delegate: Connection,
        private val recorder: Recorder,
        private val closeFailure: () -> Throwable,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val key = if (method.name == "setAutoCommit") "setAutoCommit(${args?.firstOrNull()})" else method.name
            recorder.calls += key
            if (method.name == "unwrap") throw SQLException("force fallback transaction-state probe")
            val result = try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            if (method.name != "createStatement" || result !is java.sql.Statement) return result
            return Proxy.newProxyInstance(
                java.sql.Statement::class.java.classLoader,
                arrayOf(java.sql.Statement::class.java),
            ) { _, statementMethod, statementArgs ->
                if (statementMethod.name == "close") {
                    throw closeFailure()
                }
                try {
                    statementMethod.invoke(result, *(statementArgs ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
    }
}
