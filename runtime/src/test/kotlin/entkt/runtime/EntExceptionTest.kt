package entkt.runtime
import entkt.runtime.result.toValidationViolation
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.result.EntOverfetchCapExceededException
import entkt.runtime.result.EntDriverException
import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.ValidationViolation
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntNoChangesException
import entkt.runtime.result.EntNotFoundException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntError

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the EntException ↔ EntError mapping and the cause-forwarding
 * contract on EntDriverException. The mapping has to be exhaustive
 * (every EntError variant has a matching exception class) and stable
 * (toException() round-trip preserves the error value).
 */
class EntExceptionTest {

    @Test
    fun `each EntError variant maps to its matching EntException subclass via toException`() {
        val notFound = EntError.NotFound("Post", EntOperation.LOAD, id = 1)
        assertTrue(notFound.toException() is EntNotFoundException)
        assertSame(notFound, (notFound.toException() as EntNotFoundException).notFound)

        val noChanges = EntError.NoChanges("Post", EntOperation.UPDATE, id = 1)
        assertTrue(noChanges.toException() is EntNoChangesException)

        val privacy = EntError.PrivacyDenied("Post", EntOperation.LOAD, reason = "no")
        assertTrue(privacy.toException() is EntPrivacyDeniedException)

        val validation = EntError.ValidationFailed(
            "Post", EntOperation.CREATE,
            violations = listOf(ValidationViolation("title required", field = "title")),
        )
        assertTrue(validation.toException() is EntValidationException)

        val constraint = EntError.ConstraintViolation(
            "Post", EntOperation.CREATE,
            constraint = "post_title_key", field = "title", code = "23505",
            message = "duplicate key",
        )
        assertTrue(constraint.toException() is EntConstraintViolationException)

        val conflict = EntError.Conflict(
            "Post", EntOperation.EDGE_MUTATION,
            code = "relationship_serialization_conflict",
            message = "conflict",
        )
        assertTrue(conflict.toException() is EntConflictException)

        val cause = IllegalStateException("PG down")
        val driver = EntError.DriverFailure("Post", EntOperation.LOAD, cause = cause)
        assertTrue(driver.toException() is EntDriverException)

        val overfetch = EntError.OverfetchCapExceeded("Post", EntOperation.QUERY, cap = 100)
        assertTrue(overfetch.toException() is EntOverfetchCapExceededException)
    }

    @Test
    fun `EntDriverException forwards the underlying cause to the JVM exception chain`() {
        val cause = IllegalStateException("connection reset")
        val ex = EntDriverException(
            EntError.DriverFailure("Post", EntOperation.UPDATE, cause = cause),
        )
        // The cause is preserved on the JVM Throwable chain so
        // standard exception-handling tooling sees it.
        assertSame(cause, ex.cause)
        // And the EntError side keeps it too.
        assertSame(cause, ex.driverFailure.cause)
    }

    @Test
    fun `non-driver EntExceptions have null cause by default`() {
        val ex = EntNotFoundException(EntError.NotFound("Post", EntOperation.LOAD, id = 1))
        assertNull(ex.cause)
    }

    @Test
    fun `EntException message is derived from the EntError's message`() {
        val err = EntError.PrivacyDenied("Post", EntOperation.LOAD, reason = "viewer cannot read")
        val ex = err.toException()
        // Default message is "$operation denied on $entity: $reason"
        assertEquals(err.message, ex.message)
    }

    @Test
    fun `ValidationDecision Invalid maps to ValidationViolation preserving message field code`() {
        val invalid = ValidationDecision.Invalid(
            message = "must be positive",
            field = "age",
            code = "POSITIVE_REQUIRED",
        )
        val v = invalid.toValidationViolation()
        assertEquals("must be positive", v.message)
        assertEquals("age", v.field)
        assertEquals("POSITIVE_REQUIRED", v.code)
    }
}
