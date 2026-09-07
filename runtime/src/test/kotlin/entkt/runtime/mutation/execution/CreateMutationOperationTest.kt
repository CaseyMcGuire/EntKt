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
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.privacyEvaluation
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.PrivacyOperation
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

class CreateMutationOperationTest {
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
    ) {
        var createDecision: PrivacyDecision = PrivacyDecision.Allow
        var schemaFieldViolations: List<ValidationViolation> = emptyList()
        var validationViolations: List<ValidationViolation> = emptyList()
        var loadDenial: PrivacyDenial? = null
        var loadFailure: Exception? = null
        var beforeCreateAction: () -> Unit = {}
        var afterCreateAction: (Widget) -> Unit = {}
        val receivedViewerContexts = mutableListOf<ViewerContext>()

        val converter = object : CreateMutationConverter<RecordingInput, Candidate, Widget> {
            override fun requiredInputViolations(draft: RecordingInput): List<ValidationViolation> =
                draft.requiredInputViolations()

            override fun resolve(draft: RecordingInput): PreparedCreate<Candidate> = draft.resolve()

            override fun fieldViolations(candidate: Candidate): List<ValidationViolation> {
                events += "field-validation"
                return schemaFieldViolations
            }
        }

        val privacyEvaluator = MutationPrivacyEvaluator<Unit, Candidate, Candidate>(
                entity = RecordingMapping(events),
                operation = PrivacyOperation.CREATE,
                rules = listOf(
                    batchPrivacyRule<Unit, Candidate> { context, batch ->
                        events += "create-privacy"
                        receivedViewerContexts += context.viewerContext
                        batch.decideEach { createDecision }
                    },
                ),
                freshItem = { it },
            )

        val validationEvaluator = mutationValidationEvaluatorForInternalUse<Unit, Candidate, Candidate>(
                lifecycle = "Widget CREATE validation",
                rules = listOf(
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
                freshItem = { it },
            )
    }

    private class RecordingInput(
        private val events: MutableList<String>,
    ) : CreateMutationDraft<Widget> {
        var requiredViolations: List<ValidationViolation> = emptyList()
        var prepared: PreparedCreate<Candidate> = PreparedCreate(
            values = mapOf("name" to "Ada"),
            candidate = Candidate("Ada"),
        )

        fun beforeSaveHookValue(): String {
            events += "before-save-value"
            return "save"
        }

        fun beforeCreateHookValue(): String {
            events += "before-create-value"
            return "create"
        }

        fun requiredInputViolations(): List<ValidationViolation> {
            events += "required-input"
            return requiredViolations
        }

        fun resolve(): PreparedCreate<Candidate> {
            events += "prepare"
            return prepared
        }
    }

    @Test
    fun `mutation executor threads the supplied viewer context through the create lifecycle`() {
        val fixture = fixture()

        val result = fixture.create(
            viewerContext = fixture.viewerContext,
            draft = fixture.input,
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
                "required-input",
                "prepare",
                "field-validation",
                "create-privacy",
                "validate",
                "insert",
                "decode",
                "after-create:1",
                "load-privacy",
            ),
            fixture.events,
        )
        assertEquals(2, fixture.spec.receivedViewerContexts.size)
        fixture.spec.receivedViewerContexts.forEach {
            assertSame(fixture.viewerContext, it)
        }
        assertTrue(fixture.recordedFailures.isEmpty())
    }

    @Test
    fun `required input violations fail before resolution privacy and persistence`() {
        val fixture = fixture()
        fixture.input.requiredViolations = listOf(
            ValidationViolation("name is required", field = "name"),
        )

        val result = fixture.create(
            fixture.viewerContext,
            fixture.input,
            checkReturnedEntityPrivacy = true,
        )

        val failure = assertIs<MutationResult.Failed>(result).exception
        val validation = assertIs<EntValidationException>(failure)
        assertEquals(EntOperation.CREATE, validation.operation)
        assertEquals("name", validation.violations.single().field)
        assertFalse("prepare" in fixture.events)
        assertFalse("insert" in fixture.events)
        assertSame(failure, fixture.recordedFailures.single())
    }

    @Test
    fun `field violations fail after resolution and before privacy and persistence`() {
        val fixture = fixture()
        fixture.spec.schemaFieldViolations = listOf(
            ValidationViolation("name must not be empty", field = "name"),
        )

        val result = fixture.create(
            fixture.viewerContext,
            fixture.input,
            checkReturnedEntityPrivacy = true,
        )

        val failure = assertIs<MutationResult.Failed>(result).exception
        val validation = assertIs<EntValidationException>(failure)
        assertEquals(EntOperation.CREATE, validation.operation)
        assertEquals("name", validation.violations.single().field)
        assertTrue("prepare" in fixture.events)
        assertFalse("create-privacy" in fixture.events)
        assertFalse("insert" in fixture.events)
        assertSame(failure, fixture.recordedFailures.single())
    }

    @Test
    fun `create privacy denial fails closed before validation and persistence`() {
        val fixture = fixture()
        fixture.spec.createDecision = PrivacyDecision.Deny("not yours")

        val result = fixture.create(
            fixture.viewerContext,
            fixture.input,
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

        val result = fixture.create(
            fixture.viewerContext,
            fixture.input,
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

        val result = fixture.create(
            fixture.viewerContext,
            fixture.input,
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

        val result = fixture.create(
            fixture.viewerContext,
            fixture.input,
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

            val result = fixture.create(
                fixture.viewerContext,
                fixture.input,
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

        val result = fixture.create(
            fixture.viewerContext,
            fixture.input,
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

        val result = fixture.create(
            fixture.viewerContext,
            fixture.input,
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
            prepared = PreparedCreate(
                values = mapOf("name" to "Grace"),
                candidate = Candidate("Grace"),
            )
        }

        val result = fixture.createMany(
            viewerContext = fixture.viewerContext,
            drafts = listOf(fixture.input, secondInput),
        )

        val completion = assertIs<MutationResult.Success<List<Widget>>>(result).value
        assertEquals(
            listOf(Widget(1, "Ada"), Widget(2, "Grace")),
            completion,
        )
        assertEquals(
            listOf(mapOf("name" to "Ada"), mapOf("name" to "Grace")),
            fixture.driver.insertedBatch,
        )
        assertEquals(
            listOf(
                "transaction-state",
                "transaction-preflight:Widget createMany",
                "construct-draft",
                "construct-draft",
                "before-save-value",
                "before-save-value",
                "before-save:save",
                "before-save:save",
                "before-create-value",
                "before-create-value",
                "before-create:create",
                "before-create:create",
                "required-input",
                "required-input",
                "prepare",
                "prepare",
                "field-validation",
                "field-validation",
                "create-privacy",
                "validate",
                "insert-many",
                "decode",
                "decode",
                "after-create:1",
                "after-create:2",
                "load-privacy",
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

        val result = fixture.createMany(
            viewerContext = fixture.viewerContext,
            drafts = listOf(fixture.input),
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
    fun `scalar and single-element bulk create retain distinct result and storage contracts`() {
        val scalar = fixture()
        val scalarResult: MutationResult<Widget> = scalar.create(scalar.viewerContext, scalar.input, true)
        assertEquals(MutationResult.Success(Widget(1, "Ada")), scalarResult)
        assertTrue("insert" in scalar.events)
        assertFalse("insert-many" in scalar.events)

        val bulk = fixture(inTransaction = true)
        val bulkResult: MutationResult<List<Widget>> = bulk.createMany(bulk.viewerContext, listOf(bulk.input))
        assertEquals(MutationResult.Success(listOf(Widget(1, "Ada"))), bulkResult)
        assertTrue("insert-many" in bulk.events)
        assertFalse("insert" in bulk.events)
        assertEquals(1, bulk.events.count { it == "construct-draft" })
    }

    @Test
    fun `bulk invocation captures its initializers before the caller changes the list`() {
        val fixture = fixture(inTransaction = true)
        val blocks = mutableListOf<RecordingInput.() -> Unit>({})
        val input = fixture.createManyInput(fixture.viewerContext, blocks)
        blocks.clear()

        val result = fixture.mutationExecutor.execute(fixture.manyOperation, input, Unit)

        assertEquals(MutationResult.Success(listOf(Widget(1, "Ada"))), result)
        assertEquals(1, fixture.events.count { it == "construct-draft" })
    }

    @Test
    fun `requirements distinguish caller policy from batch atomicity`() {
        val fixture = fixture()
        val blocks: List<RecordingInput.() -> Unit> = listOf({}, {})

        assertEquals(
            MutationRequirements("Widget create"),
            fixture.operation.requirements(CreateMutationInput(fixture.viewerContext, fixture.input, true)),
        )
        assertEquals(
            MutationRequirements("Widget createMany"),
            fixture.manyOperation.requirements(fixture.createManyInput(fixture.viewerContext, emptyList())),
        )
        assertEquals(
            MutationRequirements("Widget createMany", multiWrite = false, requiresAtomicTransaction = true),
            fixture.manyOperation.requirements(fixture.createManyInput(fixture.viewerContext, blocks.take(1))),
        )
        assertEquals(
            MutationRequirements("Widget createMany", multiWrite = true, requiresAtomicTransaction = true),
            fixture.manyOperation.requirements(fixture.createManyInput(fixture.viewerContext, blocks)),
        )
    }

    @Test
    fun `empty createMany checks application policy but constructs no drafts or transaction`() {
        val fixture = fixture()

        assertEquals(MutationResult.Success(emptyList()), fixture.createMany(fixture.viewerContext, emptyList()))
        assertEquals(listOf("transaction-state", "transaction-preflight:Widget createMany"), fixture.events)
    }

    @Test
    fun `bulk draft constructor failures remain inside the execution boundary`() {
        val fixture = fixture(inTransaction = true)
        val cause = IllegalStateException("draft construction failed")
        val input = CreateManyMutationInput<RecordingInput>(
            viewerContext = fixture.viewerContext,
            blocks = listOf({}),
            newDraft = { throw cause },
        )

        val result = fixture.mutationExecutor.execute(fixture.manyOperation, input, Unit)

        val failure = assertIs<EntUnexpectedMutationException>(assertIs<MutationResult.Failed>(result).exception)
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(cause, failure.cause)
        assertSame(failure, fixture.recordedFailures.single())
        assertFalse("before-save-value" in fixture.events)
        assertFalse("insert-many" in fixture.events)
    }

    @Test
    fun `a failing draft block is captured before hooks or persistence`() {
        val fixture = fixture(inTransaction = true)
        val cause = IllegalStateException("invalid draft")
        val blocks: List<RecordingInput.() -> Unit> = listOf({}, { throw cause })

        val result = fixture.mutationExecutor.execute(
            operation = fixture.manyOperation,
            ruleClient = Unit,
            input = fixture.createManyInput(fixture.viewerContext, blocks),
        )

        val failure = assertIs<EntUnexpectedMutationException>(assertIs<MutationResult.Failed>(result).exception)
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(cause, failure.cause)
        assertEquals(2, fixture.events.count { it == "construct-draft" })
        assertFalse("before-save-value" in fixture.events)
        assertFalse("insert-many" in fixture.events)
    }

    @Test
    fun `owned createMany evaluates returned privacy inside the operation without recording a failure`() {
        val fixture = fixture(inTransaction = true)
        fixture.spec.loadDenial = PrivacyDenial("Widget", EntityKey("id", 1L), "hidden")
        val blocks: List<RecordingInput.() -> Unit> = listOf({})
        val capture = MutationCompletionCapture()

        val result = fixture.mutationExecutor.executeInOwnedTransactionForInternalUse(
            operation = fixture.manyOperation,
            ruleClient = Unit,
            input = fixture.createManyInput(fixture.viewerContext, blocks),
            completionCapture = capture,
        )

        val completion = assertIs<MutationResult.Success<MutationCompletion<List<Widget>>>>(result).value
        val denial = assertIs<MutationCompletion.ReturnDenied>(completion).denial
        assertEquals(EntityKey("id", 1L), denial.entityKey)
        assertSame(denial, capture.denial)
        assertEquals(MutationWriteState.TransactionPending, capture.writeState)
        assertEquals("load-privacy", fixture.events.last())
        assertTrue(fixture.spec.receivedViewerContexts.all { it === fixture.viewerContext })
        assertTrue(fixture.recordedFailures.isEmpty())
    }

    @Test
    fun `batch storage failure classification uses execution ownership instead of an input flag`() {
        for (owned in listOf(false, true)) {
            val fixture = fixture(inTransaction = true)
            val rejection = EntConflictException("Widget", EntOperation.CREATE, "conflict", "conflict")
            fixture.driver.insertFailure = IllegalStateException("constraint")
            fixture.driver.classifiedFailure = rejection
            val blocks: List<RecordingInput.() -> Unit> = listOf({}, {})
            val input = fixture.createManyInput(fixture.viewerContext, blocks)

            val result = if (owned) {
                fixture.mutationExecutor.executeInOwnedTransactionForInternalUse(
                    fixture.manyOperation, input, Unit, MutationCompletionCapture(),
                )
            } else {
                fixture.mutationExecutor.execute(fixture.manyOperation, input, Unit)
            }

            val failure = assertIs<MutationResult.Failed>(result).exception
            if (owned) {
                assertSame(rejection, failure)
            } else {
                assertEquals(MutationWriteState.TransactionPending, failure.writeState)
                assertSame(rejection, assertIs<EntUnexpectedMutationException>(failure).cause)
            }
        }
    }

    @Test
    fun `returned privacy exceptions are completion failures but cancellation still escapes`() {
        val fixture = fixture()
        val cause = IllegalStateException("LOAD query failed")
        fixture.spec.loadFailure = cause

        val result = fixture.create(fixture.viewerContext, fixture.input, true)

        val failure = assertIs<EntUnexpectedMutationException>(assertIs<MutationResult.Failed>(result).exception)
        assertEquals(MutationWriteState.Committed, failure.writeState)
        assertSame(cause, failure.cause)

        val cancelled = fixture()
        val cancellation = CancellationException("LOAD cancelled")
        cancelled.spec.loadFailure = cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> {
            cancelled.create(cancelled.viewerContext, cancelled.input, true)
        })
        assertTrue(cancelled.recordedFailures.isEmpty())
    }

    @Test
    fun `cancellation is rethrown without recording a mutation failure`() {
        val fixture = fixture()
        val cancellation = CancellationException("cancelled")
        fixture.spec.beforeCreateAction = { throw cancellation }

        val thrown = assertFailsWith<CancellationException> {
            fixture.create(
                fixture.viewerContext,
                fixture.input,
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
        val spec = RecordingSpec(events)
        val input = RecordingInput(events)
        val viewerContext = ViewerContext(Viewer.User(7L))
        val recordedFailures = mutableListOf<EntMutationException>()
        val mutationRuntime = object : MutationRuntime {
            override fun checkTransactionRequirement(operation: String, multiWrite: Boolean) {
                events += "transaction-preflight:$operation"
            }

            override fun recordTransactionMutationFailure(exception: EntMutationException) {
                events += "record-failure"
                recordedFailures += exception
            }

            override fun isConfigured(entity: EntityMapping<*>): Boolean = true

            override fun <Entity : EntEntity<*>> evaluate(
                entity: EntityMapping<Entity>,
                viewerContext: ViewerContext,
                entities: List<Entity>,
            ): PrivacyEvaluation<Entity> {
                events += "load-privacy"
                spec.loadFailure?.let { throw it }
                spec.receivedViewerContexts += viewerContext
                return privacyEvaluation(
                    subjects = entities,
                    decisions = entities.map {
                        spec.loadDenial?.let { PrivacyDecision.Deny(it.reason) }
                            ?: PrivacyDecision.Allow
                    },
                )
            }
        }
        val mutationExecutor = MutationExecutor(driver, mutationRuntime)
        val manyOperation = CreateManyMutationOperation(
            mutationRuntime = mutationRuntime,
            entity = mapping,
            converter = spec.converter,
            privacyEvaluator = spec.privacyEvaluator,
            validationEvaluator = spec.validationEvaluator,
            hookStateConverter = object :
                CreateMutationHookStateConverter<RecordingInput, String, String> {
                override fun toBeforeSaveState(draft: RecordingInput): String =
                    draft.beforeSaveHookValue()

                override fun toBeforeCreateState(
                    viewerContext: ViewerContext,
                    draft: RecordingInput,
                    beforeSaveState: String,
                ): String = draft.beforeCreateHookValue()

                override fun toPreparationDraft(
                    originalDraft: RecordingInput,
                    state: String,
                ): RecordingInput = originalDraft
            },
            beforeSaveHookRunner = MutationHookRunner(
                lifecycle = "Widget.beforeSave",
                hooks = listOf(
                    MutationHook { value: String ->
                        events += "before-save:$value"
                        value
                    },
                ),
            ),
            beforeCreateHookRunner = MutationHookRunner(
                lifecycle = "Widget.beforeCreate",
                hooks = listOf(
                    MutationHook { value: String ->
                        events += "before-create:$value"
                        spec.beforeCreateAction()
                        value
                    },
                ),
            ),
            afterCreateHookRunner = HookRunner(
                listOf(
                    Hook { value ->
                        events += "after-create:${value.id}"
                        spec.afterCreateAction(value)
                    },
                ),
            ),
        )
        return Fixture(
            events = events,
            driver = driver,
            spec = spec,
            input = input,
            mutationExecutor = mutationExecutor,
            operation = CreateMutationOperation(manyOperation),
            manyOperation = manyOperation,
            viewerContext = viewerContext,
            recordedFailures = recordedFailures,
        )
    }

    private class Fixture(
        val events: MutableList<String>,
        val driver: RecordingDriver,
        val spec: RecordingSpec,
        val input: RecordingInput,
        val mutationExecutor: MutationExecutor,
        val operation: CreateMutationOperation<Unit, RecordingInput, Candidate, Widget, String, String>,
        val manyOperation: CreateManyMutationOperation<Unit, RecordingInput, Candidate, Widget, String, String>,
        val viewerContext: ViewerContext,
        val recordedFailures: MutableList<EntMutationException>,
    ) {
        fun create(
            viewerContext: ViewerContext,
            draft: RecordingInput,
            checkReturnedEntityPrivacy: Boolean,
        ): MutationResult<Widget> = mutationExecutor.execute(
            operation = operation,
            ruleClient = Unit,
            input = CreateMutationInput(viewerContext, draft, checkReturnedEntityPrivacy),
        )

        fun createManyInput(
            viewerContext: ViewerContext,
            blocks: List<RecordingInput.() -> Unit>,
        ): CreateManyMutationInput<RecordingInput> = CreateManyMutationInput(
            viewerContext,
            blocks,
            newDraft = {
                events += "construct-draft"
                RecordingInput(events)
            },
        )

        fun createMany(
            viewerContext: ViewerContext,
            drafts: List<RecordingInput>,
        ): MutationResult<List<Widget>> {
            val blocks: List<RecordingInput.() -> Unit> = drafts.map { draft ->
                {
                    requiredViolations = draft.requiredViolations
                    prepared = draft.prepared
                }
            }
            return mutationExecutor.execute(
                operation = manyOperation,
                ruleClient = Unit,
                input = createManyInput(viewerContext, blocks),
            )
        }
    }
}
