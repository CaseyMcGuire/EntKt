@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.Hook
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.execution.LoadPrivacyEvaluation
import entkt.runtime.query.execution.correlateLoadPrivacyEvaluationsForInternalUse
import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntTargetAbsentException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ValidationViolation
import entkt.runtime.validation.ValidationDecision
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UpdateMutationExecutorTest {
    private data class Widget(
        override val id: Long,
        val name: String,
    ) : EntEntity.LongId

    private data class State(val name: String)

    private class RecordingMapping(
        private val events: MutableList<String>,
    ) : EntityMapping<Widget> {
        override val entityName = "Widget"
        override val clientName = "widgets"
        override val entityClass = Widget::class
        override val table = "widgets"

        override fun decode(row: Map<String, Any?>): Widget {
            events += "decode:${row.getValue("name")}"
            return Widget(row.getValue("id") as Long, row.getValue("name") as String)
        }

        override fun edgeByStorageName(storageName: String): EdgeMapping<Widget, *>? = null
    }

    private class RecordingDriver(
        private val events: MutableList<String>,
        private val transaction: Boolean,
    ) : DatabaseDriver by NoopDriver {
        var updatedRow: Map<String, Any?>? = mapOf("id" to 1L, "name" to "after")
        var updateFailure: Exception? = null
        var classifiedFailure: EntMutationException? = null

        override val inTransaction: Boolean
            get() {
                events += "transaction-state"
                return transaction
            }

        override fun update(
            table: String,
            id: Any,
            values: Map<String, Any?>,
        ): Map<String, Any?>? {
            events += "update:$id:${values.getValue("name")}"
            updateFailure?.let { throw it }
            return updatedRow
        }

        override fun classifyMutationException(
            exception: Exception,
            entity: String,
            operation: EntOperation,
        ): EntMutationException? {
            events += "classify:$entity:$operation"
            return classifiedFailure
        }
    }

    private class Fixture(
        inTransaction: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val driver = RecordingDriver(events, inTransaction)
        val mapping = RecordingMapping(events)
        val viewerContext = ViewerContext(Viewer.User(7L))
        val ruleClient = Any()
        val failures = mutableListOf<EntMutationException>()
        val receivedContexts = mutableListOf<ViewerContext>()
        val receivedClients = mutableListOf<Any>()
        var loadedRow: Map<String, Any?>? = mapOf("id" to 1L, "name" to "before")
        var preparation: UpdatePreparation<State> = UpdatePreparation.Ready(
            PreparedUpdate(State("after"), mapOf("name" to "after"), isNoOp = false),
        )
        var privacyDecision: PrivacyDecision = PrivacyDecision.Allow
        var invalids: List<ValidationDecision.Invalid> = emptyList()
        var loadDenial: PrivacyDenial? = null
        var relationshipAction: (State, UpdateWriteTracker) -> Unit = { _, _ -> }
        var afterAction: (Widget) -> Unit = {}

        val spec: UpdateMutationSpec<State, Widget, Any>
            get() = UpdateMutationSpec(
                entity = mapping,
                id = 1L,
                preflight = { events += "spec-preflight" },
                loadRow = {
                    events += "load-row"
                    loadedRow
                },
                begin = { events += "begin" },
                end = { events += "end" },
                before = { context, entity ->
                    events += "before:${entity.name}"
                    receivedContexts += context
                },
                prepare = { entity, _ ->
                    events += "prepare:${entity.name}"
                    preparation
                },
                privacy = MutationPrivacyPhase { context, client, states ->
                    events += "privacy:${states.single().name}"
                    receivedContexts += context
                    receivedClients += client
                    listOf(privacyDecision)
                },
                validation = MutationValidationPhase { client, states ->
                    events += "validation:${states.single().name}"
                    receivedClients += client
                    listOf(invalids)
                },
                relationships = { state, writes ->
                    events += "relationships:${state.name}"
                    relationshipAction(state, writes)
                },
                afterUpdate = listOf(
                    Hook { entity ->
                        events += "after:${entity.name}"
                        afterAction(entity)
                    },
                ),
            )

        val executor = UpdateMutationExecutor(
            driver = driver,
            mutationRuntime = object : MutationRuntime {
                override fun checkTransactionRequirement(operation: String, multiWrite: Boolean) {
                    events += "runtime-preflight:$operation:$multiWrite"
                }

                override fun recordTransactionMutationFailure(exception: EntMutationException) {
                    events += "record-failure"
                    failures += exception
                }

                override fun isConfigured(entity: EntityMapping<*>): Boolean = true

                override fun <Entity : EntEntity<*>> evaluate(
                    entity: EntityMapping<Entity>,
                    viewerContext: ViewerContext,
                    entities: List<Entity>,
                ): List<LoadPrivacyEvaluation<Entity>> {
                    events += "load-privacy"
                    receivedContexts += viewerContext
                    return correlateLoadPrivacyEvaluationsForInternalUse(
                        lifecycle = "Widget LOAD privacy",
                        entities = entities,
                        denials = entities.map { loadDenial },
                    )
                }
            },
            ruleClient = ruleClient,
        )
    }

    @Test
    fun `update executor owns lifecycle order and preserves supplied identities`() {
        val fixture = Fixture()

        val result = fixture.executor.update(
            fixture.viewerContext,
            applyLoadPrivacy = true,
            fixture.spec,
        )

        assertEquals(MutationResult.Success(Widget(1L, "after")), result)
        assertEquals(
            listOf(
                "transaction-state",
                "runtime-preflight:Widget update:false",
                "spec-preflight",
                "load-row",
                "decode:before",
                "begin",
                "before:before",
                "prepare:before",
                "privacy:after",
                "validation:after",
                "update:1:after",
                "decode:after",
                "relationships:after",
                "after:after",
                "load-privacy",
                "end",
            ),
            fixture.events,
        )
        assertTrue(fixture.receivedContexts.all { it === fixture.viewerContext })
        assertTrue(fixture.receivedClients.all { it === fixture.ruleClient })
    }

    @Test
    fun `no-op still evaluates rules and load privacy but skips every post-persist phase`() {
        val fixture = Fixture()
        fixture.preparation = UpdatePreparation.Ready(
            PreparedUpdate(State("before"), emptyMap(), isNoOp = true),
        )

        val result = fixture.executor.update(fixture.viewerContext, true, fixture.spec)

        assertEquals(MutationResult.Success(Widget(1L, "before")), result)
        assertTrue("privacy:before" in fixture.events)
        assertTrue("validation:before" in fixture.events)
        assertTrue("load-privacy" in fixture.events)
        assertFalse(fixture.events.any { it.startsWith("update:") || it.startsWith("relationships:") || it.startsWith("after:") })
        assertEquals("end", fixture.events.last())
    }

    @Test
    fun `missing target is typed and does not open generated update state`() {
        val fixture = Fixture()
        fixture.loadedRow = null

        val result = fixture.executor.update(fixture.viewerContext, false, fixture.spec)

        val failure = assertIs<EntTargetAbsentException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(EntityKey("id", 1L), failure.key)
        assertSame(failure, fixture.failures.single())
        assertFalse("begin" in fixture.events || "end" in fixture.events)
    }

    @Test
    fun `generated preparation violations are typed and cleanup always runs`() {
        val fixture = Fixture()
        fixture.preparation = UpdatePreparation.Invalid(
            listOf(ValidationViolation("name is required", field = "name")),
        )

        val result = fixture.executor.update(fixture.viewerContext, false, fixture.spec)

        val failure = assertIs<EntValidationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals("name is required", failure.violations.single().message)
        assertFalse(fixture.events.any { it.startsWith("privacy:") || it.startsWith("update:") })
        assertEquals(listOf("end", "record-failure"), fixture.events.takeLast(2))
    }

    @Test
    fun `privacy and entity validation reject before owner persistence`() {
        val privacyFixture = Fixture()
        privacyFixture.privacyDecision = PrivacyDecision.Deny("owner only")

        val privacyResult = privacyFixture.executor.update(
            privacyFixture.viewerContext,
            false,
            privacyFixture.spec,
        )
        val privacyFailure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(privacyResult).exception,
        )
        assertEquals(EntOperation.UPDATE, privacyFailure.operation)
        assertEquals(MutationWriteState.NotPersisted, privacyFailure.writeState)
        assertFalse(privacyFixture.events.any { it.startsWith("validation:") || it.startsWith("update:") })

        val validationFixture = Fixture()
        validationFixture.invalids = listOf(ValidationDecision.Invalid("reserved", field = "name"))
        val validationResult = validationFixture.executor.update(
            validationFixture.viewerContext,
            false,
            validationFixture.spec,
        )
        val validationFailure = assertIs<EntValidationException>(
            assertIs<MutationResult.Failed>(validationResult).exception,
        )
        assertEquals("reserved", validationFailure.violations.single().message)
        assertFalse(validationFixture.events.any { it.startsWith("update:") })
    }

    @Test
    fun `recognized owner-write failure is returned and recorded by identity`() {
        val fixture = Fixture()
        val driverFailure = IllegalStateException("conflict")
        val classified = EntConflictException("Widget", EntOperation.UPDATE, "version", "conflict", driverFailure)
        fixture.driver.updateFailure = driverFailure
        fixture.driver.classifiedFailure = classified

        val result = fixture.executor.update(fixture.viewerContext, false, fixture.spec)

        assertSame(classified, assertIs<MutationResult.Failed>(result).exception)
        assertSame(classified, fixture.failures.single())
        assertEquals("end", fixture.events[fixture.events.lastIndex - 1])
    }

    @Test
    fun `relationship failure promotes statement-level rejection after an earlier write`() {
        val fixture = Fixture(inTransaction = true)
        val relationshipFailure = IllegalStateException("junction")
        val classified = EntConflictException(
            "Widget",
            EntOperation.UPDATE,
            "relationship",
            "conflict",
            relationshipFailure,
        )
        fixture.driver.classifiedFailure = classified
        fixture.relationshipAction = { _, writes ->
            writes.markWritten()
            throw relationshipFailure
        }

        val result = fixture.executor.update(fixture.viewerContext, false, fixture.spec)

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.TransactionPending, failure.writeState)
        assertSame(classified, failure.cause)
    }

    @Test
    fun `after-hook and returned-load failures report the post-write posture`() {
        val afterFixture = Fixture()
        val hookFailure = IllegalStateException("after")
        afterFixture.afterAction = { throw hookFailure }

        val afterResult = afterFixture.executor.update(
            afterFixture.viewerContext,
            false,
            afterFixture.spec,
        )
        val afterFailure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(afterResult).exception,
        )
        assertEquals(MutationWriteState.Committed, afterFailure.writeState)
        assertSame(hookFailure, afterFailure.cause)

        val loadFixture = Fixture(inTransaction = true)
        loadFixture.loadDenial = PrivacyDenial("Widget", EntityKey("id", 1L), "hidden")
        val loadResult = loadFixture.executor.update(
            loadFixture.viewerContext,
            true,
            loadFixture.spec,
        )
        val loadFailure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(loadResult).exception,
        )
        assertEquals(EntOperation.LOAD, loadFailure.operation)
        assertEquals(MutationWriteState.TransactionPending, loadFailure.writeState)
    }

    @Test
    fun `cancellation propagates without failure recording and still clears generated state`() {
        val fixture = Fixture()
        val cancellation = CancellationException("cancel")
        fixture.relationshipAction = { _, _ -> throw cancellation }

        val thrown = assertFailsWith<CancellationException> {
            fixture.executor.update(fixture.viewerContext, false, fixture.spec)
        }

        assertSame(cancellation, thrown)
        assertTrue(fixture.failures.isEmpty())
        assertEquals("end", fixture.events.last())
    }
}
