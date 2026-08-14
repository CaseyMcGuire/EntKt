package entkt.postgres

import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationWriteState
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import org.postgresql.util.ServerErrorMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for [PostgresDriver.classifyMutationException]. Doesn't
 * need Testcontainers — we synthesize PSQLException values with known
 * SQLSTATE values and verify the classifier returns a state-bearing
 * typed exception directly (no parallel state field): SQLSTATE 23xxx
 * becomes a typed [EntConstraintViolationException], 40001/40P01
 * becomes a typed [EntConflictException], and everything unrecognized
 * returns null so the generated terminal falls back to its
 * phase-derived state.
 */
class PostgresDriverClassifyTest {

    private val driver = PostgresDriver(
        // The classifier doesn't touch the DataSource; pass a dummy.
        dataSource = NullDataSource,
        autoDdl = false,
    )

    /**
     * A PSQLException carrying real `ServerErrorMessage` metadata, the
     * shape the server produces for constraint errors. Field codes per
     * the PostgreSQL error-response protocol: S severity, C sqlstate,
     * M message, n constraint name, c column name.
     */
    private fun serverError(
        sqlState: String,
        message: String,
        constraint: String? = null,
        column: String? = null,
    ): PSQLException {
        val encoded = buildString {
            append("SERROR").append('\u0000')
            append('C').append(sqlState).append('\u0000')
            append('M').append(message).append('\u0000')
            if (constraint != null) append('n').append(constraint).append('\u0000')
            if (column != null) append('c').append(column).append('\u0000')
        }
        return PSQLException(ServerErrorMessage(encoded))
    }

    // ---------- 23xxx: integrity constraint violations ----------

    @Test
    fun `SQLSTATE 23505 unique violation maps to EntConstraintViolationException`() {
        val psql = PSQLException("duplicate key value violates unique constraint", PSQLState.UNIQUE_VIOLATION)
        val classified = assertNotNull(driver.classifyMutationException(psql, "User", EntOperation.CREATE))

        val cv = assertIs<EntConstraintViolationException>(classified)
        assertEquals("User", cv.entityType)
        assertEquals(EntOperation.CREATE, cv.operation)
        assertEquals("23505", cv.driverCode)
        // No server message attached → constraint/field stay null.
        assertNull(cv.constraint)
        assertNull(cv.field)
        assertSame(psql, cv.cause, "the original driver exception must be preserved as the cause")
        // A recognized constraint rejection is a confirmed statement-level
        // rollback: NotPersisted both on the classification (a write op)
        // and hardcoded on the typed exception itself.
        assertEquals(MutationWriteState.NotPersisted, classified.writeState)
        assertEquals(MutationWriteState.NotPersisted, cv.writeState)
    }

    @Test
    fun `SQLSTATE 23503 foreign key violation maps to EntConstraintViolationException`() {
        val psql = PSQLException("FK violation", PSQLState.FOREIGN_KEY_VIOLATION)
        val classified = assertNotNull(driver.classifyMutationException(psql, "Post", EntOperation.UPDATE))

        val cv = assertIs<EntConstraintViolationException>(classified)
        assertEquals("23503", cv.driverCode)
        assertEquals(EntOperation.UPDATE, cv.operation)
        assertEquals(MutationWriteState.NotPersisted, classified.writeState)
    }

    @Test
    fun `SQLSTATE 23514 check violation maps to EntConstraintViolationException`() {
        val psql = PSQLException("check failed", PSQLState.CHECK_VIOLATION)
        val classified = assertNotNull(driver.classifyMutationException(psql, "Order", EntOperation.CREATE))

        val cv = assertIs<EntConstraintViolationException>(classified)
        assertEquals("23514", cv.driverCode)
    }

    @Test
    fun `SQLSTATE 23502 not null violation maps to EntConstraintViolationException`() {
        val psql = PSQLException("null value", PSQLState.NOT_NULL_VIOLATION)
        val classified = assertNotNull(driver.classifyMutationException(psql, "X", EntOperation.CREATE))

        val cv = assertIs<EntConstraintViolationException>(classified)
        assertEquals("23502", cv.driverCode)
    }

    @Test
    fun `SQLSTATE 23P01 exclusion violation maps to EntConstraintViolationException`() {
        val psql = serverError("23P01", "conflicting key value violates exclusion constraint")
        val classified = assertNotNull(driver.classifyMutationException(psql, "Booking", EntOperation.CREATE))

        val cv = assertIs<EntConstraintViolationException>(classified)
        assertEquals("23P01", cv.driverCode)
    }

