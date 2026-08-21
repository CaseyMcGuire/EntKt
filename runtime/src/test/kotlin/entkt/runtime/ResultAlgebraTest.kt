@file:OptIn(EntktInternal::class)

package entkt.runtime

import entkt.query.EntktInternal
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.Driver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EntitySchema
import entkt.runtime.result.EagerEdgeStep
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntTransactionOutcomeUnknownException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.NestedTransactionUnsupportedException
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionCoordinator
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.runEntTransaction
import entkt.runtime.result.visibleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the canonical result algebra's projections and the runtime
 * transaction boundary: getOrThrow direct-throw and uncertainty semantics,
 * visibleOrNull's root-only mapping table, coordinator rollback-only
 * bookkeeping with suppressed-failure ordering, and runEntTransaction
 * outcome conversion — all without any database.
 */
class ResultAlgebraTest {

    private fun rootDenial() = EntPrivacyDeniedException(
        origin = LoadDenialOrigin.Root,
        denials = listOf(PrivacyDenial("User", EntityKey("id", 1), "not visible")),
    )

    private fun eagerDenial() = EntPrivacyDeniedException(
        origin = LoadDenialOrigin.EagerEdge(listOf(EagerEdgeStep("User", "posts", "Post"))),
        denials = listOf(PrivacyDenial("Post", EntityKey("id", 9), "not visible")),
    )

    // ---- ReadResult projections ----

    @Test
    fun `getOrThrow returns the value preserving declared nullability`() {
        assertEquals("u", ReadResult.Success("u").getOrThrow())
        val absent: ReadResult<String?> = ReadResult.Success(null)
        assertNull(absent.getOrThrow())
    }

    @Test
    fun `getOrThrow throws the stored exception directly`() {
        val denial = rootDenial()
        val failed: ReadResult<String?> = ReadResult.failedForInternalUse(denial)
        val thrown = assertFailsWith<EntPrivacyDeniedException> { failed.getOrThrow() }
        assertSame(denial, thrown)
    }

    @Test
    fun `visibleOrNull maps root denial to authoritative absence`() {
        val failed: ReadResult<String?> = ReadResult.failedForInternalUse(rootDenial())
        assertEquals(ReadResult.Success(null), failed.visibleOrNull())
        assertNull(failed.visibleOrNull().getOrThrow())
    }

    @Test
    fun `visibleOrNull preserves success presence and absence`() {
        val present: ReadResult<String?> = ReadResult.Success("u")
        val absent: ReadResult<String?> = ReadResult.Success(null)
        assertSame(present, present.visibleOrNull())
        assertSame(absent, absent.visibleOrNull())
    }

    @Test
    fun `visibleOrNull propagates eager-edge denial and other failures unchanged`() {
        val eager: ReadResult<String?> = ReadResult.failedForInternalUse(eagerDenial())
        assertSame(eager, eager.visibleOrNull())
        val operational: ReadResult<String?> =
            ReadResult.failedForInternalUse(IllegalStateException("connection lost"))
        assertSame(operational, operational.visibleOrNull())
    }

    // ---- MutationResult projection ----

    @Test
    fun `mutation getOrThrow returns the success value or throws the exact stored exception`() {
        assertEquals(Unit, MutationResult.Success(Unit).getOrThrow())
        val exception = EntUnexpectedMutationException(
            MutationWriteState.PersistenceUnknown,
            RuntimeException("connection dropped during commit"),
        )
        val failed: MutationResult<Unit> = MutationResult.failedForInternalUse(exception)
        val thrown = assertFailsWith<EntUnexpectedMutationException> { failed.getOrThrow() }
        assertSame(exception, thrown)
        assertEquals(MutationWriteState.PersistenceUnknown, thrown.writeState)
    }

    // ---- TransactionResult projection ----

    @Test
    fun `transaction getOrThrow rethrows the exact stored exception after confirmed rollback`() {
        val cause = IllegalStateException("boom")
        val failed: TransactionResult<Int> =
            TransactionResult.failedForInternalUse(cause, TransactionFailureState.NotCommitted)

        val thrown = assertFailsWith<IllegalStateException> { failed.getOrThrow() }
        assertSame(cause, thrown)
    }

