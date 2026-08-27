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
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.execution.LoadPrivacyEvaluator
import entkt.runtime.query.execution.LoadPrivacyEvaluation
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.EntQueryConfigurationException
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadQueryExecutorRawTerminalTest {
    private data class Item(
        override val id: Long,
        val parentId: Long? = null,
    ) : EntEntity.LongId

    private class ItemMapping : EntityMapping<Item> {
        lateinit var childEdge: ToManyEdgeMapping<Item, Item>

        override val entityName = "Item"
        override val clientName = "items"
        override val entityClass = Item::class
        override val table = "items"
        override fun decode(row: Map<String, Any?>): Item = Item(
            id = row.getValue("id") as Long,
            parentId = row["parent_id"] as Long?,
        )

        override fun edgeByStorageName(storageName: String): EdgeMapping<Item, *>? =
            childEdge.takeIf { ::childEdge.isInitialized && it.storageName == storageName }
    }

    private class ChildEdge(
        mapping: EntityMapping<Item>,
    ) : ToManyEdgeMapping<Item, Item> {
        override val name = "children"
        override val source = mapping
        override val target = mapping
        override val storageName = "children_storage"
        override val traversal = EdgeTraversal.Direct<Item>("parent_storage", "id")
        override val storageStrategy = EdgeStorage.ForeignKeyOnTarget(
            sourceColumn = "id",
            targetColumn = "parent_id",
            sourceKey = Item::id,
            targetForeignKey = Item::parentId,
        )

        override fun attach(source: Item, targets: List<Item>): Item = source
    }

    private class RecordingDriver : DatabaseDriver by NoopDriver {
        var countCalls = 0
        var queryCalls = 0
        var aggregateCalls = 0
        var queryOrder: List<OrderField<*>>? = null
        var queryLimit: Int? = null
        var queryOffset: Int? = null

        override fun count(table: String, predicates: List<Predicate<*>>): Long {
            countCalls++
            return 4
        }

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            queryCalls++
            queryOrder = orderBy
            queryLimit = limit
            queryOffset = offset
            return if (limit == 0) emptyList() else listOf(mapOf("id" to 1L))
        }

        override fun aggregate(
            table: String,
            function: AggregateFunction,
            column: String?,
            predicates: List<Predicate<*>>,
            groupBy: String?,
        ): List<AggregateResultRow> {
            aggregateCalls++
            return listOf(AggregateResultRow(null, 9L))
        }

    }

    private val viewerContext = ViewerContext(Viewer.User(7L))

    private object NoLoadPrivacy : LoadPrivacyEvaluator {
        override fun isConfigured(entity: EntityMapping<*>): Boolean = false

        override fun <Entity : EntEntity<*>> evaluate(
            entity: EntityMapping<Entity>,
            viewerContext: ViewerContext,
            entities: List<Entity>,
        ): List<LoadPrivacyEvaluation<Entity>> = error("LOAD privacy is not configured")
    }

    private fun query(
        mapping: ItemMapping,
        limit: Int? = null,
        offset: Int? = null,
        orderBy: List<OrderField<Item>> = emptyList(),
        edges: List<EdgeSelection<Item, *>> = emptyList(),
    ): EntityQuery<Item> = EntityQuery(
        entity = mapping,
        source = QuerySource.Root(),
        predicates = listOf(Predicate.Leaf("active", Op.EQ, true)),
        orderBy = orderBy,
        limit = limit,
        offset = offset,
        edges = edges,
    )

    private fun executor(
        driver: RecordingDriver,
        executionGuard: () -> Unit = {},
    ): ReadQueryExecutor<Item> = ReadQueryExecutor(
        driver = driver,
        readExecutionGuard = executionGuard,
        registeredInterceptorsProvider = { EntInterceptorsConfig() },
        loadPrivacyEvaluatorProvider = { NoLoadPrivacy },
    )

    @Test
    fun `raw terminals delegate preparation and storage work to one read executor boundary`() {
        val mapping = ItemMapping()
        mapping.childEdge = ChildEdge(mapping)
        val driver = RecordingDriver()
        val executor = executor(driver)

        assertEquals(
            4L,
            assertIs<ReadResult.Success<Long>>(
                executor.rawCount(viewerContext) { query(mapping) },
            ).value,
        )
        assertEquals(
            true,
            assertIs<ReadResult.Success<Boolean>>(
                executor.rawExists(viewerContext) { query(mapping, offset = 3) },
            ).value,
        )
        assertEquals(emptyList(), driver.queryOrder)
        assertEquals(1, driver.queryLimit)
        assertEquals(3, driver.queryOffset)

        val aggregate = executor.rawAggregate(
            viewerContext = viewerContext,
            captureQuery = { query(mapping) },
            terminal = "rawSum",
            function = AggregateFunction.SUM,
            column = "amount",
            groupBy = null,
        ) { rows -> rows.single().value as Long }
        assertEquals(9L, assertIs<ReadResult.Success<Long>>(aggregate).value)
        assertEquals(1, driver.countCalls)
        assertEquals(1, driver.queryCalls)
        assertEquals(1, driver.aggregateCalls)
    }

    @Test
    fun `selected edges fail before the execution guard or storage work`() {
        val mapping = ItemMapping()
        val edge = ChildEdge(mapping).also { mapping.childEdge = it }
        val selectedTarget = query(mapping)
        val selected = query(
            mapping,
            edges = listOf(EdgeSelection(edge, selectedTarget, EdgeVisibility.REQUIRE_VISIBLE)),
        )
        val driver = RecordingDriver()
        var guardCalls = 0
        val result = executor(driver) { guardCalls++ }.rawCount(viewerContext) { selected }

        val failure = assertIs<ReadResult.Failed>(result)
        assertIs<EntQueryConfigurationException>(failure.exception)
        assertTrue(failure.exception.message.orEmpty().contains("Item.children"))
        assertEquals(0, guardCalls)
        assertEquals(0, driver.countCalls)
    }

    @Test
    fun `aggregate result decoding failures remain inside ReadResult`() {
        val mapping = ItemMapping()
        mapping.childEdge = ChildEdge(mapping)
        val result = executor(RecordingDriver()).rawAggregate(
            viewerContext = viewerContext,
            captureQuery = { query(mapping) },
            terminal = "rawSum",
            function = AggregateFunction.SUM,
            column = "amount",
            groupBy = null,
        ) { rows -> rows.single().value as String }

        assertIs<ClassCastException>(assertIs<ReadResult.Failed>(result).exception)
    }

    @Test
    fun `exists execution preserves offset drop ordering and honors limit zero`() {
        val mapping = ItemMapping()
        mapping.childEdge = ChildEdge(mapping)
        val driver = RecordingDriver()
        val executor = executor(driver)
        val query = query(
            mapping,
            limit = 0,
            offset = 6,
            orderBy = listOf(OrderField("id", OrderDirection.DESC)),
        )

        assertEquals(
            false,
            assertIs<ReadResult.Success<Boolean>>(executor.rawExists(viewerContext) { query }).value,
        )
        assertEquals(emptyList(), driver.queryOrder)
        assertEquals(0, driver.queryLimit)
        assertEquals(6, driver.queryOffset)

    }

    @Test
    fun `query capture failures remain inside the read result boundary`() {
        val mapping = ItemMapping().also { it.childEdge = ChildEdge(it) }
        val driver = RecordingDriver()
        var guardCalls = 0
        val failure = IllegalStateException("capture failed")

        val result = executor(driver) { guardCalls++ }.rawCount(viewerContext) {
            throw failure
        }

        assertSame(failure, assertIs<ReadResult.Failed>(result).exception)
        assertEquals(0, guardCalls)
        assertEquals(0, driver.countCalls)
    }
}
