@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.repository

import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.entity.EntityMapping
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.UpdateConsistency
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.execution.CreateManyMutationInput
import entkt.runtime.mutation.execution.CreateMutationInput
import entkt.runtime.mutation.execution.DeleteManyMutationInput
import entkt.runtime.mutation.execution.DeleteMutationInput
import entkt.runtime.mutation.execution.MutationCompletion
import entkt.runtime.mutation.execution.MutationExecution
import entkt.runtime.mutation.execution.MutationExecutor
import entkt.runtime.mutation.execution.MutationOperation
import entkt.runtime.mutation.execution.MutationRequirements
import entkt.runtime.mutation.execution.MutationRuntime
import entkt.runtime.mutation.execution.UpdateMutationInput
import entkt.runtime.privacy.LoadPrivacyEvaluator
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacyEvaluation
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EntInterceptorsConfig
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.EntityQueryBuilder
import entkt.runtime.query.QuerySource
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.result.EntMutationAlreadyConsumedException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionCoordinator
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.TransactionScope
import entkt.runtime.result.runEntTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntityRepositoryTest {
    private val viewerContext = ViewerContext(Viewer.User(7L))

    @Test
    fun `construction does not resolve the rule client or execute operations`() {
        val fixture = Fixture()

        fixture.generated
        fixture.explicit

        assertEquals(0, fixture.clientResolutions)
        assertTrue(fixture.calls.isEmpty())
        assertTrue(fixture.preflights.isEmpty())
    }

    @Test
    fun `generated ID create stays configurable and binds execution without a repository callback`() {
        val fixture = Fixture()
        val client = fixture.client
        val pending = fixture.generated.create { name = "first" }
        pending.configure { name += " second" }
        fixture.client = RuleClient("replacement")
        val resolutions = fixture.clientResolutions

        assertEquals(MutationResult.Success(Widget(1L, "first second")), pending.saveAndLoad(viewerContext))

        val call = fixture.calls.single()
        val input = assertIs<CreateMutationInput<*>>(call.input)
        assertSame(viewerContext, input.viewerContext)
        assertTrue(input.checkReturnedEntityPrivacy)
        assertSame(client, call.client)
        assertEquals(resolutions, fixture.clientResolutions)
        assertFailsWith<EntMutationAlreadyConsumedException> { pending.save(viewerContext) }
    }

    @Test
    fun `explicit ID create passes the supplied ID into the draft`() {
        val fixture = Fixture()

        assertEquals(
            MutationResult.Success(Widget(42L, "assigned")),
            fixture.explicit.create(42L) { name = "assigned" }.saveAndLoad(viewerContext),
        )
        assertEquals(42L, assertIs<CreateDraft>(assertIs<CreateMutationInput<*>>(fixture.calls.single().input).draft).id)
    }

    @Test
    fun `both create variants save without disclosing the entity`() {
        val fixture = Fixture()
        fixture.denyReturnedEntity = true

        assertEquals(MutationResult.Success(Unit), fixture.generated.create { name = "generated" }.save(viewerContext))
        assertEquals(MutationResult.Success(Unit), fixture.explicit.create(9L) { name = "explicit" }.save(viewerContext))
        assertTrue(fixture.calls.all { !assertIs<CreateMutationInput<*>>(it.input).checkReturnedEntityPrivacy })
    }

    @Test
    fun `update preserves configured defaults per-call overrides and disclosure choice`() {
        val fixture = Fixture()
        val pending = fixture.generated.update(5L) { name = "first" }
        pending.configure { name += " second" }
        assertEquals(MutationResult.Success(Unit), pending.save(viewerContext))

        val saved = assertIs<UpdateMutationInput<*>>(fixture.calls.single().input)
        assertEquals(5L, saved.request.id)
        assertEquals("first second", assertIs<UpdateDraft>(saved.request.draft).name)
        assertEquals(UpdateConsistency.Pessimistic, saved.request.consistency)
        assertEquals(RelationshipLocking.Canonical, saved.request.relationshipLocking)
        assertFalse(saved.applyLoadPrivacy)
        assertSame(viewerContext, saved.viewerContext)

        assertEquals(
            MutationResult.Success(Widget(6L, "override")),
            fixture.explicit.update(6L, UpdateConsistency.ReadCurrent, RelationshipLocking.OwnerOnly) {
                name = "override"
            }.saveAndLoad(viewerContext),
        )
        val loaded = assertIs<UpdateMutationInput<*>>(fixture.calls.last().input)
        assertEquals(UpdateConsistency.ReadCurrent, loaded.request.consistency)
        assertEquals(RelationshipLocking.OwnerOnly, loaded.request.relationshipLocking)
        assertTrue(loaded.applyLoadPrivacy)
    }

    @Test
    fun `escaped pending mutations still check execution policy and are consumed on failure`() {
        val fixture = Fixture()
        val pending = fixture.generated.update(2L) { name = "pending" }
        val expired = IllegalStateException("transaction ended")
        fixture.policyFailure = expired

        assertSame(expired, assertIs<MutationResult.Failed>(pending.save(viewerContext)).exception.cause)
        assertTrue(fixture.calls.isEmpty())
        assertFailsWith<EntMutationAlreadyConsumedException> { pending.saveAndLoad(viewerContext) }
    }

    @Test
    fun `delete uses the handle ID and deleteById preserves absence`() {
        val fixture = Fixture()
        fixture.deleted = false

        assertEquals(MutationResult.Success(Unit), fixture.generated.delete(viewerContext, Widget(3L, "old")))
        assertEquals(MutationResult.Success(false), fixture.explicit.deleteById(viewerContext, 4L))
        assertEquals(listOf(3L, 4L), fixture.calls.map { assertIs<DeleteMutationInput>(it.input).id })
        assertEquals(0, fixture.driver.transactions)
    }

    @Test
    fun `query returns a fresh concrete builder and findById preserves read execution semantics`() {
        val fixture = Fixture()
        val configured = fixture.generated.query { where(Predicate.Leaf("name", Op.EQ, "one")) }
        val fresh: WidgetQuery = fixture.generated.query()
        assertNotSame(configured, fresh)
        assertTrue(fresh.captureEntityQuery().predicates.isEmpty())

        assertEquals(ReadResult.Success(Widget(1L, "one")), fixture.generated.findById(viewerContext, 1L))
        assertEquals(listOf(Predicate.Leaf<Widget>("widget_key", Op.EQ, 1L)), fixture.driver.queryPredicates)
        assertEquals(1, fixture.driver.queryLimit)
        assertEquals(listOf(ReadOperation.BY_ID), fixture.readOperations)
        assertSame(viewerContext, fixture.readViewers.single())

        fixture.driver.rows = emptyList()
        assertEquals(ReadResult.Success(null), fixture.explicit.findById(viewerContext, 99L))
        val readFailure = IllegalStateException("stale read client")
        fixture.readFailure = readFailure
        assertSame(readFailure, assertIs<ReadResult.Failed>(fixture.generated.findById(viewerContext, 1L)).exception)
    }

    @Test
    fun `load privacy remains fail closed and exposes no execution dependencies`() {
        val fixture = Fixture()
        val repo = fixture.generated
        assertTrue(repo.hasLoadPrivacy())
        assertFalse(repo.evaluateLoadPrivacy(viewerContext, listOf(Widget(1L, "one"))).deniedOutcomes().isEmpty())

        // JVM bridges are synthetic, not callable Kotlin API; the compile tests check source access.
        val names = repo.javaClass.methods.filterNot { it.isSynthetic }.map { it.name }.toSet()
        assertTrue(names.none { it in setOf("getRuleClient", "getSelf", "getMutationExecutor", "withTransaction") })
        assertFalse(repo is entkt.runtime.mutation.CreateMutationRepository<*, *>)
        assertFalse(repo is entkt.runtime.mutation.UpdateMutationRepository<*, *>)
    }

    @Test
    fun `createMany selects transaction operation driver and rule client and preserves ordered inputs`() {
        val root = Fixture()
        val tx = Fixture(inTransaction = true)
        root.bind(tx)
        val result = root.generated.createMany(
            viewerContext,
            {
                assertEquals(1, root.driver.transactions)
                assertEquals(0, root.driver.commits)
                name = "one"
            },
            { name = "two" },
        )

        assertEquals(MutationResult.Success(listOf(Widget(1L, "one"), Widget(2L, "two"))), result)
        assertOwnedBinding(root, tx, "createMany")
        assertSame(viewerContext, assertIs<CreateManyMutationInput<*>>(tx.calls.single().input).viewerContext)
        assertEquals(1, root.driver.commits)
    }

    @Test
    fun `deleteMany rebinds either ID variant and preserves predicate order`() {
        for (explicit in listOf(false, true)) {
            val root = Fixture()
            val tx = Fixture(inTransaction = true)
            root.bind(tx)
            val first = Predicate.Leaf<Widget>("name", Op.EQ, "one")
            val second = Predicate.Leaf<Widget>("widget_key", Op.GT, 0L)

            val result = if (explicit) {
                root.explicit.deleteMany(viewerContext, first, second)
            } else {
                root.generated.deleteMany(viewerContext, first, second)
            }

            assertEquals(MutationResult.Success(2), result)
            assertOwnedBinding(root, tx, "deleteMany")
            val input = assertIs<DeleteManyMutationInput<*>>(tx.calls.single().input)
            assertEquals(listOf(first, second), input.predicates)
            assertSame(viewerContext, input.viewerContext)
        }
    }

    @Test
    fun `an existing transaction is reused without invoking the repository transaction binding`() {
        val fixture = Fixture(inTransaction = true)

        assertIs<MutationResult.Success<*>>(fixture.generated.createMany(viewerContext, { name = "one" }))
        assertIs<MutationResult.Success<*>>(fixture.generated.deleteMany(viewerContext))
        assertEquals(0, fixture.driver.transactions)
        assertTrue(fixture.calls.all { !it.owned && it.driver === fixture.driver })
    }

    @Test
    fun `empty createMany does not construct drafts or start a transaction`() {
        val fixture = Fixture()

        assertEquals(MutationResult.Success(emptyList()), fixture.generated.createMany(viewerContext))
        assertEquals(0, fixture.draftsCreated)
        assertEquals(0, fixture.driver.transactions)
    }

    @Test
    fun `application transaction policy precedes batch construction and transaction creation`() {
        val fixture = Fixture()
        val rejected = IllegalStateException("application transaction required")
        fixture.policyFailure = rejected

        val result = fixture.generated.createMany(viewerContext, { error("must not configure a draft") })

        assertSame(rejected, assertIs<MutationResult.Failed>(result).exception.cause)
        assertEquals(0, fixture.draftsCreated)
        assertEquals(0, fixture.driver.transactions)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `owned mutation failure rolls back and is classified by the executor`() {
        val root = Fixture()
        val tx = Fixture(inTransaction = true)
        root.bind(tx)
        tx.operationFailure = IllegalStateException("write failed")

        val failure = assertIs<MutationResult.Failed>(root.generated.deleteMany(viewerContext)).exception

        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertSame(tx.operationFailure, failure.cause)
        assertEquals(1, root.driver.rollbacks)
        assertEquals(0, root.driver.commits)
        assertEquals(1, tx.failures.size)
    }

    @Test
    fun `owned return denial stays neutral until commit is confirmed`() {
        val root = Fixture()
        val tx = Fixture(inTransaction = true)
        root.bind(tx)
        tx.denyReturnedEntity = true

        val result = root.generated.createMany(viewerContext, { name = "hidden" })

        val failure = assertIs<EntMutationPrivacyDeniedException>(assertIs<MutationResult.Failed>(result).exception)
        assertEquals(MutationWriteState.Committed, failure.writeState)
        assertEquals(1, root.driver.commits)
        assertEquals(0, root.driver.rollbacks)
        assertTrue(tx.failures.isEmpty())
    }

    private fun assertOwnedBinding(root: Fixture, tx: Fixture, operation: String) {
        assertTrue(root.calls.isEmpty())
        val call = tx.calls.single()
        assertEquals(operation, call.operation)
        assertSame(tx.driver, call.driver)
        assertSame(tx.client, call.client)
        assertTrue(call.owned)
        assertEquals(1, root.driver.transactions)
        assertEquals(1, root.preflights.size)
        assertTrue(tx.preflights.isEmpty())
    }

    private data class Widget(override val id: Long, val name: String) : EntEntity.LongId
    private class CreateDraft(val id: Long? = null, var name: String = "") : CreateMutationDraft<Widget>
    private class UpdateDraft(var name: String = "") : UpdateMutationDraft<Widget>
    private data class RuleClient(val scope: String)

    /** Matches the generated per-entity read surface, implemented by inherited final methods. */
    private interface WidgetReadSurface {
        fun hasLoadPrivacy(): Boolean
        fun evaluateLoadPrivacy(viewerContext: ViewerContext, entities: List<Widget>): PrivacyEvaluation<Widget>
    }

    private object Descriptor : EntityDescriptor<Widget, Long> {
        override val entityName = "Widget"
        override val clientName = "widgets"
        override val entityClass = Widget::class
        override val schema = EntitySchema("widgets", "widget_key", IdStrategy.AUTO_LONG, emptyList(), emptyMap())
        override val edgesByStorageName: Map<String, EdgeMapping<Widget, *>> = emptyMap()
        override fun decode(row: Map<String, Any?>): Widget =
            Widget(row.getValue("widget_key") as Long, row.getValue("name") as String)
    }

    private data class Call(
        val operation: String,
        val input: Any,
        val client: RuleClient,
        val driver: DatabaseDriver,
        val owned: Boolean,
    )

    private class Fixture(inTransaction: Boolean = false) : MutationRuntime, ReadQueryExecutionHost {
        val driver = RecordingDriver(inTransaction)
        val calls = mutableListOf<Call>()
        val preflights = mutableListOf<String>()
        val failures = mutableListOf<EntMutationException>()
        val readOperations = mutableListOf<ReadOperation>()
        val readViewers = mutableListOf<ViewerContext>()
        var client = RuleClient(driver.scope)
        var clientResolutions = 0
        var draftsCreated = 0
        var deleted = true
        var denyReturnedEntity = false
        var policyFailure: Exception? = null
        var operationFailure: Exception? = null
        var readFailure: Exception? = null
        var coordinator: TransactionCoordinator? = null
        var transaction: Fixture? = null
        val executor = MutationExecutor(driver, this)
        val loadPrivacy = LoadPrivacyEvaluator<RuleClient, Widget>(Descriptor, emptyList())
        val generated by lazy { GeneratedRepo(this) }
        val explicit by lazy { ExplicitRepo(this) }

        val create = operation<CreateMutationInput<CreateDraft>, Widget>("create") { input ->
            disclose(Widget(input.draft.id ?: 1L, input.draft.name), input.checkReturnedEntityPrivacy)
        }
        val update = operation<UpdateMutationInput<UpdateDraft>, Widget>("update") { input ->
            disclose(Widget(input.request.id as Long, input.request.draft.name), input.applyLoadPrivacy)
        }
        val delete = operation<DeleteMutationInput, Boolean>("delete") { MutationCompletion.Ready(deleted) }
        val deleteMany = operation<DeleteManyMutationInput<Widget>, Int>(
            "deleteMany", requirements = { MutationRequirements("Widget deleteMany", true, true) },
        ) { MutationCompletion.Ready(2) }
        val createMany = operation<CreateManyMutationInput<CreateDraft>, List<Widget>>(
            "createMany",
            requirements = { MutationRequirements("Widget createMany", it.blocks.size > 1, it.blocks.isNotEmpty()) },
        ) { input ->
            val values = input.blocks.mapIndexed { index, block ->
                val draft = input.newDraft().apply(block)
                Widget(index + 1L, draft.name)
            }
            disclose(values, values.isNotEmpty())
        }

        private fun <Input : Any, Result> operation(
            name: String,
            requirements: (Input) -> MutationRequirements = { MutationRequirements("Widget $name") },
            response: (Input) -> MutationCompletion<Result>,
        ): MutationOperation<RuleClient, Input, Result> = object : MutationOperation<RuleClient, Input, Result> {
            override fun requirements(input: Input): MutationRequirements = requirements(input)

            override fun run(execution: MutationExecution, ruleClient: RuleClient, input: Input): MutationCompletion<Result> {
                calls += Call(name, input, ruleClient, execution.driver, execution.isOwnedTransaction)
                execution.markWriteSucceeded()
                operationFailure?.let { execution.reject(EntUnexpectedMutationException(execution.writeState, it)) }
                return response(input)
            }
        }

        private fun <Result> disclose(value: Result, applyPrivacy: Boolean): MutationCompletion<Result> =
            if (applyPrivacy && denyReturnedEntity) {
                MutationCompletion.ReturnDenied(PrivacyDenial("Widget", EntityKey("id", 1L), "hidden"))
            } else {
                MutationCompletion.Ready(value)
            }

        fun bind(tx: Fixture) {
            transaction = tx
            driver.transactionDriver = tx.driver
        }

        override fun checkTransactionRequirement(operation: String, multiWrite: Boolean) {
            preflights += operation
            policyFailure?.let { throw it }
        }

        override fun recordTransactionMutationFailure(exception: EntMutationException) {
            failures += exception
            coordinator?.recordFailure(exception)
        }

        override val entityInterceptors = EntInterceptorsConfig().apply {
            addEntity<Widget>(Descriptor.clientName, "observe") { _, context -> readOperations += context.operation }
        }.resolveForInternalUse()

        override fun checkReadExecution() {
            readFailure?.let { throw it }
        }

        override fun isConfigured(entity: EntityMapping<*>): Boolean = true

        override fun <Entity : EntEntity<*>> evaluate(
            entity: EntityMapping<Entity>, viewerContext: ViewerContext, entities: List<Entity>,
        ): PrivacyEvaluation<Entity> {
            readViewers += viewerContext
            return privacyEvaluation(entities)
        }
    }

    private class GeneratedRepo(private val fixture: Fixture) :
        GeneratedIdRepository<Widget, Long, CreateDraft, UpdateDraft, WidgetQuery, RuleClient>(
            Descriptor, fixture.executor, UpdateConsistency.Pessimistic, RelationshipLocking.Canonical,
        ), WidgetReadSurface {
        override val ruleClient: RuleClient get() = fixture.client.also { fixture.clientResolutions++ }
        override val createOperation = fixture.create
        override val createManyOperation = fixture.createMany
        override val updateOperation = fixture.update
        override val deleteOperation = fixture.delete
        override val deleteManyOperation = fixture.deleteMany
        override val loadPrivacyEvaluator = fixture.loadPrivacy
        override fun newQuery(): WidgetQuery = WidgetQuery(fixture)
        override fun newUpdateDraft(): UpdateDraft = UpdateDraft()
        override fun newCreateDraft(): CreateDraft = CreateDraft().also { fixture.draftsCreated++ }

        override fun <Result> withTransaction(
            block: TransactionScope.(GeneratedIdRepository<Widget, Long, CreateDraft, UpdateDraft, WidgetQuery, RuleClient>) -> Result,
        ): TransactionResult<Result> = runEntTransaction(
            fixture.driver,
            makeTxClient = { _, coordinator ->
                checkNotNull(fixture.transaction).also { it.coordinator = coordinator }.generated
            },
            block = block,
        )
    }

    private class ExplicitRepo(private val fixture: Fixture) :
        ExplicitIdRepository<Widget, Long, CreateDraft, UpdateDraft, WidgetQuery, RuleClient>(Descriptor, fixture.executor),
        WidgetReadSurface {
        override val ruleClient: RuleClient get() = fixture.client.also { fixture.clientResolutions++ }
        override val createOperation = fixture.create
        override val updateOperation = fixture.update
        override val deleteOperation = fixture.delete
        override val deleteManyOperation = fixture.deleteMany
        override val loadPrivacyEvaluator = fixture.loadPrivacy
        override fun newQuery(): WidgetQuery = WidgetQuery(fixture)
        override fun newUpdateDraft(): UpdateDraft = UpdateDraft()
        override fun newCreateDraft(id: Long): CreateDraft = CreateDraft(id)

        override fun <Result> withTransaction(
            block: TransactionScope.(ExplicitIdRepository<Widget, Long, CreateDraft, UpdateDraft, WidgetQuery, RuleClient>) -> Result,
        ): TransactionResult<Result> = runEntTransaction(
            fixture.driver,
            makeTxClient = { _, coordinator ->
                checkNotNull(fixture.transaction).also { it.coordinator = coordinator }.explicit
            },
            block = block,
        )
    }

    private class WidgetQuery(fixture: Fixture) : EntityQueryBuilder<Widget, WidgetQuery>(
        fixture.driver, fixture, "Widget",
    ) {
        override val self: WidgetQuery get() = this
        override fun captureEntityQuery(structuralPredicates: List<Predicate<Widget>>): EntityQuery<Widget> = EntityQuery(
            Descriptor, QuerySource.Root(), predicates, orderFields, queryLimit, queryOffset, emptyList(), structuralPredicates,
        )
    }

    private class RecordingDriver(override val inTransaction: Boolean) : DatabaseDriver by NoopDriver {
        val scope = if (inTransaction) "transaction" else "root"
        var transactionDriver: DatabaseDriver? = null
        var transactions = 0
        var commits = 0
        var rollbacks = 0
        var rows = listOf(mapOf("widget_key" to 1L, "name" to "one"))
        var queryPredicates: List<Predicate<*>> = emptyList()
        var queryLimit: Int? = null

        override fun <Result> withTransaction(block: (DatabaseDriver) -> Result): DriverTransactionResult<Result> {
            transactions++
            return try {
                val result = block(checkNotNull(transactionDriver))
                commits++
                DriverTransactionResult.Success(result)
            } catch (e: Exception) {
                rollbacks++
                DriverTransactionResult.Failed(e, TransactionFailureState.NotCommitted)
            }
        }

        override fun query(
            table: String, predicates: List<Predicate<*>>, orderBy: List<OrderField<*>>, limit: Int?, offset: Int?,
        ): List<Map<String, Any?>> {
            queryPredicates = predicates
            queryLimit = limit
            return rows
        }
    }
}
