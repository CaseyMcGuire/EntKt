@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.execution.LoadPrivacyEvaluation
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
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
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CreateOperationTest {
    private class Draft : CreateMutationDraft<Widget> {
        var name: String = ""
    }

    private data class Widget(
        override val id: Long,
        val name: String,
    ) : EntEntity.LongId

    @Test
    fun `scalar create delegates to the bound lifecycle`() {
        val harness = Harness(inTransaction = false)
        val draft = Draft().apply { name = "Ada" }

        val result = harness.operation.create(
            vc = harness.vc,
            draft = draft,
            checkReturnedEntityPrivacy = true,
        )

        assertEquals(MutationResult.Success(Widget(1, "Ada")), result)
        val call = harness.oneCalls.single()
        assertSame(harness.vc, call.vc)
        assertSame(draft, call.draft)
        assertTrue(call.checkReturnedEntityPrivacy)
    }

    @Test
    fun `caller transaction runs bound bulk lifecycle and returned disclosure`() {
        val harness = Harness(inTransaction = true)

        val result = harness.operation.createMany(
            harness.vc,
            listOf(
                { name = "Ada" },
                { name = "Grace" },
            ),
        )

        assertEquals(
            MutationResult.Success(
                listOf(Widget(1, "Ada"), Widget(2, "Grace")),
            ),
            result,
        )
        assertEquals(listOf("Widget createMany" to true), harness.runtime.preflights)
        assertEquals(listOf("Ada", "Grace"), harness.manyCalls.single().drafts.map { it.name })
        assertTrue(harness.manyCalls.single().promoteDriverNotPersisted)
        assertSame(harness.vc, harness.manyCalls.single().vc)
        assertSame(harness.vc, harness.disclosureContexts.single())
        assertTrue(harness.runtime.failures.isEmpty())
    }

    @Test
    fun `caller transaction assigns TransactionPending to returned LOAD denial`() {
        val harness = Harness(inTransaction = true)
        harness.loadDenial = denial()

        val result = harness.operation.createMany(
            harness.vc,
            listOf({ name = "Ada" }),
        )

        val failure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.TransactionPending, failure.writeState)
        assertEquals(EntityKey("id", 1L), failure.entityKey)
        assertSame(failure, harness.runtime.failures.single())
    }

    @Test
    fun `owned transaction keeps LOAD denial neutral until commit is confirmed`() {
        val tx = Harness(inTransaction = true).apply {
            loadDenial = denial()
        }
        val root = Harness(inTransaction = false)
        root.ownedTransactionHandler = { vc, blocks, capture ->
            when (
                val result = tx.operation.createManyInOwnedTransactionForInternalUse(
                    vc,
                    blocks,
                    capture,
                )
            ) {
                is MutationResult.Success -> TransactionResult.Success(result.value)
                is MutationResult.Failed -> TransactionResult.failedForInternalUse(
                    result.exception,
                    TransactionFailureState.NotCommitted,
                )
            }
        }

        val result = root.operation.createMany(
            root.vc,
            listOf({ name = "Ada" }),
        )

        val failure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.Committed, failure.writeState)
        assertTrue(tx.runtime.failures.isEmpty(), "neutral disclosure must not mark the tx rollback-only")
        assertSame(failure, root.runtime.failures.single())
        assertSame(root.vc, tx.manyCalls.single().vc)
        assertSame(root.vc, tx.disclosureContexts.single())
    }

    @Test
    fun `confirmed rollback preserves captured disclosure failure as primary`() {
        val disclosureFailure = IllegalStateException("LOAD query aborted the transaction")
        val boundaryFailure = IllegalStateException("transaction is aborted")
        val tx = Harness(inTransaction = true).apply {
            loadFailure = disclosureFailure
        }
        val root = Harness(inTransaction = false)
        root.ownedTransactionHandler = { vc, blocks, capture ->
            val result = tx.operation.createManyInOwnedTransactionForInternalUse(
                vc,
                blocks,
                capture,
            )
            assertIs<MutationResult.Success<CreateManyDisclosure<Widget>>>(result)
            TransactionResult.failedForInternalUse(
                boundaryFailure,
                TransactionFailureState.NotCommitted,
            )
        }

        val result = root.operation.createMany(
            root.vc,
            listOf({ name = "Ada" }),
        )

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(disclosureFailure, failure.cause)
        assertSame(boundaryFailure, failure.suppressed.single())
        assertSame(failure, root.runtime.failures.single())
    }

    @Test
    fun `unknown owned transaction outcome remains primary over captured disclosure`() {
        val disclosureFailure = IllegalStateException("LOAD failed")
        val boundaryFailure = IllegalStateException("commit outcome unknown")
        val tx = Harness(inTransaction = true).apply {
            loadFailure = disclosureFailure
        }
        val root = Harness(inTransaction = false)
        root.ownedTransactionHandler = { vc, blocks, capture ->
            tx.operation.createManyInOwnedTransactionForInternalUse(vc, blocks, capture)
            TransactionResult.failedForInternalUse(
                boundaryFailure,
                TransactionFailureState.OutcomeUnknown,
            )
        }

        val result = root.operation.createMany(
            root.vc,
            listOf({ name = "Ada" }),
        )

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.PersistenceUnknown, failure.writeState)
        assertSame(boundaryFailure, failure.cause)
        assertSame(disclosureFailure, failure.suppressed.single())
    }

    @Test
    fun `empty bulk create checks policy without opening a transaction`() {
        val harness = Harness(inTransaction = false)
        var transactionCalled = false
        harness.ownedTransactionHandler = { _, _, _ ->
            transactionCalled = true
            error("unexpected transaction")
        }

        val result = harness.operation.createMany(harness.vc, emptyList())

        assertEquals(MutationResult.Success(emptyList()), result)
        assertEquals(listOf("Widget createMany" to false), harness.runtime.preflights)
        assertEquals(false, transactionCalled)
    }

    @Test
    fun `disclosure cancellation escapes without recording a mutation failure`() {
        val cancellation = CancellationException("cancelled")
        val harness = Harness(inTransaction = true).apply {
            loadFailure = cancellation
        }

        val thrown = assertFailsWith<CancellationException> {
            harness.operation.createMany(
                harness.vc,
                listOf({ name = "Ada" }),
            )
        }

        assertSame(cancellation, thrown)
        assertTrue(harness.runtime.failures.isEmpty())
    }

    private data class OneCall(
        val vc: ViewerContext,
        val draft: Draft,
        val checkReturnedEntityPrivacy: Boolean,
    )

    private data class ManyCall(
        val vc: ViewerContext,
        val drafts: List<Draft>,
        val promoteDriverNotPersisted: Boolean,
    )

    private class Harness(inTransaction: Boolean) {
        val vc = ViewerContext(Viewer.User(7L))
        val runtime = RecordingRuntime()
        val oneCalls = mutableListOf<OneCall>()
        val manyCalls = mutableListOf<ManyCall>()
        val disclosureContexts = mutableListOf<ViewerContext>()
        var loadDenial: PrivacyDenial? = null
        var loadFailure: Exception? = null
        var ownedTransactionHandler: (
            ViewerContext,
            List<Draft.() -> Unit>,
            CreateManyDisclosureCapture,
        ) -> TransactionResult<CreateManyDisclosure<Widget>> = { _, _, _ ->
            error("owned transaction was not configured")
        }

        val operation = CreateOperation(
            driver = PostureDriver(inTransaction),
            mutationRuntime = runtime,
            entityName = "Widget",
            newDraft = ::Draft,
            executeOne = { suppliedVc, draft, checkReturnedEntityPrivacy ->
                oneCalls += OneCall(suppliedVc, draft, checkReturnedEntityPrivacy)
                MutationResult.Success(Widget(1, draft.name))
            },
            executeMany = { suppliedVc, drafts, promoteDriverNotPersisted ->
                manyCalls += ManyCall(
                    suppliedVc,
                    drafts.toList(),
                    promoteDriverNotPersisted,
                )
                MutationResult.Success(
                    drafts.mapIndexed { index, draft ->
                        Widget(index + 1L, draft.name)
                    },
                )
            },
            returnedEntityDenial = { suppliedVc, _ ->
                disclosureContexts += suppliedVc
                loadFailure?.let { throw it }
                loadDenial
            },
            ownedTransaction = { suppliedVc, blocks, capture ->
                ownedTransactionHandler(suppliedVc, blocks, capture)
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
        ): List<LoadPrivacyEvaluation<Entity>> = error("unused")
    }

    private fun denial(): PrivacyDenial = PrivacyDenial(
        entityType = "Widget",
        entityKey = EntityKey("id", 1L),
        reason = "hidden",
    )
}
