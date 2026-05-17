package entkt.postgres

import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.classifyDriverError
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for PostgresDriver.classifyException. Doesn't need
 * Testcontainers — we synthesize PSQLException values with known
 * SQLSTATE values and verify the classifier maps them correctly.
 */
class PostgresDriverClassifyTest {

    private val driver = PostgresDriver(
        // The classifier doesn't touch the DataSource; pass a dummy.
        dataSource = NullDataSource,
        autoDdl = false,
    )

    @Test
    fun `SQLSTATE 23505 unique violation maps to ConstraintViolation`() {
        val psql = PSQLException("duplicate key value violates unique constraint", PSQLState.UNIQUE_VIOLATION)
        val classified = driver.classifyException(psql, "User", EntOperation.CREATE)
        assertTrue(classified is EntError.ConstraintViolation)
        val cv = classified
        assertEquals("User", cv.entity)
        assertEquals(EntOperation.CREATE, cv.operation)
        assertEquals("23505", cv.code)
        // No server message attached → constraint/field stay null.
        assertNull(cv.constraint)
        assertNull(cv.field)
    }

    @Test
    fun `SQLSTATE 23503 foreign key violation maps to ConstraintViolation`() {
        val psql = PSQLException("FK violation", PSQLState.FOREIGN_KEY_VIOLATION)
        val classified = driver.classifyException(psql, "Post", EntOperation.UPDATE)
        assertTrue(classified is EntError.ConstraintViolation)
        assertEquals("23503", classified.code)
    }

    @Test
    fun `SQLSTATE 23514 check violation maps to ConstraintViolation`() {
        val psql = PSQLException("check failed", PSQLState.CHECK_VIOLATION)
        val classified = driver.classifyException(psql, "Order", EntOperation.CREATE)
        assertTrue(classified is EntError.ConstraintViolation)
        assertEquals("23514", classified.code)
    }

    @Test
    fun `SQLSTATE 23502 not null violation maps to ConstraintViolation`() {
        val psql = PSQLException("null value", PSQLState.NOT_NULL_VIOLATION)
        val classified = driver.classifyException(psql, "X", EntOperation.CREATE)
        assertTrue(classified is EntError.ConstraintViolation)
        assertEquals("23502", classified.code)
    }

    @Test
    fun `SQLSTATE 40001 serialization failure is NOT classified in V1 (falls through to DriverFailure)`() {
        // The natural fit is EntError.Conflict, but V1 has no
        // generated code that surfaces Conflict — keep it as
        // DriverFailure until the optimistic-locking RFC lands a real
        // consumer. Per-driver classifier returns null; the
        // classifyDriverError helper wraps as DriverFailure.
        val psql = PSQLException("could not serialize", PSQLState.SERIALIZATION_FAILURE)
        assertNull(driver.classifyException(psql, "X", EntOperation.UPDATE))
        val classified = classifyDriverError(driver, psql, "X", EntOperation.UPDATE)
        assertTrue(classified is EntError.DriverFailure)
        assertSame(psql, classified.cause)
    }

    @Test
    fun `non-PSQLException returns null from classifier`() {
        val notPsql = RuntimeException("network down")
        assertNull(driver.classifyException(notPsql, "X", EntOperation.LOAD))
        // Per the tightened classifyDriverError contract, non-SQLException
        // throwables are re-thrown as programming bugs rather than
        // wrapped as DriverFailure. The Postgres classifier returns
        // null (it's not a PSQLException), and the runtime helper
        // then re-throws because RuntimeException isn't in the
        // SQLException family.
        try {
            classifyDriverError(driver, notPsql, "X", EntOperation.LOAD)
            kotlin.test.fail("expected re-throw")
        } catch (e: RuntimeException) {
            assertSame(notPsql, e)
        }
    }

    @Test
    fun `SQLException without SQLSTATE 23xxx falls back to DriverFailure`() {
        // The Postgres classifier only matches SQLSTATE 23xxx;
        // anything else (a SocketException wrapped as SQLException,
        // a 08xxx connection error, etc.) returns null from the
        // driver classifier but is still a genuine driver-level
        // failure — so it wraps as DriverFailure rather than
        // re-throwing.
        val sql = java.sql.SQLException("connection lost", "08006")
        val classified = classifyDriverError(driver, sql, "X", EntOperation.LOAD)
        assertTrue(classified is EntError.DriverFailure)
        assertSame(sql, (classified as EntError.DriverFailure).cause)
    }

    // ---------- Test fixtures ----------

    /** A DataSource that throws on every call — the classifier never touches it. */
    private object NullDataSource : javax.sql.DataSource {
        override fun getConnection() = error("test fixture: DataSource not used by classifier")
        override fun getConnection(username: String?, password: String?) = getConnection()
        override fun getLogWriter() = error("not used")
        override fun setLogWriter(out: java.io.PrintWriter?) {}
        override fun setLoginTimeout(seconds: Int) {}
        override fun getLoginTimeout() = 0
        override fun getParentLogger() = error("not used")
        override fun <T : Any?> unwrap(iface: Class<T>?) = error("not used")
        override fun isWrapperFor(iface: Class<*>?) = false
    }
}
