package entkt.runtime

import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntPrivacyFailure
import entkt.runtime.result.EntTargetAbsentException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.SelectedEdgeStep
import entkt.runtime.result.ValidationViolation
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.result.toValidationViolation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the typed-exception hierarchy of the canonical result algebra:
 * direct structured properties (no universal error payload), the
 * hardcoded [MutationWriteState.NotPersisted] invariants on pre-write
 * failure types, non-empty payload invariants, cause preservation,
 * and ordinary stack-trace retention.
 */
class EntExceptionTest {

    // ---- hardcoded NotPersisted on pre-write failure types ----

    @Test
    fun `target absence hardcodes NotPersisted and derives its message from the key`() {
        val ex = EntTargetAbsentException("User", EntityKey("id", 42))
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("User with id=42 not found", ex.message)
    }

    @Test
    fun `validation hardcodes NotPersisted and requires at least one violation`() {
        val ex = EntValidationException(
            entityType = "User",
            operation = EntOperation.CREATE,
            violations = listOf(ValidationViolation("email required", field = "email")),
        )
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("email", ex.violations.single().field)
        assertFailsWith<IllegalArgumentException> {
            EntValidationException("User", EntOperation.CREATE, emptyList())
        }
    }

    @Test
    fun `recognized constraint hardcodes NotPersisted and preserves the driver exception as cause`() {
        val driverException = RuntimeException("duplicate key")
        val ex = EntConstraintViolationException(
            entityType = "User",
            operation = EntOperation.CREATE,
            constraint = "users_email_key",
            field = "email",
            driverCode = "23505",
            message = "unique violation on users_email_key",
            cause = driverException,
        )
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertSame(driverException, ex.cause)
        assertEquals("23505", ex.driverCode)
    }

    @Test
    fun `conflict hardcodes NotPersisted and permits a missing cause`() {
        val ex = EntConflictException(
            entityType = "Order",
            operation = EntOperation.UPDATE,
            code = "40001",
            message = "serialization failure",
        )
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertNull(ex.cause)
    }

    // ---- state-bearing mutation failure types ----

    @Test
    fun `mutation privacy denial carries the denied privacy operation and reported write state`() {
        val preWrite = EntMutationPrivacyDeniedException(
            writeState = MutationWriteState.NotPersisted,
            entityType = "User",
            operation = EntOperation.UPDATE,
            entityKey = EntityKey("id", 7),
            reason = "not the owner",
        )
        assertEquals(EntOperation.UPDATE, preWrite.operation)
        assertEquals("UPDATE denied on User: not the owner", preWrite.message)

        val disclosure = EntMutationPrivacyDeniedException(
            writeState = MutationWriteState.Committed,
            entityType = "User",
            operation = EntOperation.LOAD,
            entityKey = EntityKey("id", 7),
            reason = "viewer cannot load",
        )
        assertEquals(MutationWriteState.Committed, disclosure.writeState)
        assertEquals(EntOperation.LOAD, disclosure.operation)
    }

    @Test
    fun `create privacy denial permits a missing entity key`() {
        val ex = EntMutationPrivacyDeniedException(
            writeState = MutationWriteState.NotPersisted,
            entityType = "User",
            operation = EntOperation.CREATE,
            entityKey = null,
            reason = "anonymous viewers cannot create",
        )
        assertNull(ex.entityKey)
    }

    @Test
    fun `unexpected mutation failure preserves the foreign exception as cause with EntKt's state`() {
        val hookBug = IllegalStateException("hook exploded")
        val ex = EntUnexpectedMutationException(MutationWriteState.TransactionPending, hookBug)
        assertSame(hookBug, ex.cause)
        assertEquals(MutationWriteState.TransactionPending, ex.writeState)
        assertTrue(ex.message!!.contains("TransactionPending"))
    }

    @Test
    fun `every mutation failure kind is an EntMutationException carrying a write state`() {
        val all: List<EntMutationException> = listOf(
            EntTargetAbsentException("User", EntityKey("id", 1)),
            EntValidationException("User", EntOperation.CREATE, listOf(ValidationViolation("m"))),
            EntConflictException("User", EntOperation.UPDATE, null, "conflict"),
            EntUnexpectedMutationException(MutationWriteState.PersistenceUnknown, RuntimeException("x")),
        )
        assertTrue(all.all { it.writeState in MutationWriteState.entries })
    }

    // ---- read-side LOAD denial ----

    @Test
    fun `load denial requires a non-empty denial list and derives its message`() {
        val single = EntPrivacyDeniedException(
            origin = LoadDenialOrigin.Root,
            denials = listOf(PrivacyDenial("User", EntityKey("id", 3), "not visible")),
        )
        assertEquals("LOAD denied for 1 User", single.message)
        assertFailsWith<IllegalArgumentException> {
            EntPrivacyDeniedException(LoadDenialOrigin.Root, emptyList())
        }
    }

    @Test
    fun `read and mutation denials share the privacy failure marker`() {
        val read = EntPrivacyDeniedException(
            origin = LoadDenialOrigin.Root,
            denials = listOf(PrivacyDenial("User", EntityKey("id", 3), "not visible")),
        )
        val mutation = EntMutationPrivacyDeniedException(
            writeState = MutationWriteState.NotPersisted,
            entityType = "User",
            operation = EntOperation.UPDATE,
            entityKey = EntityKey("id", 3),
            reason = "not visible",
        )

        assertIs<EntPrivacyFailure>(read)
        assertIs<EntPrivacyFailure>(mutation)
    }

    @Test
    fun `an unexpected failure is not a privacy failure merely because its cause is one`() {
        val denial = EntPrivacyDeniedException(
            origin = LoadDenialOrigin.Root,
            denials = listOf(PrivacyDenial("User", EntityKey("id", 3), "not visible")),
        )
        val unexpected: EntException =
            EntUnexpectedMutationException(MutationWriteState.NotPersisted, denial)

        assertFalse(unexpected is EntPrivacyFailure)
    }

    @Test
    fun `selected edge path requires a step and carries only schema names`() {
        val step = SelectedEdgeStep("User", "posts", "Post")
        val origin = LoadDenialOrigin.SelectedEdgePath(listOf(step))
        assertEquals("posts", origin.steps.single().edgeName)
        assertFailsWith<IllegalArgumentException> {
            LoadDenialOrigin.SelectedEdgePath(emptyList())
        }
    }

    @Test
    fun `framework exceptions retain ordinary stack traces`() {
        val ex = EntTargetAbsentException("User", EntityKey("id", 1))
        assertTrue(ex.stackTrace.isNotEmpty())
    }

    // ---- validation DSL bridge ----

    @Test
    fun `ValidationDecision Invalid converts to ValidationViolation field for field`() {
        val invalid = ValidationDecision.Invalid("too short", field = "name", code = "min_length")
        val violation = invalid.toValidationViolation()
        assertEquals("too short", violation.message)
        assertEquals("name", violation.field)
        assertEquals("min_length", violation.code)
    }
}
