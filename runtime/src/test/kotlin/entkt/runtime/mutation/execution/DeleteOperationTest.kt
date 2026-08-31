@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeleteOperationTest {
    private data class Widget(
        override val id: Long,
        val name: String,
    ) : EntEntity.LongId

    @Test
    fun `scalar delete terminals delegate to the bound lifecycle`() {
        val harness = Harness(inTransaction = false)
        val entity = Widget(1L, "Ada")

        assertEquals(MutationResult.Success(Unit), harness.operation.delete(harness.vc, entity))
        assertEquals(MutationResult.Success(true), harness.operation.deleteById(harness.vc, 2L))

        val entityCall = harness.entityCalls.single()
        assertSame(harness.vc, entityCall.vc)
        assertSame(entity, entityCall.entity)
        val idCall = harness.idCalls.single()
        assertSame(harness.vc, idCall.vc)
        assertEquals(2L, idCall.id)
    }

    @Test
    fun `caller transaction snapshots predicates and promotes bulk write failures`() {
        val harness = Harness(inTransaction = true)
        val predicate = Predicate.Leaf<Widget>("name", Op.EQ, "Ada")
        val requested = mutableListOf<Predicate<Widget>>(predicate)

        val result = harness.operation.deleteMany(harness.vc, requested)

        assertEquals(MutationResult.Success(2), result)
        assertEquals(listOf("Widget deleteMany" to true), harness.runtime.preflights)
        val call = harness.manyCalls.single()
        assertSame(harness.vc, call.vc)
        assertEquals(listOf(predicate), call.predicates)
        assertNotSame(requested, call.predicates)
        assertTrue(call.promoteDriverNotPersisted)
        assertTrue(harness.runtime.failures.isEmpty())
    }

    @Test
    fun `owned transaction re-enters its transaction-bound delete operation`() {
        val tx = Harness(inTransaction = true)
        val root = Harness(inTransaction = false)
        root.ownedTransactionHandler = { vc, predicates ->
            when (
                val result = tx.operation.deleteManyInOwnedTransactionForInternalUse(
                    vc,
                    predicates,
                )
            ) {
                is MutationResult.Success -> TransactionResult.Success(result.value)
                is MutationResult.Failed -> TransactionResult.failedForInternalUse(
                    result.exception,
                    TransactionFailureState.NotCommitted,
                )
            }
        }
        val predicate = Predicate.Leaf<Widget>("name", Op.EQ, "Ada")

        val result = root.operation.deleteMany(root.vc, listOf(predicate))

        assertEquals(MutationResult.Success(2), result)
        val call = tx.manyCalls.single()
        assertSame(root.vc, call.vc)
        assertEquals(listOf(predicate), call.predicates)
        assertEquals(false, call.promoteDriverNotPersisted)
        assertTrue(root.manyCalls.isEmpty())
        assertTrue(root.runtime.failures.isEmpty())
        assertTrue(tx.runtime.failures.isEmpty())
    }

    @Test
    fun `confirmed rollback preserves a typed NotPersisted failure`() {
        val typed = EntConflictException(
            entityType = "Widget",
            operation = EntOperation.DELETE,
            code = "conflict",
            message = "conflict",
        )
        val harness = Harness(inTransaction = false)
        harness.ownedTransactionHandler = { _, _ ->
            TransactionResult.failedForInternalUse(
                typed,
                TransactionFailureState.NotCommitted,
            )
        }

        val result = harness.operation.deleteMany(harness.vc, emptyList())

        assertSame(typed, assertIs<MutationResult.Failed>(result).exception)
        assertSame(typed, harness.runtime.failures.single())
    }

    @Test
    fun `confirmed rollback replaces a pending wrapper with NotPersisted`() {
        val driverFailure = IllegalStateException("connection aborted")
        val pending = EntUnexpectedMutationException(
            MutationWriteState.TransactionPending,
            driverFailure,
        )
        val harness = Harness(inTransaction = false)
        harness.ownedTransactionHandler = { _, _ ->
            TransactionResult.failedForInternalUse(
                pending,
                TransactionFailureState.NotCommitted,
            )
        }

        val result = harness.operation.deleteMany(harness.vc, emptyList())

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertNotSame(pending, failure)
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(driverFailure, failure.cause)
    }

    @Test
    fun `unknown owned transaction outcome remains PersistenceUnknown`() {
        val boundaryFailure = IllegalStateException("commit outcome unknown")
        val harness = Harness(inTransaction = false)
        harness.ownedTransactionHandler = { _, _ ->
            TransactionResult.failedForInternalUse(
                boundaryFailure,
                TransactionFailureState.OutcomeUnknown,
            )
        }

        val result = harness.operation.deleteMany(harness.vc, emptyList())

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.PersistenceUnknown, failure.writeState)
        assertSame(boundaryFailure, failure.cause)
        assertSame(failure, harness.runtime.failures.single())
    }

    @Test
    fun `ordinary owned transaction adapter failure is NotPersisted`() {
        val adapterFailure = IllegalStateException("could not begin transaction")
        val harness = Harness(inTransaction = false)
        harness.ownedTransactionHandler = { _, _ -> throw adapterFailure }

        val result = harness.operation.deleteMany(harness.vc, emptyList())

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(adapterFailure, failure.cause)
    }

    @Test
    fun `cancellation escapes without recording a mutation failure`() {
        val cancellation = CancellationException("cancelled")
        val harness = Harness(inTransaction = false)
        harness.ownedTransactionHandler = { _, _ -> throw cancellation }

        val thrown = assertFailsWith<CancellationException> {
            harness.operation.deleteMany(harness.vc, emptyList())
        }

        assertSame(cancellation, thrown)
        assertTrue(harness.runtime.failures.isEmpty())
    }

    private data class EntityCall(
        val vc: ViewerContext,
        val entity: Widget,
    )

    private data class IdCall(
        val vc: ViewerContext,
        val id: Any,
    )

    private data class ManyCall(
        val vc: ViewerContext,
        val predicates: List<Predicate<Widget>>,
        val promoteDriverNotPersisted: Boolean,
    )

    private class Harness(inTransaction: Boolean) {
        val vc = ViewerContext(Viewer.User(7L))
        val runtime = RecordingRuntime()
        val entityCalls = mutableListOf<EntityCall>()
        val idCalls = mutableListOf<IdCall>()
        val manyCalls = mutableListOf<ManyCall>()
        var ownedTransactionHandler: (
            ViewerContext,
            List<Predicate<Widget>>,
        ) -> TransactionResult<Int> = { _, _ -> error("owned transaction was not configured") }

        val operation = DeleteOperation(
            driver = PostureDriver(inTransaction),
            mutationRuntime = runtime,
            entityName = "Widget",
            executeEntity = { suppliedVc, entity ->
                entityCalls += EntityCall(suppliedVc, entity)
                MutationResult.Success(Unit)
            },
            executeId = { suppliedVc, id ->
                idCalls += IdCall(suppliedVc, id)
                MutationResult.Success(true)
            },
            executeMany = { suppliedVc, predicates, promoteDriverNotPersisted ->
                manyCalls += ManyCall(
                    suppliedVc,
                    predicates,
                    promoteDriverNotPersisted,
                )
                MutationResult.Success(2)
            },
            ownedTransaction = { suppliedVc, predicates ->
                ownedTransactionHandler(suppliedVc, predicates)
            },
        )
    }

    private class PostureDriver(
        private val transaction: Boolean,
    ) : DatabaseDriver by NoopDriver {
        override val inTransaction: Boolean
            get() = transaction
    }

    private class RecordingRuntime : MutationRuntime {
        val preflights = mutableListOf<Pair<String, Boolean>>()
        val failures = mutableListOf<EntMutationException>()

        override fun checkTransactionRequirement(operation: String, multiWrite: Boolean) {
            preflights += operation to multiWrite
        }

        override fun recordTransactionMutationFailure(exception: EntMutationException) {
            failures += exception
        }

        override fun isConfigured(entity: EntityMapping<*>): Boolean = false

        override fun <Entity : EntEntity<*>> evaluate(
            entity: EntityMapping<Entity>,
            viewerContext: ViewerContext,
            entities: List<Entity>,
        ): PrivacyEvaluation<Entity> = error("unused")
    }
}
