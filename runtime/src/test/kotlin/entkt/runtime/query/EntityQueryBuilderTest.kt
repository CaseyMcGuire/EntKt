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
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.execution.LoadPrivacyEvaluation
import entkt.runtime.query.execution.ReadQueryExecutionHost
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class EntityQueryBuilderTest {
    private data class Item(override val id: Long) : EntEntity.LongId

    private object ItemMapping : EntityMapping<Item> {
        override val entityName: String = "Item"
        override val clientName: String = "items"
        override val entityClass = Item::class
        override val table: String = "items"

        override fun decode(row: Map<String, Any?>): Item = Item(row.getValue("id") as Long)

        override fun edgeByStorageName(storageName: String): EdgeMapping<Item, *>? = null
    }

    private class RecordingDriver(
        private val rows: List<Map<String, Any?>> = listOf(
            mapOf("id" to 1L),
            mapOf("id" to 2L),
        ),
    ) : DatabaseDriver by NoopDriver {
        var queryCalls: Int = 0
        var lastLimit: Int? = null

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            queryCalls++
            lastLimit = limit
            return rows.drop(offset ?: 0).let { selected ->
                if (limit == null) selected else selected.take(limit)
            }
        }
    }

    private class RecordingHost : ReadQueryExecutionHost {
        var guardCalls: Int = 0
        val interceptorContexts: MutableList<QueryContext> = mutableListOf()
        val loadViewerContexts: MutableList<ViewerContext> = mutableListOf()

        override val entityInterceptors: EntInterceptorsConfig = EntInterceptorsConfig().apply {
            addEntity<Item>(ItemMapping.clientName, "record") { _, context ->
                interceptorContexts += context
            }
        }

        override fun checkReadExecution() {
            guardCalls++
        }

        override fun isConfigured(entity: EntityMapping<*>): Boolean = true

        override fun <Entity : EntEntity<*>> evaluate(
            entity: EntityMapping<Entity>,
            viewerContext: ViewerContext,
            entities: List<Entity>,
        ): List<LoadPrivacyEvaluation<Entity>> {
            loadViewerContexts += viewerContext
            return entities.map { LoadPrivacyEvaluation.Allowed(it) }
        }
    }

    private class ItemQuery(
        driver: DatabaseDriver,
        executionHost: ReadQueryExecutionHost?,
    ) : EntityQueryBuilder<Item, ItemQuery>(
        driver = driver,
        executionHost = executionHost,
        entityName = ItemMapping.entityName,
    ) {
        override val self: ItemQuery
            get() = this

        override fun captureEntityQuery(
            structuralPredicates: List<Predicate<Item>>,
        ): EntityQuery<Item> = EntityQuery(
            entity = ItemMapping,
            source = QuerySource.Root(),
            predicates = predicates,
            orderBy = orderFields,
            limit = queryLimit,
            offset = queryOffset,
            edges = emptyList(),
            structuralPredicates = structuralPredicates,
        )
    }

    private val vc = ViewerContext(Viewer.User(7L))

    @Test
    fun `fluent configuration stays typed and capture sees accumulated state`() {
        val first = Predicate.Leaf<Item>("active", Op.EQ, true)
        val second = Predicate.Leaf<Item>("score", Op.GTE, 10)
        val order = OrderField<Item>("score", OrderDirection.DESC)
        val query = ItemQuery(NoopDriver, executionHost = null)

        assertSame(query, query.where(first))
        assertSame(query, query.where(second))
        assertSame(query, query.orderBy(order))
        assertSame(query, query.limit(25))
        assertSame(query, query.offset(5))

        val captured = query.captureEntityQuery()
        assertEquals(listOf(first, second), captured.predicates)
        assertEquals(listOf(order), captured.orderBy)
        assertEquals(25, captured.limit)
        assertEquals(5, captured.offset)
        assertEquals(Predicate.And(first, second), query.combinedPredicate())
    }

    @Test
    fun `negative bounds fail before mutating query state`() {
        val query = ItemQuery(NoopDriver, executionHost = null)

        val limitFailure = assertFailsWith<IllegalArgumentException> { query.limit(-1) }
        val offsetFailure = assertFailsWith<IllegalArgumentException> { query.offset(-2) }

        assertEquals("limit must be non-negative; was -1", limitFailure.message)
        assertEquals("offset must be non-negative; was -2", offsetFailure.message)
        assertEquals(null, query.queryLimit)
        assertEquals(null, query.queryOffset)
    }

    @Test
    fun `all delegates through the host with the identical viewer context`() {
        val driver = RecordingDriver()
        val host = RecordingHost()

        val result = assertIs<ReadResult.Success<List<Item>>>(
            ItemQuery(driver, host).all(vc),
        )

        assertEquals(listOf(Item(1L), Item(2L)), result.value)
        assertEquals(1, driver.queryCalls)
        assertEquals(1, host.guardCalls)
        assertEquals(ReadOperation.ALL, host.interceptorContexts.single().operation)
        assertSame(vc, host.interceptorContexts.single().viewerContext)
        assertSame(vc, host.loadViewerContexts.single())
    }

    @Test
    fun `firstOrNull preserves the one-row execution bound`() {
        val driver = RecordingDriver()
        val host = RecordingHost()

        val result = assertIs<ReadResult.Success<Item?>>(
            ItemQuery(driver, host).firstOrNull(vc),
        )

        assertEquals(Item(1L), result.value)
        assertEquals(1, driver.lastLimit)
        assertEquals(ReadOperation.FIRST, host.interceptorContexts.single().operation)
        assertSame(vc, host.interceptorContexts.single().viewerContext)
    }

    @Test
    fun `framework compilation delegates through the same host guard and context`() {
        val host = RecordingHost()
        val predicate = Predicate.Leaf<Item>("active", Op.EQ, true)
        val query = ItemQuery(NoopDriver, host).where(predicate)

        val compiled = query.compileEntityQuery(vc, ReadOperation.DELETE_CANDIDATES)

        assertEquals(listOf(predicate), compiled.predicates)
        assertEquals(1, host.guardCalls)
        assertEquals(ReadOperation.DELETE_CANDIDATES, host.interceptorContexts.single().operation)
        assertSame(vc, host.interceptorContexts.single().viewerContext)
    }

    @Test
    fun `terminal without an execution host returns the existing client-required failure`() {
        val result = ItemQuery(NoopDriver, executionHost = null).all(vc)

        val failure = assertIs<ReadResult.Failed>(result)
        val exception = assertIs<IllegalStateException>(failure.exception)
        assertEquals("Item query requires a client for privacy enforcement", exception.message)
    }
}
