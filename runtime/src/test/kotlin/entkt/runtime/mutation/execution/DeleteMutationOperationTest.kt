@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.Hook
import entkt.runtime.hook.HookRunner
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.privacy.mutationPrivacyEvaluatorForInternalUse
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EntInterceptorsConfig
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.EntityQueryBuilder
import entkt.runtime.query.QuerySource
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.result.EntConflictException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
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

class DeleteMutationOperationTest {
    private data class Widget(
        override val id: Long,
        val name: String,
    ) : EntEntity.LongId

    private data class Candidate(val name: String)

    private class RecordingMapping(
        private val events: MutableList<String>,
    ) : EntityMapping<Widget> {
        override val entityName = "Widget"
        override val clientName = "widgets"
        override val entityClass = Widget::class
        override val table = "widgets"

        override fun decode(row: Map<String, Any?>): Widget {
            events += "decode"
            return Widget(row.getValue("id") as Long, row.getValue("name") as String)
        }

        override fun edgeByStorageName(storageName: String): EdgeMapping<Widget, *>? = null
    }

    private class RecordingDriver(
        private val events: MutableList<String>,
        private val transaction: Boolean,
    ) : DatabaseDriver by NoopDriver {
        var row: Map<String, Any?>? = mapOf("id" to 1L, "name" to "current")
        var deleteResult = true
        var deleteFailure: Exception? = null
        var deleteManyFailure: Exception? = null
        var classifiedFailure: EntMutationException? = null
        var acknowledgedIds: List<Any> = listOf(1L, 2L)
        var receivedPredicates: List<Predicate<*>>? = null
        var receivedQueryPredicates: List<Predicate<*>>? = null
        var queryRows: List<Map<String, Any?>> = listOf(
            mapOf("id" to 1L, "name" to "one"),
            mapOf("id" to 2L, "name" to "two"),
        )

        override val inTransaction: Boolean
            get() {
                events += "transaction-state"
                return transaction
            }

        override fun byId(table: String, id: Any): Map<String, Any?>? {
            events += "by-id:$id"
            return row
        }

        override fun delete(table: String, id: Any): Boolean {
            events += "delete:$id"
            deleteFailure?.let { throw it }
            return deleteResult
        }

        override fun deleteManyByIds(
            table: String,
            idColumn: String,
            ids: List<Any>,
            predicates: List<Predicate<*>>,
        ): List<Any> {
            events += "delete-many:${ids.joinToString()}"
            receivedPredicates = predicates
            deleteManyFailure?.let { throw it }
            return acknowledgedIds
        }

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            events += "query"
            receivedQueryPredicates = predicates
            return queryRows
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

    private class WidgetQuery(
        driver: DatabaseDriver,
        executionHost: ReadQueryExecutionHost,
        private val mapping: EntityMapping<Widget>,
    ) : EntityQueryBuilder<Widget, WidgetQuery>(
        driver = driver,
        executionHost = executionHost,
        entityName = mapping.entityName,
    ) {
        override val self: WidgetQuery
            get() = this

        override fun captureEntityQuery(
            structuralPredicates: List<Predicate<Widget>>,
        ): EntityQuery<Widget> = EntityQuery(
            entity = mapping,
            source = QuerySource.Root(),
            predicates = predicates,
            orderBy = orderFields,
            limit = queryLimit,
            offset = queryOffset,
            edges = emptyList(),
            structuralPredicates = structuralPredicates,
        )
    }

    private class Fixture(
        inTransaction: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val driver = RecordingDriver(events, inTransaction)
        val viewerContext = ViewerContext(Viewer.User(7L))
        val otherViewerContext = ViewerContext(Viewer.User(8L))
        val ruleClient = Any()
        val failures = mutableListOf<EntMutationException>()
        var privacyDecisions: List<PrivacyDecision> = emptyList()
        var validationDecisions: List<List<ValidationDecision.Invalid>> = emptyList()
        var effectivePredicates: List<Predicate<Widget>> = emptyList()
        val receivedViewerContexts = mutableListOf<ViewerContext>()
        val receivedReadOperations = mutableListOf<ReadOperation>()
        val receivedRuleClients = mutableListOf<Any>()
        val mapping = RecordingMapping(events)
        val queryHost = object : ReadQueryExecutionHost {
            override val entityInterceptors = EntInterceptorsConfig().apply {
                addEntity<Widget>(mapping.clientName, "delete-selection") { scope, context ->
                    events += "select"
                    receivedViewerContexts += context.viewerContext
                    receivedReadOperations += context.operation
                    effectivePredicates.forEach(scope::addPredicate)
                }
            }.resolveForInternalUse()

            override fun checkReadExecution() {
                events += "read-guard"
            }

            override fun isConfigured(entity: EntityMapping<*>): Boolean =
                error("DELETE candidate selection never evaluates LOAD privacy")

            override fun <Entity : EntEntity<*>> evaluate(
                entity: EntityMapping<Entity>,
                viewerContext: ViewerContext,
                entities: List<Entity>,
            ): PrivacyEvaluation<Entity> =
                error("DELETE candidate selection never evaluates LOAD privacy")
        }

        val converter = object : DeleteMutationConverter<Widget, Candidate> {
            override fun toCandidate(entity: Widget): Candidate {
                events += "candidate:${entity.id}"
                return Candidate(entity.name)
            }
        }

        val spec: DeleteMutationSpec<Widget> = DeleteMutationSpec(
            entity = mapping,
            idColumn = "id",
            newQuery = { WidgetQuery(driver, queryHost, mapping) },
            beforeDelete = HookRunner(listOf(Hook { entity -> events += "before:${entity.id}" })),
            afterDelete = HookRunner(listOf(Hook { entity -> events += "after:${entity.id}" })),
        )

        val privacyEvaluator = mutationPrivacyEvaluatorForInternalUse<
            Any,
            DeleteRuleCandidate<Widget, Candidate>,
            DeleteRuleCandidate<Widget, Candidate>,
            >(
            lifecycle = "Widget DELETE privacy",
            unresolvedReason = "no delete rule allowed access",
            rules = listOf(
                batchPrivacyRule<Any, DeleteRuleCandidate<Widget, Candidate>> { context, batch ->
                    events += "privacy:${batch.joinToString { it.entity.id.toString() }}"
                    receivedViewerContexts += context.viewerContext
                    receivedRuleClients += context.client
                    batch.decideEachIndexed { index, _ ->
                        privacyDecisions.getOrNull(index) ?: PrivacyDecision.Allow
                    }
                },
            ),
            ruleClientProvider = { ruleClient },
            freshItem = { it },
        )

        val validationEvaluator = mutationValidationEvaluatorForInternalUse<
            Any,
            DeleteRuleCandidate<Widget, Candidate>,
            DeleteRuleCandidate<Widget, Candidate>,
            >(
            lifecycle = "Widget DELETE validation",
            rules = listOf(
                batchValidationRule<Any, DeleteRuleCandidate<Widget, Candidate>> { context, batch ->
                    events += "validation:${batch.joinToString { it.entity.id.toString() }}"
                    receivedRuleClients += context.client
                    batch.decideEachIndexed { index, _ ->
                        validationDecisions.getOrNull(index)?.firstOrNull()
                            ?: ValidationDecision.Valid
                    }
                },
            ),
            ruleClientProvider = { ruleClient },
            freshItem = { it },
        )

        val mutationRuntime = object : MutationRuntime {
            override fun checkTransactionRequirement(operation: String, multiWrite: Boolean) {
                events += "preflight:$operation:$multiWrite"
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
            ): PrivacyEvaluation<Entity> = error("DELETE never evaluates LOAD privacy")
        }
        val mutationExecutor = MutationExecutor(driver, mutationRuntime)

        fun scalarOperation(
            spec: DeleteMutationSpec<Widget> = this.spec,
        ): DeleteMutationOperation<Widget, Candidate> = DeleteMutationOperation(
            spec = spec,
            converter = converter,
            privacyEvaluator = privacyEvaluator,
            validationEvaluator = validationEvaluator,
        )

        fun manyOperation(): DeleteManyMutationOperation<Widget, Candidate> = DeleteManyMutationOperation(
            spec = spec,
            converter = converter,
            privacyEvaluator = privacyEvaluator,
            validationEvaluator = validationEvaluator,
        )

        fun deleteById(
            viewerContext: ViewerContext,
            id: Any,
            spec: DeleteMutationSpec<Widget> = this.spec,
        ): MutationResult<Boolean> = mutationExecutor.execute(
            operation = scalarOperation(spec),
            input = DeleteMutationInput(viewerContext, id),
        )

        fun deleteMany(
            viewerContext: ViewerContext,
            predicates: List<Predicate<Widget>>,
        ): MutationResult<Int> = mutationExecutor.execute(
            operation = manyOperation(),
            input = DeleteManyMutationInput(viewerContext, predicates),
        )
    }

    @Test
    fun `scalar and bulk delete declare distinct transaction requirements`() {
        val fixture = Fixture()

        assertEquals(
            MutationRequirements("Widget delete"),
            fixture.scalarOperation().requirements(DeleteMutationInput(fixture.viewerContext, 1L)),
        )
        assertEquals(
            MutationRequirements("Widget deleteMany", multiWrite = true, requiresAtomicTransaction = true),
            fixture.manyOperation().requirements(
                DeleteManyMutationInput(fixture.viewerContext, emptyList()),
            ),
        )
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `scalar delete reloads current state and runs lifecycle in order`() {
        val fixture = Fixture()

        val result = fixture.deleteById(fixture.viewerContext, 1L)

        assertEquals(MutationResult.Success(true), result)
        assertEquals(
            listOf(
                "transaction-state",
                "preflight:Widget delete:false",
                "by-id:1",
                "decode",
                "candidate:1",
                "privacy:1",
                "validation:1",
                "before:1",
                "delete:1",
                "after:1",
            ),
            fixture.events,
        )
        assertEquals(listOf(fixture.viewerContext), fixture.receivedViewerContexts)
        assertTrue(fixture.receivedRuleClients.all { it === fixture.ruleClient })
    }

    @Test
    fun `missing scalar target is an idempotent success with no lifecycle callbacks`() {
        val fixture = Fixture()
        fixture.driver.row = null

        val result = fixture.deleteById(fixture.viewerContext, 42L)

        assertEquals(MutationResult.Success(false), result)
        assertEquals(
            listOf("transaction-state", "preflight:Widget delete:false", "by-id:42"),
            fixture.events,
        )
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `privacy denial stops before validation hooks and persistence`() {
        val fixture = Fixture()
        fixture.privacyDecisions = listOf(PrivacyDecision.Deny("owner only"))

        val result = fixture.deleteById(fixture.viewerContext, 1L)

        val failure = assertIs<MutationResult.Failed>(result).exception
        val denial = assertIs<EntMutationPrivacyDeniedException>(failure)
        assertEquals(EntOperation.DELETE, denial.operation)
        assertEquals("owner only", denial.reason)
        assertEquals(MutationWriteState.NotPersisted, denial.writeState)
        assertSame(denial, fixture.failures.single())
        assertFalse(fixture.events.any { it.startsWith("validation") || it.startsWith("before") || it.startsWith("delete:") })
    }

    @Test
    fun `validation failure is typed and records the same exception`() {
        val fixture = Fixture()
        fixture.validationDecisions = listOf(
            listOf(ValidationDecision.Invalid("still referenced", field = "id")),
        )

        val result = fixture.deleteById(fixture.viewerContext, 1L)

        val failure = assertIs<MutationResult.Failed>(result).exception
        val validation = assertIs<EntValidationException>(failure)
        assertEquals(EntOperation.DELETE, validation.operation)
        assertEquals("still referenced", validation.violations.single().message)
        assertSame(validation, fixture.failures.single())
        assertFalse(fixture.events.any { it.startsWith("before") || it.startsWith("delete:") })
    }

    @Test
    fun `driver classification is preserved by identity`() {
        val fixture = Fixture()
        val driverFailure = IllegalStateException("constraint")
        val classified = EntConflictException("Widget", EntOperation.DELETE, "conflict", "conflict", driverFailure)
        fixture.driver.deleteFailure = driverFailure
        fixture.driver.classifiedFailure = classified

        val result = fixture.deleteById(fixture.viewerContext, 1L)

        assertSame(classified, assertIs<MutationResult.Failed>(result).exception)
        assertSame(classified, fixture.failures.single())
    }

    @Test
    fun `after hook failure reports the actual scalar post-write posture`() {
        for ((inTransaction, expected) in listOf(
            false to MutationWriteState.Committed,
            true to MutationWriteState.TransactionPending,
        )) {
            val fixture = Fixture(inTransaction)
            val boom = IllegalStateException("after")
            val failingSpec: DeleteMutationSpec<Widget> = DeleteMutationSpec(
                entity = fixture.mapping,
                idColumn = "id",
                newQuery = fixture.spec.newQuery,
                beforeDelete = HookRunner(emptyList()),
                afterDelete = HookRunner(listOf(Hook<Widget> { throw boom })),
            )

            val result = fixture.deleteById(fixture.viewerContext, 1L, failingSpec)

            val failure = assertIs<EntUnexpectedMutationException>(
                assertIs<MutationResult.Failed>(result).exception,
            )
            assertEquals(expected, failure.writeState)
            assertSame(boom, failure.cause)
        }
    }

    @Test
    fun `bulk delete keeps the supplied context and frozen predicates through one phase-major write`() {
        val fixture = Fixture(inTransaction = true)
        val requested = Predicate.Leaf<Widget>("name", Op.EQ, "requested")
        val effective = Predicate.Leaf<Widget>("tenant_id", Op.EQ, 7L)
        fixture.effectivePredicates = listOf(effective)
        fixture.driver.acknowledgedIds = listOf(2L)

        val result = fixture.deleteMany(
            viewerContext = fixture.viewerContext,
            predicates = listOf(requested),
        )

        assertEquals(MutationResult.Success(1), result)
        assertEquals(
            listOf(
                "transaction-state",
                "preflight:Widget deleteMany:true",
                "read-guard",
                "select",
                "query",
                "decode",
                "decode",
                "candidate:1",
                "candidate:2",
                "privacy:1, 2",
                "validation:1, 2",
                "before:1",
                "before:2",
                "delete-many:1, 2",
                "after:2",
            ),
            fixture.events,
        )
        assertEquals(listOf(requested, effective), fixture.driver.receivedQueryPredicates)
        assertEquals(listOf(requested, effective), fixture.driver.receivedPredicates)
        assertEquals(listOf(ReadOperation.DELETE_CANDIDATES), fixture.receivedReadOperations)
        assertTrue(fixture.receivedViewerContexts.all { it === fixture.viewerContext })
        assertTrue(fixture.receivedRuleClients.all { it === fixture.ruleClient })
    }

    @Test
    fun `bulk delete promotes a statement-level failure after a multi-row attempt`() {
        val fixture = Fixture(inTransaction = true)
        val driverFailure = IllegalStateException("constraint")
        val classified = EntConflictException("Widget", EntOperation.DELETE, "conflict", "conflict", driverFailure)
        fixture.driver.deleteManyFailure = driverFailure
        fixture.driver.classifiedFailure = classified

        val result = fixture.deleteMany(
            fixture.viewerContext,
            emptyList(),
        )

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertEquals(MutationWriteState.TransactionPending, failure.writeState)
        assertSame(classified, failure.cause)
    }

    @Test
    fun `cancellation propagates without recording a mutation failure`() {
        val fixture = Fixture()
        val cancellation = CancellationException("cancel")
        fixture.driver.deleteFailure = cancellation

        val thrown = assertFailsWith<CancellationException> {
            fixture.deleteById(fixture.otherViewerContext, 1L)
        }

        assertSame(cancellation, thrown)
        assertTrue(fixture.failures.isEmpty())
    }
}
