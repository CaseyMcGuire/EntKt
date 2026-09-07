@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MutationExecutorTest {
    @Test
    fun `passes the original input and driver after checking application policy`() {
        val harness = Harness()
        val input = Input("Ada")

        assertEquals(MutationResult.Success("Ada"), harness.execute(input))

        assertEquals(listOf("requirements", "preflight", "run"), harness.events)
        assertEquals(listOf("Widget mutation" to false), harness.runtime.preflights)
        assertSame(input, harness.calls.single().second)
        assertSame(harness.driver, harness.calls.single().first.driver)
        assertFalse(harness.calls.single().first.isOwnedTransaction)
    }

    @Test
    fun `application policy rejection precedes the operation and owned transaction`() {
        val harness = Harness(atomic = true)
        val denied = IllegalStateException("application transaction required")
        harness.runtime.preflightFailure = denied
        harness.ownedTransaction = { _, _ -> error("must not open a transaction") }

        val failure = unexpected(harness.execute())

        assertSame(denied, failure.cause)
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertTrue(harness.calls.isEmpty())
        assertSame(failure, harness.runtime.failures.single())
    }

    @Test
    fun `an existing caller transaction is reused`() {
        val harness = Harness(inTransaction = true, atomic = true, multiWrite = true)
        harness.ownedTransaction = { _, _ -> error("must not nest a transaction") }

        assertEquals(MutationResult.Success("Ada"), harness.execute())

        assertFalse(harness.calls.single().first.isOwnedTransaction)
        assertEquals(listOf("Widget mutation" to true), harness.runtime.preflights)
    }

    @Test
    fun `owned execution selects the transaction operation and its matching dependencies`() {
        val root = Harness(atomic = true)
        val tx = Harness(inTransaction = true, atomic = true)
        val input = Input("Ada")
        root.bindTransaction(tx)

        assertEquals(MutationResult.Success("Ada"), root.execute(input))

        assertTrue(root.calls.isEmpty())
        assertSame(input, tx.calls.single().second)
        assertSame(tx.driver, tx.calls.single().first.driver)
        assertTrue(tx.calls.single().first.isOwnedTransaction)
        assertTrue(tx.runtime.preflights.isEmpty(), "application policy was already checked at the root")
        assertTrue(tx.runtime.failures.isEmpty())
    }

    @Test
    fun `missing owned transaction wiring fails before the operation runs`() {
        val harness = Harness(atomic = true)

        assertEquals(MutationWriteState.NotPersisted, unexpected(harness.execute()).writeState)
        assertTrue(harness.calls.isEmpty())
    }

    @Test
    fun `owned entry rejects an unbound driver before running the operation`() {
        val harness = Harness()

        val result = harness.executeOwned(Input("Ada"), MutationCompletionCapture())

        assertEquals(MutationWriteState.NotPersisted, unexpected(result).writeState)
        assertTrue(harness.calls.isEmpty())
    }

    @Test
    fun `return failures are recorded with the caller transaction write state`() {
        val cause = IllegalStateException("LOAD failed")
        for (completion in listOf(denied(), MutationCompletion.ReturnFailed(cause))) {
            val harness = Harness(inTransaction = true)
            harness.completion = completion

            val failure = failed(harness.execute())

            assertEquals(MutationWriteState.TransactionPending, failure.writeState)
            assertSame(failure, harness.runtime.failures.single())
            if (completion is MutationCompletion.ReturnDenied) {
                assertEquals(EntOperation.LOAD, assertIs<EntMutationPrivacyDeniedException>(failure).operation)
            } else {
                assertSame(cause, failure.cause)
            }
        }
    }

    @Test
    fun `owned return failures remain neutral until commit is confirmed`() {
        val cause = IllegalStateException("LOAD failed")
        for (completion in listOf(denied(), MutationCompletion.ReturnFailed(cause))) {
            val root = Harness(atomic = true)
            val tx = Harness(inTransaction = true)
            tx.completion = completion
            root.ownedTransaction = { input, capture ->
                val result = assertIs<MutationResult.Success<MutationCompletion<String>>>(tx.executeOwned(input, capture))
                assertSame(completion, result.value)
                assertTrue(tx.runtime.failures.isEmpty(), "return failures must not mark owned work rollback-only")
                TransactionResult.Success(result.value)
            }

            val failure = failed(root.execute())

            assertEquals(MutationWriteState.Committed, failure.writeState)
            assertSame(failure, root.runtime.failures.single())
            if (completion is MutationCompletion.ReturnDenied) {
                assertEquals(EntityKey("id", 1L), assertIs<EntMutationPrivacyDeniedException>(failure).entityKey)
            } else {
                assertSame(cause, failure.cause)
            }
        }
    }

    @Test
    fun `a no-write completion stays NotPersisted even when its owned transaction commits`() {
        val root = Harness(atomic = true)
        val tx = Harness(inTransaction = true)
        tx.writes = false
        tx.completion = denied()
        root.bindTransaction(tx)

        assertEquals(MutationWriteState.NotPersisted, failed(root.execute()).writeState)
    }

    @Test
    fun `lifecycle rejection fails owned work instead of becoming a neutral return failure`() {
        val root = Harness(atomic = true)
        val tx = Harness(inTransaction = true)
        val rejection = conflict()
        tx.rejection = rejection
        root.bindTransaction(tx)

        assertSame(rejection, failed(root.execute()))
        assertSame(rejection, tx.runtime.failures.single())
    }

    @Test
    fun `confirmed rollback preserves typed NotPersisted failures`() {
        val harness = Harness(atomic = true)
        val rejection = conflict()
        harness.ownedTransaction = { _, _ -> transactionFailure(rejection) }

        assertSame(rejection, failed(harness.execute()))
    }

    @Test
    fun `confirmed rollback replaces a pending failure and preserves its suppressed errors`() {
        val cause = IllegalStateException("write failed")
        val secondary = IllegalStateException("secondary")
        val pending = EntUnexpectedMutationException(MutationWriteState.TransactionPending, cause)
        pending.addSuppressed(secondary)
        val harness = Harness(atomic = true)
        harness.ownedTransaction = { _, _ -> transactionFailure(pending) }

        val failure = unexpected(harness.execute())

        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(cause, failure.cause)
        assertSame(secondary, failure.suppressed.single())
    }

    @Test
    fun `confirmed rollback retains a captured return failure as primary`() {
        val cause = IllegalStateException("LOAD aborted SQL transaction")
        for (completion in listOf(denied(), MutationCompletion.ReturnFailed(cause))) {
            val root = Harness(atomic = true)
            val tx = Harness(inTransaction = true)
            val commitFailure = IllegalStateException("commit failed")
            tx.completion = completion
            root.ownedTransaction = { input, capture ->
                assertIs<MutationResult.Success<*>>(tx.executeOwned(input, capture))
                transactionFailure(commitFailure)
            }

            val failure = failed(root.execute())

            assertEquals(MutationWriteState.NotPersisted, failure.writeState)
            assertSame(commitFailure, failure.suppressed.single())
            if (completion is MutationCompletion.ReturnDenied) {
                assertIs<EntMutationPrivacyDeniedException>(failure)
            } else {
                assertSame(cause, failure.cause)
            }
        }
    }

    @Test
    fun `unknown transaction outcome remains primary over a captured return failure`() {
        val cause = IllegalStateException("LOAD failed")
        for (completion in listOf(denied(), MutationCompletion.ReturnFailed(cause))) {
            val root = Harness(atomic = true)
            val tx = Harness(inTransaction = true)
            val commitFailure = IllegalStateException("commit outcome unknown")
            tx.completion = completion
            root.ownedTransaction = { input, capture ->
                assertIs<MutationResult.Success<*>>(tx.executeOwned(input, capture))
                transactionFailure(commitFailure, TransactionFailureState.OutcomeUnknown)
            }

            val failure = unexpected(root.execute())

            assertEquals(MutationWriteState.PersistenceUnknown, failure.writeState)
            assertSame(commitFailure, failure.cause)
            if (completion is MutationCompletion.ReturnDenied) {
                val suppressed = assertIs<EntMutationPrivacyDeniedException>(failure.suppressed.single())
                assertEquals(MutationWriteState.PersistenceUnknown, suppressed.writeState)
            } else {
                assertSame(cause, failure.suppressed.single())
            }
        }
    }

    @Test
    fun `a recorded mutation failure takes precedence over captured return denial`() {
        val root = Harness(atomic = true)
        val tx = Harness(inTransaction = true)
        val rejection = conflict()
        tx.completion = denied()
        root.ownedTransaction = { input, capture ->
            tx.executeOwned(input, capture)
            transactionFailure(rejection)
        }

        assertSame(rejection, failed(root.execute()))
        assertIs<EntMutationPrivacyDeniedException>(rejection.suppressed.single())
    }

    @Test
    fun `ordinary owned transaction wiring failure is captured as NotPersisted`() {
        val harness = Harness(atomic = true)
        val cause = IllegalStateException("transaction setup failed")
        harness.ownedTransaction = { _, _ -> throw cause }

        val failure = unexpected(harness.execute())

        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(cause, failure.cause)
    }

    @Test
    fun `projection is checked inside execution and does not access unavailable results`() {
        val harness = Harness()
        val cause = IllegalStateException("invalid result shape")
        val projected = harness.operation.mapResult<String> { throw cause }

        val failure = unexpected(harness.executor.execute(projected, Input("Ada")))

        assertEquals(MutationWriteState.Committed, failure.writeState)
        assertSame(cause, failure.cause)
        for (completion in listOf(denied(), MutationCompletion.ReturnFailed(IllegalStateException("LOAD")))) {
            harness.completion = completion
            val unavailable = failed(harness.executor.execute(projected, Input("Ada")))
            assertFalse(unavailable.cause === cause)
        }
    }

    @Test
    fun `projection failure in an owned operation fails its write phases`() {
        val root = Harness(atomic = true)
        val tx = Harness(inTransaction = true)
        val cause = IllegalStateException("invalid result shape")
        root.ownedTransaction = { input, capture ->
            tx.executor.executeInOwnedTransactionForInternalUse(
                tx.operation.mapResult<String> { throw cause }, input, capture,
            ).asTransactionResult()
        }

        val failure = unexpected(root.execute())

        assertSame(cause, failure.cause)
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertEquals(1, tx.runtime.failures.size)
    }

    @Test
    fun `cancellation escapes without recording a mutation failure`() {
        for (atomic in listOf(false, true)) {
            val harness = Harness(atomic = atomic)
            val cancellation = CancellationException("cancelled")
            if (atomic) {
                harness.ownedTransaction = { _, _ -> throw cancellation }
            } else {
                harness.runFailure = cancellation
            }

            assertSame(cancellation, assertFailsWith<CancellationException> { harness.execute() })
            assertTrue(harness.runtime.failures.isEmpty())
        }
    }

    private data class Input(val value: String)

    private class Harness(
        inTransaction: Boolean = false,
        atomic: Boolean = false,
        multiWrite: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val driver = object : DatabaseDriver by NoopDriver {
            override val inTransaction: Boolean = inTransaction
        }
        val runtime = RecordingRuntime(events)
        val executor = MutationExecutor(driver, runtime)
        val calls = mutableListOf<Pair<MutationExecution, Input>>()
        var completion: MutationCompletion<String>? = null
        var writes = true
        var runFailure: Exception? = null
        var rejection: EntMutationException? = null
        var ownedTransaction: ((Input, MutationCompletionCapture) -> TransactionResult<MutationCompletion<String>>)? = null

        val operation = object : MutationOperation<Input, String> {
            override fun requirements(input: Input): MutationRequirements {
                events += "requirements"
                return MutationRequirements("Widget mutation", multiWrite, atomic)
            }

            override fun run(execution: MutationExecution, input: Input): MutationCompletion<String> {
                events += "run"
                calls += execution to input
                rejection?.let { execution.reject(it) }
                if (writes) execution.markWriteSucceeded()
                runFailure?.let { throw it }
                return completion ?: MutationCompletion.Ready(input.value)
            }
        }

        fun execute(input: Input = Input("Ada")) = executor.execute(operation, input, ownedTransaction)

        fun executeOwned(input: Input, capture: MutationCompletionCapture) =
            executor.executeInOwnedTransactionForInternalUse(operation, input, capture)

        fun bindTransaction(tx: Harness) {
            ownedTransaction = { input, capture -> tx.executeOwned(input, capture).asTransactionResult() }
        }
    }

    private class RecordingRuntime(private val events: MutableList<String>) : MutationRuntime {
        val preflights = mutableListOf<Pair<String, Boolean>>()
        val failures = mutableListOf<EntMutationException>()
        var preflightFailure: Exception? = null

        override fun checkTransactionRequirement(operation: String, multiWrite: Boolean) {
            events += "preflight"
            preflights += operation to multiWrite
            preflightFailure?.let { throw it }
        }

        override fun recordTransactionMutationFailure(exception: EntMutationException) {
            failures += exception
        }

        override fun isConfigured(entity: EntityMapping<*>): Boolean = error("privacy belongs to the operation")

        override fun <Entity : EntEntity<*>> evaluate(
            entity: EntityMapping<Entity>,
            viewerContext: ViewerContext,
            entities: List<Entity>,
        ): PrivacyEvaluation<Entity> = error("privacy belongs to the operation")
    }

    private fun failed(result: MutationResult<*>): EntMutationException = assertIs<MutationResult.Failed>(result).exception

    private fun unexpected(result: MutationResult<*>): EntUnexpectedMutationException = assertIs(failed(result))

    private fun conflict() = EntConflictException("Widget", EntOperation.CREATE, "conflict", "conflict")

    private fun denied() = MutationCompletion.ReturnDenied(PrivacyDenial("Widget", EntityKey("id", 1L), "hidden"))

    companion object {
        private fun transactionFailure(
            exception: Exception,
            state: TransactionFailureState = TransactionFailureState.NotCommitted,
        ) = TransactionResult.failedForInternalUse(exception, state)

        private fun <Result> MutationResult<Result>.asTransactionResult(): TransactionResult<Result> = when (this) {
            is MutationResult.Success -> TransactionResult.Success(value)
            is MutationResult.Failed -> transactionFailure(exception)
        }
    }
}
