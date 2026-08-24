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
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.EdgeMapping
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
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MutationEvaluatorTest {
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
        var createDenial: String? = null
        var validationViolations: List<ValidationViolation> = emptyList()
        var loadDenial: PrivacyDenial? = null
        var beforeCreateAction: () -> Unit = {}
        var afterCreateAction: (Widget) -> Unit = {}
        val receivedPrivacyContexts = mutableListOf<PrivacyContext>()

        val value = CreateMutationSpec(
            entity = entity,
            beforeSaveHooks = listOf(Hook { value -> events += "before-save:$value" }),
            beforeCreateHooks = listOf(
                Hook { value ->
                    events += "before-create:$value"
                    beforeCreateAction()
                },
            ),
            afterCreateHooks = listOf(
                Hook { value ->
                    events += "after-create:${value.id}"
                    afterCreateAction(value)
                },
            ),
            beforeSaveHookValue = RecordingInput::beforeSaveHookValue,
            beforeCreateHookValue = RecordingInput::beforeCreateHookValue,
            resolve = RecordingInput::resolve,
            createDenialReasons = { privacy, candidates ->
                events += "create-privacy"
                receivedPrivacyContexts += privacy
                candidates.map { createDenial }
            },
            validationViolations = { candidates ->
                events += "validate"
                candidates.map { validationViolations }
            },
            loadDenials = { privacy, entities ->
                events += "load-privacy"
                receivedPrivacyContexts += privacy
                entities.map { loadDenial }
            },
        )
    }

    private class RecordingInput(
        private val events: MutableList<String>,
    ) {
        var preparation: CreatePreparation<Candidate> = CreatePreparation.Ready(
            PreparedCreate(
                values = mapOf("name" to "Ada"),
                candidate = Candidate("Ada"),
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

        fun resolve(): CreatePreparation<Candidate> {
            events += "prepare"
            return preparation
        }
    }

    @Test
    fun `runs the create lifecycle in order and captures privacy once`() {
        val fixture = fixture()

        val result = fixture.evaluator.create(
            draft = fixture.input,
            spec = fixture.spec.value,
            checkReturnedEntityPrivacy = true,
        )

        assertEquals(MutationResult.Success(Widget(1, "Ada")), result)
        assertEquals("widgets", fixture.driver.insertedTable)
        assertEquals(mapOf("name" to "Ada"), fixture.driver.insertedValues)
        assertEquals(
            listOf(
                "transaction-state",
                "transaction-preflight:Widget create",
                "before-save-value",
                "before-save:save",
                "before-create-value",
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

        val result = fixture.evaluator.create(
            fixture.input,
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
        fixture.spec.createDenial = "not yours"

        val result = fixture.evaluator.create(
            fixture.input,
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
    fun `unclassified insert failure reports an unknown persistence outcome`() {
        val fixture = fixture()
        val driverFailure = IllegalStateException("connection lost")
        fixture.driver.insertFailure = driverFailure

        val result = fixture.evaluator.create(
            fixture.input,
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

        val result = fixture.evaluator.create(
            fixture.input,
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

            val result = fixture.evaluator.create(
                fixture.input,
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

        val result = fixture.evaluator.create(
            fixture.input,
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

        val result = fixture.evaluator.create(
            fixture.input,
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
                    candidate = Candidate("Grace"),
                ),
            )
        }

        val result = fixture.evaluator.createMany(
            drafts = listOf(fixture.input, secondInput),
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
                "transaction-state",
                "before-save-value",
                "before-save-value",
                "before-save:save",
                "before-save:save",
                "before-create-value",
                "before-create-value",
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

        val result = fixture.evaluator.createMany(
            drafts = listOf(fixture.input),
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
            fixture.evaluator.create(
                fixture.input,
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
        val evaluator = MutationEvaluator(
            driver = driver,
            mutationRuntime = object : MutationRuntime {
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

            },
        )
        return Fixture(
            events = events,
            driver = driver,
            spec = spec,
            input = input,
            evaluator = evaluator,
            privacyContext = privacyContext,
            recordedFailures = recordedFailures,
        )
    }

    private class Fixture(
        val events: MutableList<String>,
        val driver: RecordingDriver,
        val spec: RecordingSpec,
        val input: RecordingInput,
        val evaluator: MutationEvaluator,
        val privacyContext: PrivacyContext,
        val recordedFailures: MutableList<EntMutationException>,
    )
}
