@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.Hook
import entkt.runtime.mutation.CreatePreparation
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.execution.LoadPrivacyEvaluation
import entkt.runtime.query.execution.correlateLoadPrivacyEvaluationsForInternalUse
import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ValidationViolation
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.batchValidationRule
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MutationExecutorTest {
    private data class Candidate(val name: String)

    private data class Widget(
        override val id: Long,
        val name: String,
    ) : EntEntity.LongId

    private class RecordingMapping(
        private val events: MutableList<String>,
    ) : EntityMapping<Widget> {
        override val entityName = "Widget"
        override val clientName = "widgets"
        override val entityClass = Widget::class
        override val table = "widgets"

        override fun decode(row: Map<String, Any?>): Widget {
            events += "decode"
            return Widget(
                id = row.getValue("id") as Long,
                name = row.getValue("name") as String,
            )
        }

        override fun edgeByStorageName(storageName: String): EdgeMapping<Widget, *>? = null
    }

    private class RecordingDriver(
        private val events: MutableList<String>,
        private val transaction: Boolean = false,
    ) : DatabaseDriver by NoopDriver {
        var insertedTable: String? = null
        var insertedValues: Map<String, Any?>? = null
        var insertedBatch: List<Map<String, Any?>>? = null
        var insertFailure: Exception? = null
        var classifiedFailure: EntMutationException? = null

        override val inTransaction: Boolean
            get() {
                events += "transaction-state"
                return transaction
            }

        override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
            events += "insert"
            insertedTable = table
            insertedValues = values
            insertFailure?.let { throw it }
            return values + ("id" to 1L)
        }

        override fun insertMany(
            table: String,
            values: List<Map<String, Any?>>,
        ): List<Map<String, Any?>> {
            events += "insert-many"
            insertedTable = table
            insertedBatch = values
            insertFailure?.let { throw it }
            return values.mapIndexed { index, row -> row + ("id" to (index + 1L)) }
        }

        override fun classifyMutationException(
            exception: Exception,
            entity: String,
            operation: EntOperation,
        ): EntMutationException? {
            events += "classify-driver-failure"
            return classifiedFailure
        }
    }

    private class RecordingSpec(
        private val events: MutableList<String>,
        entity: EntityMapping<Widget>,
    ) {
        var createDecision: PrivacyDecision = PrivacyDecision.Allow
        var validationViolations: List<ValidationViolation> = emptyList()
        var loadDenial: PrivacyDenial? = null
        var beforeCreateAction: () -> Unit = {}
        var afterCreateAction: (Widget) -> Unit = {}
        val receivedPrivacyContexts = mutableListOf<PrivacyContext>()

        val value: CreateMutationSpec<
            RecordingInput,
            String,
            String,
            Candidate,
            Candidate,
            Widget,
            Unit,
            Unit,
            > =
            CreateMutationSpec(
            entity = entity,
            resolveDraft = RecordingInput::resolve,
            beforeSave = listOf(Hook { value -> events += "before-save:$value" }),
            beforeCreate = listOf(
                Hook { value ->
                    events += "before-create:$value"
                    beforeCreateAction()
                },
            ),
            afterCreate = listOf(
                Hook { value ->
                    events += "after-create:${value.id}"
                    afterCreateAction(value)
                },
            ),
            privacyRules = listOf(
                batchPrivacyRule<Unit, Candidate> { context, batch ->
                    events += "create-privacy"
                    receivedPrivacyContexts += context.privacy
                    batch.decideEach { createDecision }
                },
            ),
            validationRules = listOf(
                batchValidationRule<Unit, Candidate> { _, batch ->
                    events += "validate"
                    batch.decideEach {
                        validationViolations.firstOrNull()?.let { violation ->
                            ValidationDecision.Invalid(
                                message = violation.message,
                                field = violation.field,
                                code = violation.code,
                            )
                        } ?: ValidationDecision.Valid
                    }
                },
            ),
            )
    }

    private class RecordingInput(
        private val events: MutableList<String>,
    ) {
        var preparation: CreatePreparation<Candidate, Candidate> = CreatePreparation.Ready(
            PreparedCreate(
                values = mapOf("name" to "Ada"),
                privacyItem = { Candidate("Ada") },
                validationItem = { Candidate("Ada") },
            ),
        )

        fun beforeSaveHookValue(): String {
            events += "before-save-value"
            return "save"
        }

        fun beforeCreateHookValue(): String {
            events += "before-create-value"
            return "create"
        }

        fun resolve(): CreatePreparation<Candidate, Candidate> {
            events += "prepare"
            return preparation
        }

        fun mutationInput(): CreateMutationInput<RecordingInput, String, String> =
            CreateMutationInput(
                draft = this,
                beforeSave = beforeSaveHookValue(),
                beforeCreate = beforeCreateHookValue(),
            )
    }

    @Test
    fun `mutation executor runs the create lifecycle in order and captures privacy once`() {
        val fixture = fixture()

        val result = fixture.executor.create(
            input = fixture.input.mutationInput(),
            spec = fixture.spec.value,
            checkReturnedEntityPrivacy = true,
        )

        assertEquals(MutationResult.Success(Widget(1, "Ada")), result)
        assertEquals("widgets", fixture.driver.insertedTable)
        assertEquals(mapOf("name" to "Ada"), fixture.driver.insertedValues)
        assertEquals(
            listOf(
                "before-save-value",
                "before-create-value",
                "transaction-state",
                "transaction-preflight:Widget create",
                "before-save:save",
                "before-create:create",
                "prepare",
                "privacy-context",
                "create-privacy",
                "validate",
                "insert",
                "decode",
                "after-create:1",
                "load-privacy",
            ),
            fixture.events,
        )
        assertEquals(2, fixture.spec.receivedPrivacyContexts.size)
        fixture.spec.receivedPrivacyContexts.forEach {
            assertSame(fixture.privacyContext, it)
        }
        assertTrue(fixture.recordedFailures.isEmpty())
    }

    @Test
    fun `preparation violations fail before privacy and persistence`() {
        val fixture = fixture()
        fixture.input.preparation = CreatePreparation.Invalid(
            listOf(ValidationViolation("name is required", field = "name")),
        )

        val result = fixture.executor.create(
            fixture.input.mutationInput(),
            fixture.spec.value,
            checkReturnedEntityPrivacy = true,
        )

        val failure = assertIs<MutationResult.Failed>(result).exception
        val validation = assertIs<EntValidationException>(failure)
        assertEquals(EntOperation.CREATE, validation.operation)
        assertEquals("name", validation.violations.single().field)
        assertFalse("privacy-context" in fixture.events)
        assertFalse("insert" in fixture.events)
        assertSame(failure, fixture.recordedFailures.single())
    }

    @Test
    fun `create privacy denial fails closed before validation and persistence`() {
        val fixture = fixture()
        fixture.spec.createDecision = PrivacyDecision.Deny("not yours")

        val result = fixture.executor.create(
            fixture.input.mutationInput(),
            fixture.spec.value,
            checkReturnedEntityPrivacy = true,
        )

        val failure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertEquals(EntOperation.CREATE, failure.operation)
        assertEquals(null, failure.entityKey)
        assertFalse("validate" in fixture.events)
        assertFalse("insert" in fixture.events)
        assertSame(failure, fixture.recordedFailures.single())
    }

    @Test
    fun `unresolved create privacy fails closed before validation and persistence`() {
        val fixture = fixture()
        fixture.spec.createDecision = PrivacyDecision.Continue

        val result = fixture.executor.create(
            fixture.input.mutationInput(),
            fixture.spec.value,
            checkReturnedEntityPrivacy = true,
        )

        val failure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals("no create rule allowed access", failure.reason)
        assertFalse("validate" in fixture.events)
        assertFalse("insert" in fixture.events)
    }

    @Test
    fun `unclassified insert failure reports an unknown persistence outcome`() {
        val fixture = fixture()
        val driverFailure = IllegalStateException("connection lost")
        fixture.driver.insertFailure = driverFailure

        val result = fixture.executor.create(
            fixture.input.mutationInput(),
            fixture.spec.value,
            checkReturnedEntityPrivacy = true,
        )

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.PersistenceUnknown, failure.writeState)
        assertSame(driverFailure, failure.cause)
        assertTrue("classify-driver-failure" in fixture.events)
        assertFalse("decode" in fixture.events)
        assertSame(failure, fixture.recordedFailures.single())
    }

    @Test
    fun `driver classification is returned without being rewrapped`() {
        val fixture = fixture()
        fixture.driver.insertFailure = IllegalStateException("conflict")
        val classified = EntConflictException(
            entityType = "Widget",
            operation = EntOperation.CREATE,
            code = "duplicate",
            message = "conflict",
        )
        fixture.driver.classifiedFailure = classified

        val result = fixture.executor.create(
            fixture.input.mutationInput(),
            fixture.spec.value,
            checkReturnedEntityPrivacy = true,
        )

        assertSame(classified, assertIs<MutationResult.Failed>(result).exception)
        assertSame(classified, fixture.recordedFailures.single())
    }

    @Test
    fun `post-write callback failures retain the transaction posture`() {
        for ((inTransaction, expectedState) in listOf(
            false to MutationWriteState.Committed,
            true to MutationWriteState.TransactionPending,
        )) {
            val fixture = fixture(inTransaction = inTransaction)
            val callbackFailure = IllegalStateException("after hook failed")
            fixture.spec.afterCreateAction = { throw callbackFailure }

            val result = fixture.executor.create(
                fixture.input.mutationInput(),
                fixture.spec.value,
                checkReturnedEntityPrivacy = true,
            )

            val failure = assertIs<EntUnexpectedMutationException>(
                assertIs<MutationResult.Failed>(result).exception,
            )
            assertEquals(expectedState, failure.writeState)
            assertSame(callbackFailure, failure.cause)
            assertFalse("load-privacy" in fixture.events)
            assertSame(failure, fixture.recordedFailures.single())
        }
    }

    @Test
    fun `returned load denial reports the persisted entity key and write state`() {
        val fixture = fixture()
        fixture.spec.loadDenial = PrivacyDenial(
            entityType = "Widget",
            entityKey = EntityKey("id", 1L),
            reason = "hidden",
        )

        val result = fixture.executor.create(
            fixture.input.mutationInput(),
            fixture.spec.value,
            checkReturnedEntityPrivacy = true,
        )

        val failure = assertIs<EntMutationPrivacyDeniedException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.Committed, failure.writeState)
        assertEquals(EntOperation.LOAD, failure.operation)
        assertEquals(EntityKey("id", 1L), failure.entityKey)
        assertSame(failure, fixture.recordedFailures.single())
    }

    @Test
    fun `acknowledgement-only create skips returned load privacy`() {
        val fixture = fixture()
        fixture.spec.loadDenial = PrivacyDenial(
            entityType = "Widget",
            entityKey = EntityKey("id", 1L),
            reason = "hidden",
        )

        val result = fixture.executor.create(
            fixture.input.mutationInput(),
            fixture.spec.value,
            checkReturnedEntityPrivacy = false,
        )

        assertEquals(MutationResult.Success(Widget(1, "Ada")), result)
        assertFalse("load-privacy" in fixture.events)
        assertTrue(fixture.recordedFailures.isEmpty())
    }

    @Test
    fun `createMany shares the phase-major create lifecycle and preserves builder order`() {
        val fixture = fixture(inTransaction = true)
        val secondInput = RecordingInput(
            fixture.events,
        ).apply {
            preparation = CreatePreparation.Ready(
                PreparedCreate(
                    values = mapOf("name" to "Grace"),
                    privacyItem = { Candidate("Grace") },
                    validationItem = { Candidate("Grace") },
                ),
            )
        }

        val result = fixture.executor.createMany(
            inputs = listOf(fixture.input.mutationInput(), secondInput.mutationInput()),
            spec = fixture.spec.value,
            promoteDriverNotPersisted = false,
        )

        val completion = assertIs<MutationResult.Success<CreateMutationOutput<Widget>>>(result).value
        assertEquals(
            listOf(Widget(1, "Ada"), Widget(2, "Grace")),
            completion.entities,
        )
        assertSame(fixture.privacyContext, completion.privacyContext)
        assertEquals(
            listOf(mapOf("name" to "Ada"), mapOf("name" to "Grace")),
            fixture.driver.insertedBatch,
        )
        assertEquals(
            listOf(
                "before-save-value",
                "before-create-value",
                "before-save-value",
                "before-create-value",
                "transaction-state",
                "before-save:save",
                "before-save:save",
                "before-create:create",
                "before-create:create",
                "prepare",
                "prepare",
                "privacy-context",
                "create-privacy",
                "validate",
                "insert-many",
                "decode",
                "decode",
                "after-create:1",
                "after-create:2",
            ),
            fixture.events,
        )
        assertTrue(fixture.recordedFailures.isEmpty())
    }

    @Test
    fun `createMany reports a before hook failure before preparation and persistence`() {
        val fixture = fixture(inTransaction = true)
        val callbackFailure = IllegalArgumentException("before create failed")
        fixture.spec.beforeCreateAction = { throw callbackFailure }

        val result = fixture.executor.createMany(
            inputs = listOf(fixture.input.mutationInput()),
            spec = fixture.spec.value,
            promoteDriverNotPersisted = false,
        )

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(callbackFailure, failure.cause)
        assertSame(failure, fixture.recordedFailures.single())
        assertFalse("prepare" in fixture.events)
        assertFalse("insert-many" in fixture.events)
    }

    @Test
    fun `cancellation is rethrown without recording a mutation failure`() {
        val fixture = fixture()
        val cancellation = CancellationException("cancelled")
        fixture.spec.beforeCreateAction = { throw cancellation }

        val thrown = assertFailsWith<CancellationException> {
            fixture.executor.create(
                fixture.input.mutationInput(),
                fixture.spec.value,
                checkReturnedEntityPrivacy = true,
            )
        }

        assertSame(cancellation, thrown)
        assertTrue(fixture.recordedFailures.isEmpty())
        assertFalse("prepare" in fixture.events)
    }

    private fun fixture(inTransaction: Boolean = false): Fixture {
        val events = mutableListOf<String>()
        val driver = RecordingDriver(events, inTransaction)
        val mapping = RecordingMapping(events)
        val spec = RecordingSpec(events, mapping)
        val input = RecordingInput(events)
        val privacyContext = PrivacyContext(Viewer.User(7L))
        val recordedFailures = mutableListOf<EntMutationException>()
        val executor = MutationExecutor(
            driver = driver,
            mutationRuntime = object : MutationRuntime<Unit, Unit> {
                override fun get(): PrivacyContext {
                    events += "privacy-context"
                    return privacyContext
                }

                override fun checkTransactionRequirement(operation: String, multiWrite: Boolean) {
                    events += "transaction-preflight:$operation"
                }

                override fun recordTransactionMutationFailure(exception: EntMutationException) {
                    events += "record-failure"
                    recordedFailures += exception
                }

                override fun privacyRuleClient(privacyContext: PrivacyContext) = Unit

                override fun validationRuleClient() = Unit

                override fun isConfigured(entity: EntityMapping<*>): Boolean = true

                override fun <Entity : EntEntity<*>> evaluate(
                    entity: EntityMapping<Entity>,
                    privacyContext: PrivacyContext,
                    entities: List<Entity>,
                ): List<LoadPrivacyEvaluation<Entity>> {
                    events += "load-privacy"
                    spec.receivedPrivacyContexts += privacyContext
                    return correlateLoadPrivacyEvaluationsForInternalUse(
                        lifecycle = "Widget LOAD privacy",
                        entities = entities,
                        denials = entities.map { spec.loadDenial },
                    )
                }
            },
        )
        return Fixture(
            events = events,
            driver = driver,
            spec = spec,
            input = input,
            executor = executor,
            privacyContext = privacyContext,
            recordedFailures = recordedFailures,
        )
    }

    private class Fixture(
        val events: MutableList<String>,
        val driver: RecordingDriver,
        val spec: RecordingSpec,
        val input: RecordingInput,
        val executor: MutationExecutor<Unit, Unit>,
        val privacyContext: PrivacyContext,
        val recordedFailures: MutableList<EntMutationException>,
    )
}
