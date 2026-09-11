@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacyEvaluation
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntDatabaseConflictException
import entkt.runtime.result.EntConflictFailure
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import entkt.runtime.result.visibleOrNull
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForUpdateQueryTest {
    private data class Item(
        override val id: Long,
        val parentId: Long? = null,
        val parent: Item? = null,
    ) : EntEntity.LongId

    private object Items : EntityMapping<Item> {
        override val entityName = "Item"
        override val clientName = "items"
        override val entityClass = Item::class
        override val table = "items"
        override fun decode(row: Map<String, Any?>) = Item(row.getValue("id") as Long, row["parent_id"] as Long?)
        override fun edgeByStorageName(storageName: String): EdgeMapping<Item, *>? =
            Parent.takeIf { storageName == "parent" }
    }

    private object Parent : ToOneEdgeMapping<Item, Item> {
        override val name = "parent"
        override val storageName = "parent"
        override val source = Items
        override val target = Items
        override val traversal = EdgeTraversal.Direct<Item>("children", "parent_id")
        override val storageStrategy = EdgeStorage.ForeignKeyOnSource(
            sourceColumn = "parent_id",
            targetColumn = "id",
            sourceForeignKey = Item::parentId,
            targetKey = Item::id,
        )
        override fun attach(source: Item, target: Item?): Item = source.copy(parent = target)
    }

    private data class Call(
        val predicates: List<Predicate<*>>,
        val order: List<OrderField<*>>,
        val limit: Int?,
        val offset: Int?,
        val lockMode: QueryLockMode,
    )

    private class Fixture {
        val viewer = ViewerContext(Viewer.User(7L))
        val events = mutableListOf<String>()
        val calls = mutableListOf<Call>()
        val contexts = mutableListOf<QueryContext>()
        var transactional = true
        var supported = true
        var mutationLockSupported = false
        var closed: Exception? = null
        var failure: Throwable? = null
        var classify: (Exception) -> EntDatabaseConflictException? = { null }
        val classifiedExceptions = mutableListOf<Exception>()
        var rows = listOf(listOf(mapOf<String, Any?>("id" to 1L, "parent_id" to 2L)))
        var denied = emptySet<Long>()
        var intercept: (InterceptScope<Item>, QueryContext) -> Unit = { _, _ -> }
        val driver = object : DatabaseDriver by NoopDriver {
            override val inTransaction: Boolean get() = transactional
            override val supportsQueryForUpdate: Boolean get() = supported
            override val supportsReadRowForUpdate: Boolean get() = mutationLockSupported

            override fun classifyConflictException(exception: Exception): EntDatabaseConflictException? {
                classifiedExceptions += exception
                return classify(exception)
            }

            override fun query(
                table: String,
                predicates: List<Predicate<*>>,
                orderBy: List<OrderField<*>>,
                limit: Int?,
                offset: Int?,
                lockMode: QueryLockMode,
            ): List<Map<String, Any?>> {
                events += "driver:$lockMode"
                calls += Call(predicates, orderBy, limit, offset, lockMode)
                failure?.let { throw it }
                return rows[minOf(calls.lastIndex, rows.lastIndex)]
            }
        }
        private val host = object : ReadQueryExecutionHost {
            override val entityInterceptors = EntInterceptorsConfig().apply {
                addEntity<Item>("items", "test") { scope, context ->
                    events += "interceptor:${context.operation}"
                    contexts += context
                    intercept(scope, context)
                }
            }.resolveForInternalUse()

            override fun checkReadExecution() {
                events += "guard"
                closed?.let { throw it }
            }

            override fun isConfigured(entity: EntityMapping<*>): Boolean = true

            override fun <Entity : EntEntity<*>> evaluate(
                entity: EntityMapping<Entity>,
                viewerContext: ViewerContext,
                entities: List<Entity>,
            ): PrivacyEvaluation<Entity> {
                events += "privacy:${entities.joinToString { it.id.toString() }}"
                return privacyEvaluation(entities, entities.map {
                    if (it.id in denied) PrivacyDecision.Deny("hidden") else PrivacyDecision.Allow
                })
            }
        }
        val executor = ReadQueryExecutor<Item>(driver, host)
        fun locking(query: EntityQuery<Item> = EntityQuery(Items)): ForUpdateQuery<Item> =
            ForUpdateQuery(query, executor)
    }

    @Test
    fun `construction is inert and each terminal uses the captured query and ordinary privacy pipeline`() {
        val fixture = Fixture()
        val predicate = Predicate.Leaf<Item>("active", Op.EQ, true)
        val tenant = Predicate.Leaf<Item>("tenant_id", Op.EQ, 7L)
        val query = EntityQuery(Items, predicates = listOf(predicate),
            orderBy = listOf(OrderField("id", OrderDirection.DESC)), limit = 4, offset = 2)
        val locking = fixture.locking(query)
        query.configured(limit = 9, offset = 8)
        fixture.intercept = { scope, _ -> scope.addPredicate(tenant) }
        assertTrue(fixture.events.isEmpty())

        assertEquals(listOf(Item(1L, 2L)), locking.all(fixture.viewer).getOrThrow())
        assertEquals(Item(1L, 2L), locking.firstOrNull(fixture.viewer).getOrThrow())
        assertEquals(listOf(4, 1), fixture.calls.map { it.limit })
        assertTrue(fixture.calls.all {
            it.lockMode == QueryLockMode.ForUpdate && it.predicates == listOf(predicate, tenant) &&
                it.order == query.orderBy && it.offset == 2
        })
        assertEquals(listOf(ReadOperation.ALL, ReadOperation.FIRST), fixture.contexts.map { it.operation })
        assertTrue(fixture.contexts.all { it.lockMode == QueryLockMode.ForUpdate && it.viewerContext === fixture.viewer })
        assertEquals(listOf("guard", "interceptor:ALL", "driver:ForUpdate", "privacy:1",
            "guard", "interceptor:FIRST", "driver:ForUpdate", "privacy:1"), fixture.events)
    }

    @Test
    fun `preflight rejects root clients unsupported drivers and expired clients before interceptors or SQL`() {
        val outside = Fixture().apply { transactional = false }
        assertIs<TransactionRequiredException>(assertIs<ReadResult.Failed>(outside.locking().all(outside.viewer)).exception)
        assertEquals(listOf("guard"), outside.events)

        val unsupported = Fixture().apply { supported = false; mutationLockSupported = true }
        assertIs<UnsupportedDriverCapabilityException>(
            assertIs<ReadResult.Failed>(unsupported.locking().firstOrNull(unsupported.viewer)).exception,
        )
        assertEquals(listOf("guard"), unsupported.events)

        val expired = Fixture()
        val captured = expired.locking()
        val closed = IllegalStateException("transaction ended")
        expired.closed = closed
        assertSame(closed, assertIs<ReadResult.Failed>(captured.all(expired.viewer)).exception)
        assertEquals(listOf("guard"), expired.events)

        assertIs<IllegalStateException>(
            assertIs<ReadResult.Failed>(ForUpdateQuery(EntityQuery(Items), null).all(expired.viewer)).exception,
        )
    }

    @Test
    fun `absence and zero limits retain singular semantics`() {
        val fixture = Fixture().apply { rows = listOf(emptyList()) }
        assertEquals(null, fixture.locking().firstOrNull(fixture.viewer).getOrThrow())
        assertEquals(emptyList(), fixture.locking().all(fixture.viewer).getOrThrow())
        fixture.locking(EntityQuery(Items, limit = 0)).firstOrNull(fixture.viewer).getOrThrow()
        assertEquals(listOf(1, null, 0), fixture.calls.map { it.limit })
        assertTrue(fixture.events.none { it.startsWith("privacy") })
    }

    @Test
    fun `denial is a read failure and projections perform no further execution`() {
        val fixture = Fixture().apply { denied = setOf(1L) }
        val result = fixture.locking().firstOrNull(fixture.viewer)
        assertIs<EntPrivacyDeniedException>(assertIs<ReadResult.Failed>(result).exception)
        val events = fixture.events.toList()
        assertEquals(null, result.visibleOrNull().getOrThrow())
        assertEquals(events, fixture.events)
        fixture.denied = emptySet()
        assertEquals(Item(1L, 2L), fixture.locking().firstOrNull(fixture.viewer).getOrThrow())
    }

    @Test
    fun `traversal and edge predicate contexts remain unlocked while final root carries lock intent`() {
        val fixture = Fixture()
        val query = EntityQuery(
            Items,
            source = QuerySource.Traversal(EntityQuery(Items, limit = 3, offset = 1), Parent),
            predicates = listOf(Predicate.HasEdge("parent")),
        )
        fixture.locking(query).all(fixture.viewer).getOrThrow()
        assertEquals(listOf(ReadOperation.EDGE_TRAVERSAL, ReadOperation.ALL, ReadOperation.EDGE_PREDICATE),
            fixture.contexts.map { it.operation })
        assertEquals(listOf(QueryLockMode.None, QueryLockMode.ForUpdate, QueryLockMode.None),
            fixture.contexts.map { it.lockMode })
        val bridge = assertIs<Predicate.HasEdgeFromShape<*, *>>(fixture.calls.single().predicates.last())
        assertEquals(3, bridge.source.limit)
        assertEquals(1, bridge.source.offset)
    }

    @Test
    fun `eager reads stay unlocked and required edge denial stays a whole read failure`() {
        val fixture = Fixture().apply {
            rows = listOf(rows.single(), listOf(mapOf("id" to 2L)))
            denied = setOf(2L)
        }
        val query = EntityQuery(Items, edges = listOf(
            EdgeSelection(Parent, EntityQuery(Items), EdgeVisibility.REQUIRE_VISIBLE),
        ))
        val result = fixture.locking(query).firstOrNull(fixture.viewer)
        val failure = assertIs<EntPrivacyDeniedException>(assertIs<ReadResult.Failed>(result).exception)
        assertIs<LoadDenialOrigin.SelectedEdgePath>(failure.origin)
        assertSame(result, result.visibleOrNull())
        assertEquals(listOf(QueryLockMode.ForUpdate, QueryLockMode.None), fixture.calls.map { it.lockMode })
        assertEquals(listOf(QueryLockMode.ForUpdate, QueryLockMode.None), fixture.contexts.map { it.lockMode })
        assertEquals(listOf(ReadOperation.FIRST, ReadOperation.EAGER_LOAD), fixture.contexts.map { it.operation })
    }

    @Test
    fun `interceptor rejection and operational failures preserve read failure propagation`() {
        val rejected = Fixture().apply { intercept = { scope, _ -> scope.reject("blocked") } }
        assertIs<EntQueryRejectedException>(assertIs<ReadResult.Failed>(rejected.locking().all(rejected.viewer)).exception)
        assertTrue(rejected.calls.isEmpty())

        val operational = Fixture()
        val failure = IllegalStateException("database failed")
        operational.failure = failure
        assertSame(failure, assertIs<ReadResult.Failed>(operational.locking().all(operational.viewer)).exception)
        assertTrue(operational.events.none { it.startsWith("privacy") })

        for (uncaptured in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
            operational.failure = uncaptured
            assertSame(uncaptured, assertFailsWith<Throwable> { operational.locking().all(operational.viewer) })
        }
    }

    @Test
    fun `both locking terminals report typed conflicts before privacy and preserve projection behavior`() {
        for (first in listOf(false, true)) {
            val cause = Exception("database conflict")
            val fixture = Fixture().apply {
                failure = cause
                classify = { EntDatabaseConflictException("40P01", "deadlock", it) }
            }
            val query = fixture.locking()
            val result = if (first) query.firstOrNull(fixture.viewer) else query.all(fixture.viewer)
            val conflict = assertIs<EntDatabaseConflictException>(assertIs<ReadResult.Failed>(result).exception)

            assertIs<EntConflictFailure>(conflict)
            assertSame(cause, conflict.cause)
            assertEquals(listOf(cause), fixture.classifiedExceptions)
            assertTrue(fixture.events.none { it.startsWith("privacy") })
            assertSame(conflict, assertFailsWith<EntDatabaseConflictException> { result.getOrThrow() })
        }
    }

    @Test
    fun `ordinary reads require neither transaction nor locking capability`() {
        val fixture = Fixture().apply { transactional = false; supported = false }
        fixture.executor.readRootQuery(fixture.viewer, { EntityQuery(Items) }, ReadOperation.ALL, null).getOrThrow()
        assertEquals(QueryLockMode.None, fixture.calls.single().lockMode)
        assertEquals(QueryLockMode.None, fixture.contexts.single().lockMode)
    }
}
