@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.entity.EntityMapping
import entkt.runtime.hook.ActionHook
import entkt.runtime.hook.BatchActionHook
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.privacy.MutationPrivacyEvaluator
import entkt.runtime.privacy.PrivacyOperation
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EntInterceptorsConfig
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.query.execution.ReadQueryExecutor
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
import entkt.runtime.validation.MutationValidationEvaluator
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

    private class RecordingDescriptor(
        private val events: MutableList<String>,
        idColumn: String,
    ) : EntityDescriptor<Widget, Long> {
        override val entityName = "Widget"
        override val clientName = "widgets"
        override val entityClass = Widget::class
        override val schema = EntitySchema(
            table = "widgets",
            idColumn = idColumn,
            idStrategy = IdStrategy.EXPLICIT,
            columns = emptyList(),
            edges = emptyMap(),
        )
        override val edgesByStorageName: Map<String, EdgeMapping<Widget, *>> = emptyMap()

        override fun decode(row: Map<String, Any?>): Widget {
            events += "decode"
            return Widget(row.getValue(idColumn) as Long, row.getValue("name") as String)
        }
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
        var receivedIdColumn: String? = null
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
            receivedIdColumn = idColumn
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

    private class Fixture(
        inTransaction: Boolean = false,
        idColumn: String = "id",
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
        var readExecutionFailure: Exception? = null
        val receivedViewerContexts = mutableListOf<ViewerContext>()
        val receivedReadOperations = mutableListOf<ReadOperation>()
        val receivedRuleClients = mutableListOf<Any>()
        val mapping = RecordingDescriptor(events, idColumn)
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
                readExecutionFailure?.let { throw it }
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

        val beforeDelete = listOf(ActionHook<Widget> { entity -> events += "before:${entity.id}" })
        val afterDelete = listOf(ActionHook<Widget> { entity -> events += "after:${entity.id}" })

        val privacyEvaluator = MutationPrivacyEvaluator<
            Any,
            DeleteRuleCandidate<Widget, Candidate>,
            >(
            entity = mapping,
            operation = PrivacyOperation.DELETE,
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
        )

        val validationEvaluator = MutationValidationEvaluator<
            Any,
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
            beforeDelete: List<BatchActionHook<Widget>> = this.beforeDelete,
            afterDelete: List<BatchActionHook<Widget>> = this.afterDelete,
        ): DeleteMutationOperation<Any, Widget, Candidate> = DeleteMutationOperation(
            entity = mapping,
            converter = converter,
            privacyEvaluator = privacyEvaluator,
            validationEvaluator = validationEvaluator,
            beforeDelete = beforeDelete,
            afterDelete = afterDelete,
        )

        fun manyOperation(): DeleteManyMutationOperation<Any, Widget, Candidate> = DeleteManyMutationOperation(
            entity = mapping,
            converter = converter,
            privacyEvaluator = privacyEvaluator,
            validationEvaluator = validationEvaluator,
            readQueryExecutor = ReadQueryExecutor(driver, queryHost),
            beforeDelete = beforeDelete,
            afterDelete = afterDelete,
        )

        fun deleteById(
            viewerContext: ViewerContext,
            id: Any,
            beforeDelete: List<BatchActionHook<Widget>> = this.beforeDelete,
            afterDelete: List<BatchActionHook<Widget>> = this.afterDelete,
        ): MutationResult<Boolean> = mutationExecutor.execute(
            operation = scalarOperation(beforeDelete, afterDelete),
            ruleClient = ruleClient,
            input = DeleteMutationInput(viewerContext, id),
        )

        fun deleteMany(
            viewerContext: ViewerContext,
            predicates: List<Predicate<Widget>>,
        ): MutationResult<Int> = mutationExecutor.execute(
            operation = manyOperation(),
            ruleClient = ruleClient,
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

            val result = fixture.deleteById(
                fixture.viewerContext,
                1L,
                beforeDelete = emptyList(),
                afterDelete = listOf(ActionHook<Widget> { throw boom }),
            )

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
    fun `delete operations use the descriptor's non-default ID column`() {
        val fixture = Fixture(inTransaction = true, idColumn = "widget_id")
        fixture.driver.row = mapOf("widget_id" to 1L, "name" to "current")
        fixture.driver.queryRows = listOf(
            mapOf("widget_id" to 1L, "name" to "one"),
            mapOf("widget_id" to 2L, "name" to "two"),
        )

        assertEquals(MutationResult.Success(true), fixture.deleteById(fixture.viewerContext, 1L))
        assertEquals(MutationResult.Success(2), fixture.deleteMany(fixture.viewerContext, emptyList()))
        assertEquals("widget_id", fixture.driver.receivedIdColumn)
    }

    @Test
    fun `reusing a bulk delete operation does not retain predicates between calls`() {
        val fixture = Fixture(inTransaction = true)
        val operation = fixture.manyOperation()
        val firstPredicate = Predicate.Leaf<Widget>("name", Op.EQ, "first")
        val secondPredicate = Predicate.Leaf<Widget>("name", Op.EQ, "second")

        for (predicate in listOf(firstPredicate, secondPredicate)) {
            val result = fixture.mutationExecutor.execute(
                operation = operation,
                ruleClient = fixture.ruleClient,
                input = DeleteManyMutationInput(fixture.viewerContext, listOf(predicate)),
            )

            assertEquals(MutationResult.Success(2), result)
            assertEquals(listOf(predicate), fixture.driver.receivedQueryPredicates)
            assertEquals(listOf(predicate), fixture.driver.receivedPredicates)
        }
        assertEquals(2, fixture.events.count { it == "read-guard" })
        assertEquals(
            listOf(ReadOperation.DELETE_CANDIDATES, ReadOperation.DELETE_CANDIDATES),
            fixture.receivedReadOperations,
        )
    }

    @Test
    fun `bulk delete read guard rejects execution before selection or lifecycle work`() {
        val fixture = Fixture(inTransaction = true)
        val operation = fixture.manyOperation()
        val guardFailure = IllegalStateException("transaction client is no longer active")
        fixture.readExecutionFailure = guardFailure

        val result = fixture.mutationExecutor.execute(
            operation = operation,
            ruleClient = fixture.ruleClient,
            input = DeleteManyMutationInput(fixture.viewerContext, emptyList()),
        )

        val failure = assertIs<EntUnexpectedMutationException>(
            assertIs<MutationResult.Failed>(result).exception,
        )
        assertSame(guardFailure, failure.cause)
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(failure, fixture.failures.single())
        assertEquals(
            listOf(
                "transaction-state",
                "preflight:Widget deleteMany:true",
                "read-guard",
                "record-failure",
            ),
            fixture.events,
        )
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