    @Test
    fun `transaction getOrThrow uses the dedicated exception only for an unknown outcome`() {
        val cause = rootDenial()
        val cleanup = IllegalStateException("cleanup failed")
        cause.addSuppressed(cleanup)
        val failed: TransactionResult<Int> =
            TransactionResult.failedForInternalUse(cause, TransactionFailureState.OutcomeUnknown)

        val thrown = assertFailsWith<EntTransactionOutcomeUnknownException> { failed.getOrThrow() }
        assertSame(cause, thrown.exception)
        assertSame(cause, thrown.cause)
        assertSame(cleanup, thrown.exception.suppressed.single())
        assertEquals(7, (TransactionResult.Success(7) as TransactionResult<Int>).getOrThrow())
    }

    @Test
    fun `confirmed rollback is authoritative while mutation state remains the operation-time snapshot`() {
        for (state in listOf(MutationWriteState.TransactionPending, MutationWriteState.PersistenceUnknown)) {
            val mutation = EntUnexpectedMutationException(
                state,
                IllegalStateException("mutation failed at $state"),
            )
            val failed: TransactionResult<Unit> =
                TransactionResult.failedForInternalUse(mutation, TransactionFailureState.NotCommitted)

            val thrown = assertFailsWith<EntUnexpectedMutationException> { failed.getOrThrow() }
            assertSame(mutation, thrown)
            assertEquals(state, thrown.writeState)
        }
    }

    // ---- TransactionCoordinator ----

    @Test
    fun `coordinator records failures in encounter order without mutating them`() {
        val coordinator = TransactionCoordinator()
        assertTrue(!coordinator.rollbackOnly)
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        coordinator.recordFailure(first)
        coordinator.recordFailure(first) // identical instance dedups
        coordinator.recordFailure(second)
        assertTrue(coordinator.rollbackOnly)
        // Internal accessors; test sources are a friend compilation.
        assertSame(first, coordinator.firstFailure())
        assertEquals(listOf<Exception>(first, second), coordinator.recordedFailures())
        assertTrue(first.suppressed.isEmpty())
    }

