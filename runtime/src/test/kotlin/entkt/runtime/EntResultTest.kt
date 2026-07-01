package entkt.runtime
import entkt.runtime.result.flatMap
import entkt.runtime.result.map
import entkt.runtime.result.getOrThrow
import entkt.runtime.result.EntOverfetchCapExceededException
import entkt.runtime.result.EntNoChangesException
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.getOrNullForAbsenceOnly
import entkt.runtime.result.EntDriverException
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntNotFoundException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntError
import entkt.runtime.result.EntResult

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntResultTest {

    private fun ok(value: Int = 1): EntResult<Int> = EntResult.Ok(value)
    private fun err(error: EntError = EntError.NotFound("Post", EntOperation.LOAD, id = 1)): EntResult<Int> =
        EntResult.Err(error)

    // ---------- getOrThrow ----------

    @Test
    fun `getOrThrow unwraps Ok value`() {
        assertEquals(42, ok(42).getOrThrow())
    }

    @Test
    fun `getOrThrow on Err NotFound throws EntNotFoundException carrying the error`() {
        val ex = assertFailsWith<EntNotFoundException> { err().getOrThrow() }
        assertEquals("Post", ex.notFound.entity)
        assertEquals(EntOperation.LOAD, ex.notFound.operation)
        assertEquals(1, ex.notFound.id)
    }

    @Test
    fun `getOrThrow on Err PrivacyDenied throws EntPrivacyDeniedException`() {
        val r = err(EntError.PrivacyDenied("Post", EntOperation.LOAD, reason = "no"))
        val ex = assertFailsWith<EntPrivacyDeniedException> { r.getOrThrow() }
        assertEquals("no", ex.privacyDenied.reason)
    }

    @Test
    fun `getOrThrow on Err DriverFailure preserves the underlying cause`() {
        val underlying = IllegalStateException("PG down")
        val r = err(EntError.DriverFailure("Post", EntOperation.LOAD, cause = underlying))
        val ex = assertFailsWith<EntDriverException> { r.getOrThrow() }
        assertSame(underlying, ex.cause, "EntDriverException must forward the driver cause")
    }

    // ---------- getOrNullForAbsenceOnly ----------

    @Test
    fun `getOrNullForAbsenceOnly returns value on Ok`() {
        assertEquals(7, ok(7).getOrNullForAbsenceOnly())
    }

    @Test
    fun `getOrNullForAbsenceOnly returns null only for Err NotFound`() {
        assertNull(err(EntError.NotFound("Post", EntOperation.LOAD)).getOrNullForAbsenceOnly())
    }

    @Test
    fun `getOrNullForAbsenceOnly throws for non-NotFound Err variants`() {
        // PrivacyDenied → throws
        assertFailsWith<EntPrivacyDeniedException> {
            err(EntError.PrivacyDenied("Post", EntOperation.LOAD, reason = "no"))
                .getOrNullForAbsenceOnly()
        }
        // ValidationFailed → throws
        assertFailsWith<EntValidationException> {
            err(EntError.ValidationFailed("Post", EntOperation.CREATE, violations = emptyList()))
                .getOrNullForAbsenceOnly()
        }
        // ConstraintViolation → throws
        assertFailsWith<EntConstraintViolationException> {
            err(
                EntError.ConstraintViolation(
                    "Post", EntOperation.CREATE,
                    constraint = "users_email_key",
                    field = "email",
                    code = "23505",
                    message = "duplicate key",
                ),
            ).getOrNullForAbsenceOnly()
        }
        // NoChanges → throws (NoChanges is NOT expected absence by contract)
        assertFailsWith<EntNoChangesException> {
            err(EntError.NoChanges("Post", EntOperation.UPDATE, id = 1)).getOrNullForAbsenceOnly()
        }
        // OverfetchCapExceeded → throws (procedural failure, not absence)
        assertFailsWith<EntOverfetchCapExceededException> {
            err(EntError.OverfetchCapExceeded("Post", EntOperation.QUERY, cap = 100))
                .getOrNullForAbsenceOnly()
        }
    }

    // ---------- map / flatMap ----------

    @Test
    fun `map transforms Ok value, returns Err unchanged`() {
        val mapped = ok(3).map { it * 10 }
        assertEquals(EntResult.Ok(30), mapped)

        val err = err()
        val mappedErr = err.map { it * 10 }
        assertSame(err, mappedErr, "map on Err must return the same Err instance unchanged")
    }

    @Test
    fun `flatMap chains, short-circuits on Err`() {
        val chained = ok(3).flatMap { EntResult.Ok(it + 1) }
        assertEquals(EntResult.Ok(4), chained)

        val short = ok(3).flatMap { err() }
        assertTrue(short is EntResult.Err)
        assertTrue((short as EntResult.Err).error is EntError.NotFound)

        val startsErr = err()
        val nopChain = startsErr.flatMap { EntResult.Ok(it + 1) }
        assertSame(startsErr, nopChain, "flatMap on Err must return the same Err instance unchanged")
    }
}
