@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.Hook
import entkt.runtime.hook.HookRunner
import entkt.runtime.hook.MutationHook
import entkt.runtime.hook.MutationHookRunner
import entkt.runtime.mutation.BeforeSaveHookState
import entkt.runtime.mutation.BeforeUpdateHookState
import entkt.runtime.mutation.PreparedUpdateState
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.RelationshipLockKey
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.mutation.UpdateConsistency
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdateMutationRequest
import entkt.runtime.mutation.UpdatePendingEdges
import entkt.runtime.privacyEvaluation
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.PrivacyOperation
import entkt.runtime.query.EdgeMapping
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
import entkt.runtime.validation.batchValidationRule
import entkt.runtime.validation.mutationValidationEvaluatorForInternalUse
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UpdateMutationOperationTest {
    private data class Widget(
        override val id: Long,
        val name: String,
    ) : EntEntity.LongId

    private data class State(val name: String) : PreparedUpdateState<Widget>

    private data class PendingEdges(val description: String) : UpdatePendingEdges<Widget>

    private data class BeforeSaveState(val description: String) : BeforeSaveHookState<Widget>

    private data class BeforeUpdateState(val description: String) : BeforeUpdateHookState<Widget>

    private class Draft : UpdateMutationDraft<Widget>

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
        override val supportsReadRowForUpdate: Boolean,
        override val supportsOwnerEdgeSerialization: Boolean,
        override val supportsInsertIgnore: Boolean,
        override val supportsRelationshipSerialization: Boolean,
    ) : DatabaseDriver by NoopDriver {
        var loadedRow: Map<String, Any?>? = mapOf("id" to 1L, "name" to "before")
        var updatedRow: Map<String, Any?>? = mapOf("id" to 1L, "name" to "after")
        var updateFailure: Exception? = null
        var classifiedFailure: EntMutationException? = null

        override val inTransaction: Boolean
            get() {
                events += "transaction-state"
                return transaction
            }

        override fun byId(table: String, id: Any): Map<String, Any?>? {
            events += "load-row"
            return loadedRow
        }

        override fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? {
            events += "load-row-for-update"
            return loadedRow
        }

        override fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? {
            events += "serialize-owner-edge-and-load"
            return loadedRow
        }

        override fun serializeRelationship(key: RelationshipLockKey) {
            events += "serialize-relationship:${key.junctionTable}"
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
        supportsReadRowForUpdate: Boolean = false,
        supportsOwnerEdgeSerialization: Boolean = false,
        supportsInsertIgnore: Boolean = false,
        supportsRelationshipSerialization: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val driver = RecordingDriver(
            events,
            inTransaction,
            supportsReadRowForUpdate,
            supportsOwnerEdgeSerialization,
            supportsInsertIgnore,
            supportsRelationshipSerialization,
        )
        val mapping = RecordingMapping(events)
        val viewerContext = ViewerContext(Viewer.User(7L))
        val ruleClient = Any()
        val failures = mutableListOf<EntMutationException>()
        val receivedContexts = mutableListOf<ViewerContext>()
        val receivedClients = mutableListOf<Any>()
        var loadedRow: Map<String, Any?>?
            get() = driver.loadedRow
            set(value) {
                driver.loadedRow = value
            }
        var preparation: UpdatePreparation<State> = UpdatePreparation.Ready(
            PreparedUpdate(State("after"), mapOf("name" to "after"), isNoOp = false),
        )
        var privacyDecision: PrivacyDecision = PrivacyDecision.Allow
        var invalids: List<ValidationDecision.Invalid> = emptyList()
        var loadDenial: PrivacyDenial? = null
        var relationshipAction: (State, UpdateWriteTracker) -> Unit = { _, _ -> }
        var afterAction: (Widget) -> Unit = {}
        var currentRelationshipRequirements: UpdateRelationshipRequirements =
            UpdateRelationshipRequirements.None
        val request = UpdateMutationRequest(
            id = 1L,
            draft = Draft(),
            consistency = UpdateConsistency.ReadCurrent,
            relationshipLocking = RelationshipLocking.OwnerOnly,
        )

        val privacyEvaluator = MutationPrivacyEvaluator<Any, State, State>(
            entity = mapping,
            operation = PrivacyOperation.UPDATE,
            rules = listOf(
                batchPrivacyRule<Any, State> { context, states ->
                    events += "privacy:${states.single().name}"
                    receivedContexts += context.viewerContext
                    receivedClients += context.client
                    states.decideEach { privacyDecision }
                },
            ),
            freshItem = { it },
        )

        val validationEvaluator = mutationValidationEvaluatorForInternalUse<Any, State, State>(
            lifecycle = "Widget UPDATE validation",
            rules = listOf(
                batchValidationRule<Any, State> { context, states ->
                    events += "validation:${states.single().name}"
                    receivedClients += context.client
                    states.decideEach { invalids.firstOrNull() ?: ValidationDecision.Valid }
                },
            ),
            freshItem = { it },
        )

        val adapter = object :
            UpdateMutationAdapter<Draft, Widget, PendingEdges, State, BeforeUpdateState> {
            override fun relationshipRequirements(draft: Draft): UpdateRelationshipRequirements =
                currentRelationshipRequirements

            override fun capturePendingEdges(draft: Draft): PendingEdges {
                events += "capture-pending-edges"
                return PendingEdges("pending-edges")
            }

            override fun prepare(
                request: UpdateMutationRequest<Draft>,
                before: Widget,
                pendingEdges: PendingEdges,
                hookState: BeforeUpdateState,
                scope: UpdatePreparationScope,
            ): UpdatePreparation<State> {
                events += "prepare:${before.name}:${pendingEdges.description}"
                return preparation
            }

            override fun persistRelationships(
                request: UpdateMutationRequest<Draft>,
                state: State,
                writes: UpdateWriteTracker,
            ) {
                events += "relationships:${state.name}"
                relationshipAction(state, writes)
            }
        }

        val mutationRuntime = object : MutationRuntime {
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
            ): PrivacyEvaluation<Entity> {
                events += "load-privacy"
                receivedContexts += viewerContext
                return privacyEvaluation(
                    subjects = entities,
                    decisions = entities.map {
                        loadDenial?.let { PrivacyDecision.Deny(it.reason) }
                            ?: PrivacyDecision.Allow
                    },
                )
            }
        }
        val mutationExecutor = MutationExecutor(driver, mutationRuntime)
        val operation = UpdateMutationOperation(
            entity = mapping,
            mutationRuntime = mutationRuntime,
            privacyEvaluator = privacyEvaluator,
            validationEvaluator = validationEvaluator,
            adapter = adapter,
            hooks = UpdateMutationHooks(
                converter = object :
                    UpdateMutationHookStateConverter<
                        Draft,
                        Widget,
                        PendingEdges,
                        BeforeSaveState,
                        BeforeUpdateState,
                    > {
                    override fun toBeforeSaveState(draft: Draft): BeforeSaveState =
                        BeforeSaveState("before-save:before")

                    override fun toBeforeUpdateState(
                        viewerContext: ViewerContext,
                        before: Widget,
                        pendingEdges: PendingEdges,
                        beforeSaveState: BeforeSaveState,
                    ): BeforeUpdateState {
                        receivedContexts += viewerContext
                        return BeforeUpdateState(
                            "before:${before.name}:${pendingEdges.description}",
                        )
                    }
                },
                beforeSave = MutationHookRunner(
                    lifecycle = "Widget.beforeSave",
                    hooks = listOf(
                        MutationHook { value: BeforeSaveState ->
                            events += value.description
                            value
                        },
                    ),
                ),
                beforeUpdate = MutationHookRunner(
                    lifecycle = "Widget.beforeUpdate",
                    hooks = listOf(
                        MutationHook { value: BeforeUpdateState ->
                            events += value.description
                            value
                        },
                    ),
                ),
                afterUpdate = HookRunner(
                    listOf(
                        Hook { entity ->
                            events += "after:${entity.name}"
                            afterAction(entity)
                        },
                    ),
                ),
            ),
        )

        fun execute(
            applyLoadPrivacy: Boolean,
            request: UpdateMutationRequest<Draft> = this.request,
            relationshipRequirements: UpdateRelationshipRequirements = UpdateRelationshipRequirements.None,
        ): MutationResult<Widget> {
            currentRelationshipRequirements = relationshipRequirements
            return mutationExecutor.execute(
                operation = operation,
                ruleClient = ruleClient,
                input = UpdateMutationInput(
                    viewerContext = viewerContext,
                    request = request,
                    applyLoadPrivacy = applyLoadPrivacy,
                ),
            )
        }
    }

    @Test
    fun `update operation owns lifecycle order and preserves supplied identities`() {
        val fixture = Fixture()

        val result = fixture.execute(applyLoadPrivacy = true)

        assertEquals(MutationResult.Success(Widget(1L, "after")), result)
        assertEquals(
            listOf(
                "transaction-state",
                "runtime-preflight:Widget update:false",
                "load-row",
                "decode:before",
                "capture-pending-edges",
                "before-save:before",
                "before:before:pending-edges",
                "prepare:before:pending-edges",
                "privacy:after",
                "validation:after",
                "update:1:after",
                "decode:after",
                "relationships:after",
                "after:after",
                "load-privacy",
            ),
            fixture.events,
        )
        assertTrue(fixture.receivedContexts.all { it === fixture.viewerContext })
        assertTrue(fixture.receivedClients.all { it === fixture.ruleClient })
    }

    @Test
    fun `pessimistic consistency is validated from the request before owner loading`() {
        val missingTransaction = Fixture()

        val transactionResult = missingTransaction.execute(
            applyLoadPrivacy = false,
            request = missingTransaction.request.copy(consistency = UpdateConsistency.Pessimistic),
        )

        val transactionFailure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(transactionResult).exception,
        )
        assertIs<TransactionRequiredException>(transactionFailure.cause)
        assertFalse("load-row" in missingTransaction.events)

        val missingCapability = Fixture(inTransaction = true)
        val capabilityResult = missingCapability.execute(
            applyLoadPrivacy = false,
            request = missingCapability.request.copy(consistency = UpdateConsistency.Pessimistic),
        )

        val capabilityFailure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(capabilityResult).exception,
        )
        assertIs<UnsupportedDriverCapabilityException>(capabilityFailure.cause)
        assertFalse("load-row" in missingCapability.events)
    }

    @Test
    fun `relationship requirements are enforced and canonical locks precede owner loading`() {
        val fixture = Fixture(
            inTransaction = true,
            supportsOwnerEdgeSerialization = true,
            supportsInsertIgnore = true,
            supportsRelationshipSerialization = true,
        )
        val requirements = UpdateRelationshipRequirements(
            hasPendingWrites = true,
            requiresInsertIgnore = true,
            canonicalLockKeys = listOf(
                RelationshipLockKey.canonical("widget_tags", listOf("widget_id", "tag_id")),
            ),
        )

        val result = fixture.execute(
            applyLoadPrivacy = false,
            request = fixture.request.copy(relationshipLocking = RelationshipLocking.Canonical),
            relationshipRequirements = requirements,
        )

        assertIs<MutationResult.Success<Widget>>(result)
        val relationshipLock = fixture.events.indexOf("serialize-relationship:widget_tags")
        val ownerLoad = fixture.events.indexOf("serialize-owner-edge-and-load")
        val beforeHook = fixture.events.indexOf("before:before:pending-edges")
        assertTrue(relationshipLock in 0 until ownerLoad && ownerLoad < beforeHook)
    }

    @Test
    fun `relationship owner loading prefers row locking and falls back to owner serialization`() {
        val requirements = UpdateRelationshipRequirements(
            hasPendingWrites = true,
            requiresInsertIgnore = false,
            canonicalLockKeys = emptyList(),
        )
        val rowLockFixture = Fixture(
            inTransaction = true,
            supportsReadRowForUpdate = true,
        )

        rowLockFixture.execute(false, relationshipRequirements = requirements)

        assertTrue("load-row-for-update" in rowLockFixture.events)
        assertFalse("serialize-owner-edge-and-load" in rowLockFixture.events)

        val ownerSerializationFixture = Fixture(
            inTransaction = true,
            supportsOwnerEdgeSerialization = true,
        )
        ownerSerializationFixture.execute(false, relationshipRequirements = requirements)

        assertTrue("serialize-owner-edge-and-load" in ownerSerializationFixture.events)
        assertFalse("load-row-for-update" in ownerSerializationFixture.events)
    }

    @Test
    fun `relationship capability failures occur before owner loading`() {
        val requirements = UpdateRelationshipRequirements(
            hasPendingWrites = true,
            requiresInsertIgnore = true,
            canonicalLockKeys = listOf(
                RelationshipLockKey.canonical("widget_tags", listOf("tag_id", "widget_id")),
            ),
        )
        val fixture = Fixture(
            inTransaction = true,
            supportsOwnerEdgeSerialization = true,
        )

        val result = fixture.execute(false, relationshipRequirements = requirements)

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertIs<UnsupportedDriverCapabilityException>(failure.cause)
        assertFalse(fixture.events.any {
            it == "load-row" || it == "load-row-for-update" || it == "serialize-owner-edge-and-load"
        })
    }

    @Test
    fun `no-op still evaluates rules and load privacy but skips every post-persist phase`() {
        val fixture = Fixture()
        fixture.preparation = UpdatePreparation.Ready(
            PreparedUpdate(State("before"), emptyMap(), isNoOp = true),
        )

        val result = fixture.execute(applyLoadPrivacy = true)

        assertEquals(MutationResult.Success(Widget(1L, "before")), result)
        assertTrue("privacy:before" in fixture.events)
        assertTrue("validation:before" in fixture.events)
        assertTrue("load-privacy" in fixture.events)
        assertFalse(fixture.events.any { it.startsWith("update:") || it.startsWith("relationships:") || it.startsWith("after:") })
        assertEquals("load-privacy", fixture.events.last())
    }

    @Test
    fun `missing target is typed and does not capture pending edges`() {
        val fixture = Fixture()
        fixture.loadedRow = null

        val result = fixture.execute(applyLoadPrivacy = false)

        val failure = assertIs<EntTargetAbsentException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(EntityKey("id", 1L), failure.key)
        assertSame(failure, fixture.failures.single())
        assertFalse("capture-pending-edges" in fixture.events)
    }

    @Test
    fun `generated preparation violations are typed`() {
        val fixture = Fixture()
        fixture.preparation = UpdatePreparation.Invalid(
            listOf(ValidationViolation("name is required", field = "name")),
        )

        val result = fixture.execute(applyLoadPrivacy = false)

        val failure = assertIs<EntValidationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals("name is required", failure.violations.single().message)
        assertFalse(fixture.events.any { it.startsWith("privacy:") || it.startsWith("update:") })
        assertEquals("record-failure", fixture.events.last())
    }

    @Test
    fun `privacy and entity validation reject before owner persistence`() {
        val privacyFixture = Fixture()
        privacyFixture.privacyDecision = PrivacyDecision.Deny("owner only")

        val privacyResult = privacyFixture.execute(applyLoadPrivacy = false)
        val privacyFailure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(privacyResult).exception,
        )
        assertEquals(EntOperation.UPDATE, privacyFailure.operation)
        assertEquals(MutationWriteState.NotPersisted, privacyFailure.writeState)
        assertFalse(privacyFixture.events.any { it.startsWith("validation:") || it.startsWith("update:") })

        val validationFixture = Fixture()
        validationFixture.invalids = listOf(ValidationDecision.Invalid("reserved", field = "name"))
        val validationResult = validationFixture.execute(applyLoadPrivacy = false)
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

        val result = fixture.execute(applyLoadPrivacy = false)

        assertSame(classified, assertIs<MutationResult.Failed>(result).exception)
        assertSame(classified, fixture.failures.single())
        assertEquals("record-failure", fixture.events.last())
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

        val result = fixture.execute(applyLoadPrivacy = false)

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

        val afterResult = afterFixture.execute(applyLoadPrivacy = false)
        val afterFailure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(afterResult).exception,
        )
        assertEquals(MutationWriteState.Committed, afterFailure.writeState)
        assertSame(hookFailure, afterFailure.cause)

        val loadFixture = Fixture(inTransaction = true)
        loadFixture.loadDenial = PrivacyDenial("Widget", EntityKey("id", 1L), "hidden")
        val loadResult = loadFixture.execute(applyLoadPrivacy = true)
        val loadFailure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(loadResult).exception,
        )
        assertEquals(EntOperation.LOAD, loadFailure.operation)
        assertEquals(MutationWriteState.TransactionPending, loadFailure.writeState)
    }

    @Test
    fun `cancellation propagates without failure recording`() {
        val fixture = Fixture()
        val cancellation = CancellationException("cancel")
        fixture.relationshipAction = { _, _ -> throw cancellation }

        val thrown = assertFailsWith<CancellationException> {
            fixture.execute(applyLoadPrivacy = false)
        }

        assertSame(cancellation, thrown)
        assertTrue(fixture.failures.isEmpty())
        assertEquals("relationships:after", fixture.events.last())
    }
}