    @Test
    fun `orRollback exit cause is primary with earlier ignored failures flat-suppressed`() {
        val ignored = EntUnexpectedMutationException(
            MutationWriteState.TransactionPending,
            RuntimeException("ignored earlier failure"),
        )
        val exitCause = rootDenial()
        var captured: TransactionCoordinator? = null
        val result = runEntTransaction(FakeDriver(), { _, c -> captured = c }) {
            captured!!.recordFailure(ignored)
            val read: ReadResult<String?> = ReadResult.failedForInternalUse(exitCause)
            read.orRollback()
            error("unreachable")
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        // RFC precedence rule 1: the orRollback exit cause is primary;
        // the earlier distinct recorded failure is suppressed on it —
        // never the inversion.
        assertSame(exitCause, failed.exception)
        assertTrue(failed.exception.suppressed.any { it === ignored })
        assertTrue(ignored.suppressed.isEmpty())
    }

    @Test
    fun `two ignored failures flat-suppress onto a block-thrown primary in encounter order`() {
        val a = EntUnexpectedMutationException(
            MutationWriteState.TransactionPending, RuntimeException("a"),
        )
        val b = EntUnexpectedMutationException(
            MutationWriteState.TransactionPending, RuntimeException("b"),
        )
        val blockException = IllegalStateException("application bug")
        var captured: TransactionCoordinator? = null
        val result = runEntTransaction(FakeDriver(), { _, c -> captured = c }) {
            captured!!.recordFailure(a)
            captured!!.recordFailure(b)
            throw blockException
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(blockException, failed.exception)
        assertEquals(listOf<Throwable>(a, b), failed.exception.suppressed.toList())
        assertTrue(a.suppressed.isEmpty())
    }

    // ---- runEntTransaction boundary ----

    @Test
    fun `success path commits and returns the block value`() {
        val result = runEntTransaction(FakeDriver(), { _, _ -> "client" }) { client ->
            assertEquals("client", client)
            41 + 1
        }
        assertEquals(TransactionResult.Success(42), result)
    }

    @Test
    fun `orRollback on a failed read stops the block and reports NotCommitted`() {
        val denial = rootDenial()
        val result = runEntTransaction(FakeDriver(), { _, _ -> Unit }) {
            val read: ReadResult<String?> = ReadResult.failedForInternalUse(denial)
            read.orRollback()
            error("unreachable")
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(denial, failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
    }

    @Test
    fun `an ignored mutation failure recorded on the coordinator rolls back a normally returning block`() {
        val exception = EntMutationPrivacyDeniedException(
            writeState = MutationWriteState.TransactionPending,
            entityType = "User",
            operation = EntOperation.UPDATE,
            entityKey = EntityKey("id", 5),
            reason = "denied",
        )
        var captured: TransactionCoordinator? = null
        val result = runEntTransaction(FakeDriver(), { _, coordinator ->
            captured = coordinator
            Unit
        }) {
            // Simulate a generated mutation terminal producing Failed
            // through the transaction client and the caller ignoring it.
            captured!!.recordFailure(exception)
            "committed value that must not surface"
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(exception, failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
    }

    @Test
    fun `a block-thrown exception is primary with recorded mutation failures suppressed`() {
        val recorded = EntUnexpectedMutationException(
            MutationWriteState.TransactionPending,
            RuntimeException("ignored failure"),
        )
        val blockException = IllegalStateException("application bug")
        var captured: TransactionCoordinator? = null
        val result = runEntTransaction(FakeDriver(), { _, c -> captured = c }) {
            captured!!.recordFailure(recorded)
            throw blockException
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(blockException, failed.exception)
        assertTrue(failed.exception.suppressed.any { it === recorded })
    }

    @Test
    fun `calling the boundary on a transaction-scoped driver throws before any transaction work`() {
        val driver = FakeDriver(inTransactionValue = true)
        assertFailsWith<NestedTransactionUnsupportedException> {
            runEntTransaction(driver, { _, _ -> Unit }) { }
        }
        assertEquals(0, driver.transactionsStarted)
    }

    @Test
    fun `an ordinary failure reading the driver's transaction posture stays inside the boundary`() {
        val probeFailure = IllegalStateException("pool exploded during posture read")
        val driver = FakeDriver(inTransactionError = probeFailure)
        val result = runEntTransaction(driver, { _, _ -> Unit }) { error("unreachable") }
        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(probeFailure, failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertEquals(0, driver.transactionsStarted)
    }

    @Test
    fun `cancellation from the posture read propagates instead of becoming a result`() {
        val driver = FakeDriver(
            inTransactionError = java.util.concurrent.CancellationException("cancelled"),
        )
        assertFailsWith<java.util.concurrent.CancellationException> {
            runEntTransaction(driver, { _, _ -> Unit }) { }
        }
    }

    @Test
    fun `cancellation propagates out of the boundary instead of becoming a result`() {
        assertFailsWith<java.util.concurrent.CancellationException> {
            runEntTransaction(FakeDriver(), { _, _ -> Unit }) {
                throw java.util.concurrent.CancellationException("cancelled")
            }
        }
    }

    /**
     * Minimal fake driver: executes the transaction block against
     * itself, confirming rollback for ordinary exceptions and
     * rethrowing cancellation/errors, mirroring the documented SPI
     * contract without any storage.
     */
    private class FakeDriver(
        private val inTransactionValue: Boolean = false,
        private val inTransactionError: Exception? = null,
    ) : Driver {
        var transactionsStarted = 0

        override fun requireBindCapacity(minimumParameters: Long, table: String) {}

        override val inTransaction: Boolean
            get() = inTransactionError?.let { throw it } ?: inTransactionValue

        override fun <T> withTransaction(block: (Driver) -> T): DriverTransactionResult<T> {
            transactionsStarted++
            return try {
                DriverTransactionResult.Success(block(FakeDriver(inTransactionValue = true)))
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                DriverTransactionResult.Failed(e, TransactionFailureState.NotCommitted)
            }
        }

        override fun registerAll(schemas: List<EntitySchema>) = Unit
        override fun register(schema: EntitySchema) = Unit
        override fun registeredIdColumn(table: String): String = "id"
        override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> =
            error("unused")
        override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? =
            error("unused")
        override fun byId(table: String, id: Any): Map<String, Any?>? = error("unused")
        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> = error("unused")
        override fun directToManyWindowCapability(): entkt.runtime.driver.DirectToManyWindowCapability =
            error("unused")
        @OptIn(entkt.query.EntktInternal::class)
        override fun queryDirectToMany(
            query: entkt.runtime.driver.DirectToManyQuery,
        ): entkt.runtime.driver.RelatedRows = error("unused")
        override fun count(table: String, predicates: List<Predicate<*>>): Long = error("unused")
        override fun exists(table: String, predicates: List<Predicate<*>>): Boolean = error("unused")
        override fun delete(table: String, id: Any): Boolean = error("unused")
        override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> =
            error("unused")
        override fun updateMany(
            table: String,
            values: Map<String, Any?>,
            predicates: List<Predicate<*>>,
        ): Int = error("unused")
        override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int = error("unused")
    }
}