    @Test
    fun `server-attached metadata populates constraint and field`() {
        val psql = serverError(
            sqlState = "23505",
            message = "duplicate key value violates unique constraint \"emails_addr_key\"",
            constraint = "emails_addr_key",
            column = "addr",
        )
        val classified = assertNotNull(driver.classifyMutationException(psql, "Email", EntOperation.CREATE))

        val cv = assertIs<EntConstraintViolationException>(classified)
        assertEquals("emails_addr_key", cv.constraint)
        assertEquals("addr", cv.field)
        assertEquals("23505", cv.driverCode)
        assertSame(psql, cv.cause)
    }

    @Test
    fun `classification is mutation-only - the typed exception's own state is the contract`() {
        // There is no parallel state field: the returned exception's
        // hardcoded NotPersisted IS the classification, for any
        // mutation operation.
        val psql = PSQLException("duplicate key", PSQLState.UNIQUE_VIOLATION)
        for (op in listOf(EntOperation.CREATE, EntOperation.UPDATE, EntOperation.DELETE, EntOperation.EDGE_MUTATION)) {
            val cv = assertIs<EntConstraintViolationException>(
                assertNotNull(driver.classifyMutationException(psql, "User", op)),
            )
            assertEquals(MutationWriteState.NotPersisted, cv.writeState)
            assertEquals(op, cv.operation)
        }
    }

    // ---------- 40001 / 40P01: statement-level concurrency conflicts ----------

    @Test
    fun `SQLSTATE 40001 serialization failure maps to EntConflictException`() {
        val psql = PSQLException("could not serialize", PSQLState.SERIALIZATION_FAILURE)
        val classified = assertNotNull(driver.classifyMutationException(psql, "X", EntOperation.UPDATE))

        val conflict = assertIs<EntConflictException>(classified)
        assertEquals("X", conflict.entityType)
        assertEquals(EntOperation.UPDATE, conflict.operation)
        assertEquals("40001", conflict.code)
        assertSame(psql, conflict.cause)
        assertEquals(MutationWriteState.NotPersisted, classified.writeState)
        assertEquals(MutationWriteState.NotPersisted, conflict.writeState)
    }

    @Test
    fun `SQLSTATE 40P01 deadlock detected maps to EntConflictException`() {
        val psql = serverError("40P01", "deadlock detected")
        val classified = assertNotNull(driver.classifyMutationException(psql, "X", EntOperation.DELETE))

        val conflict = assertIs<EntConflictException>(classified)
        assertEquals("40P01", conflict.code)
        assertEquals(MutationWriteState.NotPersisted, classified.writeState)
    }

    @Test
    fun `conflict classification carries NotPersisted on the exception itself`() {
        val psql = PSQLException("could not serialize", PSQLState.SERIALIZATION_FAILURE)
        val conflict = assertIs<EntConflictException>(
            assertNotNull(driver.classifyMutationException(psql, "X", EntOperation.UPDATE)),
        )
        assertEquals(MutationWriteState.NotPersisted, conflict.writeState)
    }

    // ---------- unrecognized exceptions return null ----------

    @Test
    fun `non-PSQLException returns null`() {
        assertNull(driver.classifyMutationException(RuntimeException("network down"), "X", EntOperation.LOAD))
        // Even a plain SQLException with a recognizable-looking SQLSTATE
        // is not a PSQLException, so it is not classified.
        assertNull(driver.classifyMutationException(java.sql.SQLException("connection lost", "08006"), "X", EntOperation.CREATE))
    }

    @Test
    fun `PSQLException with an unrecognized SQLSTATE returns null`() {
        // Connection failures (08xxx) and other unrecognized states get
        // no precise classification — the generated terminal then keeps
        // the original exception with its phase-derived state (never
        // optimistically NotPersisted).
        val psql = PSQLException("connection lost", PSQLState.CONNECTION_FAILURE)
        assertNull(driver.classifyMutationException(psql, "X", EntOperation.UPDATE))
    }

    @Test
    fun `PSQLException without a SQLSTATE returns null`() {
        val psql = PSQLException("no state attached", null as PSQLState?)
        assertNull(driver.classifyMutationException(psql, "X", EntOperation.CREATE))
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
