@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyContextProvider
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.StorageQuerySpec
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EdgeTraversal
import entkt.runtime.query.EntInterceptorsConfig
import entkt.runtime.query.InterceptScope
import entkt.runtime.query.QueryContext
import entkt.runtime.query.QuerySource
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.execution.LoadPrivacyEvaluator
import entkt.runtime.query.execution.LoadPrivacyEvaluation
import entkt.runtime.query.execution.ReadQueryEvaluator
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ReadResult
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class ReadQueryEvaluatorRootTest {
    private data class Item(
        override val id: Long,
        val loaded: Boolean = false,
    ) : EntEntity.LongId

    private class QueryDriver(
        private val rows: List<Map<String, Any?>>,
        private val events: MutableList<String>,
        private val failure: Throwable? = null,
    ) : DatabaseDriver by NoopDriver {
        var table: String? = null
        var predicates: List<Predicate<*>>? = null
        var orderBy: List<OrderField<*>>? = null
        var limit: Int? = null
        var offset: Int? = null

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            events += "driver"
            failure?.let { throw it }
            this.table = table
            this.predicates = predicates
            this.orderBy = orderBy
            this.limit = limit
            this.offset = offset
            return rows
        }
    }

    private class Adapter(
        val events: MutableList<String>,
        var driver: DatabaseDriver = NoopDriver,
        var spec: StorageQuerySpec<Item> = StorageQuerySpec(
            table = "items",
            predicates = emptyList(),
            orderBy = emptyList(),
            limit = null,
            offset = null,
            flags = emptySet(),
            annotations = emptyMap(),
        ),
    ) : EntityMapping<Item> {
        val privacy = PrivacyContext(Viewer.User(7L))
        var privacyEnabled = true
        var denials: List<PrivacyDenial?> = emptyList()
        var preparationFailure: Throwable? = null
        val interceptorContexts = mutableListOf<QueryContext>()
        var interceptorBehavior: (InterceptScope<Item>, QueryContext) -> Unit = { _, context ->
            events += if (context.operation == ReadOperation.ALL || context.operation == ReadOperation.FIRST) {
                "root-interceptors"
            } else {
                "interceptors:${context.operation}"
            }
        }
        val childrenEdge: EdgeMapping<Item, Item> = ItemEdge(this)
        val interceptors = EntInterceptorsConfig().apply {
            addEntity<Item>("items", "trace") { scope, context ->
                preparationFailure?.let { throw it }
                interceptorContexts += context
                interceptorBehavior(scope, context)
            }
        }

        override val entityName: String = "Item"
        override val clientName: String = "items"
        override val entityClass = Item::class
        override val table: String = "items"

        override fun decode(row: Map<String, Any?>): Item {
            val id = row.getValue("id") as Long
            events += "decode:$id"
            return Item(id)
        }

        override fun edgeByStorageName(storageName: String): EdgeMapping<Item, *>? =
            childrenEdge.takeIf { storageName == it.storageName }

    }

    private class ItemEdge(
        private val mapping: EntityMapping<Item>,
    ) : EdgeMapping<Item, Item> {
        override val name: String = "children"
        override val storageName: String = "children_storage"
        override val source: EntityMapping<Item> = mapping
        override val target: EntityMapping<Item> = mapping
        override val traversal: EdgeTraversal<Item> = EdgeTraversal.Direct(
            inverseStorageName = "parent_storage",
            selectedColumn = "id",
        )
    }

    private fun rootQuery(adapter: Adapter): EntityQuery<Item> = EntityQuery(
        entity = adapter,
        source = QuerySource.Root(),
        predicates = adapter.spec.predicates,
        orderBy = adapter.spec.orderBy,
        limit = adapter.spec.limit,
        offset = adapter.spec.offset,
        edges = emptyList(),
    )

    private fun read(
        adapter: Adapter,
        query: EntityQuery<Item>,
        operation: ReadOperation = ReadOperation.ALL,
    ): ReadResult<List<Item>> = queryEvaluator(adapter).readRootQuery(
        captureQuery = { query },
        operation = operation,
        maximumRows = null,
    )

    private fun queryEvaluator(adapter: Adapter): ReadQueryEvaluator<Item> = ReadQueryEvaluator(
        driver = adapter.driver,
        privacyContextProvider = PrivacyContextProvider {
            adapter.events += "privacy-context"
            adapter.privacy
        },
        registeredInterceptorsProvider = { adapter.interceptors },
        loadPrivacyEvaluatorProvider = { object : LoadPrivacyEvaluator {
            override fun isConfigured(entity: EntityMapping<*>): Boolean {
                assertSame(adapter as Any, entity as Any)
                adapter.events += "has-privacy"
                return adapter.privacyEnabled
            }

            override fun <Entity : EntEntity<*>> evaluate(
                entity: EntityMapping<Entity>,
                privacyContext: PrivacyContext,
                entities: List<Entity>,
            ): List<LoadPrivacyEvaluation<Entity>> {
                assertSame(adapter as Any, entity as Any)
                assertSame(adapter.privacy, privacyContext)
                adapter.events += "load-privacy:${entities.joinToString { it.id.toString() }}"
                val denials = adapter.denials.ifEmpty { List(entities.size) { null } }
                return entities.mapIndexed { index, entityValue ->
                    val denial = denials[index]
                    if (denial == null) {
                        LoadPrivacyEvaluation.Allowed(entityValue)
                    } else {
                        LoadPrivacyEvaluation.Denied(entityValue, denial)
                    }
                }
            }
        } },
    )

    private fun readAll(adapter: Adapter): ReadResult<List<Item>> =
        queryEvaluator(adapter).readRootQuery(
            captureQuery = { rootQuery(adapter) },
            operation = ReadOperation.ALL,
            maximumRows = null,
        )

    private fun readFirstOrNull(adapter: Adapter): ReadResult<Item?> =
        when (
            val result = queryEvaluator(adapter).readRootQuery(
                captureQuery = { rootQuery(adapter) },
                operation = ReadOperation.FIRST,
                maximumRows = 1,
            )
        ) {
            is ReadResult.Success -> ReadResult.Success(result.value.firstOrNull())
            is ReadResult.Failed -> result
        }

    @Test
    fun `evaluator rejects a negative terminal bound`() {
        val adapter = Adapter(mutableListOf())

        assertFailsWith<IllegalArgumentException> {
            queryEvaluator(adapter).readRootQuery(
                captureQuery = { rootQuery(adapter) },
                operation = ReadOperation.ALL,
                maximumRows = -1,
            )
        }
    }

    @Test
    fun `query capture failures become read failures before the lifecycle starts`() {
        val events = mutableListOf<String>()
        val adapter = Adapter(events)
        val failure = IllegalStateException("capture failed")

        val result = queryEvaluator(adapter).readRootQuery(
            captureQuery = { throw failure },
            operation = ReadOperation.ALL,
            maximumRows = null,
        )

        val failed = assertIs<ReadResult.Failed>(result)
        assertSame(failure, failed.exception)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `all executes one ordered root pipeline and preserves the frozen driver shape`() {
        val events = mutableListOf<String>()
        val order = listOf(OrderField<Item>("id", OrderDirection.DESC))
        val adapter = Adapter(events).apply {
            spec = spec.copy(orderBy = order, limit = 4, offset = 2)
        }
        val driver = QueryDriver(
            rows = listOf(mapOf("id" to 2L), mapOf("id" to 1L)),
            events = events,
        )
        adapter.driver = driver

        val result = readAll(adapter)

        assertEquals(
            listOf(Item(2L), Item(1L)),
            assertIs<ReadResult.Success<List<Item>>>(result).value,
        )
        assertEquals("items", driver.table)
        assertEquals(order, driver.orderBy)
        assertEquals(4, driver.limit)
        assertEquals(2, driver.offset)
        assertEquals(
            listOf(
                "privacy-context",
                "root-interceptors",
                "driver",
                "decode:2",
                "decode:1",
                "has-privacy",
                "load-privacy:2, 1",
            ),
            events,
        )
    }

    @Test
    fun `chained queries run source and target interceptors and preserve the source shape`() {
        val events = mutableListOf<String>()
        val adapter = Adapter(events)
        val driver = QueryDriver(emptyList(), events)
        adapter.driver = driver
        adapter.interceptorBehavior = { scope, context ->
            events += "interceptors:${context.operation}"
            when (context.operation) {
                ReadOperation.EDGE_TRAVERSAL -> {
                    scope.addPredicate(Predicate.Leaf("source_visible", Op.EQ, true))
                    scope.addAnnotation("source", "intercepted")
                }

                ReadOperation.ALL -> scope.addPredicate(
                    Predicate.Leaf("target_visible", Op.EQ, true),
                )

                else -> Unit
            }
        }
        val sourceQuery = EntityQuery(
            entity = adapter,
            source = QuerySource.Root(),
            predicates = listOf(Predicate.Leaf("source", Op.EQ, 7L)),
            orderBy = listOf(OrderField("id", OrderDirection.DESC)),
            limit = 3,
            offset = 1,
            edges = emptyList(),
        )
        val targetQuery = EntityQuery(
            entity = adapter,
            source = QuerySource.Traversal(sourceQuery, adapter.childrenEdge),
            predicates = listOf(Predicate.Leaf("target", Op.EQ, 9L)),
            orderBy = emptyList(),
            limit = null,
            offset = null,
            edges = emptyList(),
        )

        assertIs<ReadResult.Success<List<Item>>>(read(adapter, targetQuery))

        val predicates = checkNotNull(driver.predicates)
        assertEquals(3, predicates.size)
        assertEquals(Predicate.Leaf<Item>("target", Op.EQ, 9L), predicates[0])
        val bridge = assertIs<Predicate.HasEdgeFromShape<Item, Item>>(predicates[1])
        assertEquals("parent_storage", bridge.edge)
        assertEquals("items", bridge.source.table)
        assertEquals("id", bridge.source.selectedColumn)
        assertEquals(3, bridge.source.limit)
        assertEquals(1, bridge.source.offset)
        assertEquals(
            listOf(
                Predicate.Leaf<Item>("source", Op.EQ, 7L),
                Predicate.Leaf<Item>("source_visible", Op.EQ, true),
            ),
            bridge.source.predicates,
        )
        assertEquals(Predicate.Leaf<Item>("target_visible", Op.EQ, true), predicates[2])
        assertEquals(
            listOf(ReadOperation.EDGE_TRAVERSAL, ReadOperation.ALL),
            adapter.interceptorContexts.map(QueryContext::operation),
        )
        val targetContext = adapter.interceptorContexts.last()
        assertEquals(Item::class, targetContext.rootEntity)
        assertEquals(Item::class, targetContext.sourceEntity)
        assertEquals("children", targetContext.edgeName)
        assertEquals(
            listOf(EdgeStep(Item::class, "children", Item::class)),
            targetContext.path,
        )
    }

    @Test
    fun `edge predicates run target interceptors using the declared edge context`() {
        val events = mutableListOf<String>()
        val adapter = Adapter(events)
        val driver = QueryDriver(emptyList(), events)
        adapter.driver = driver
        adapter.interceptorBehavior = { scope, context ->
            events += "interceptors:${context.operation}"
            if (context.operation == ReadOperation.EDGE_PREDICATE) {
                scope.addPredicate(Predicate.Leaf("target_visible", Op.EQ, true))
            }
        }
        val inner = Predicate.Leaf<Item>("published", Op.EQ, true)
        val query = EntityQuery(
            entity = adapter,
            source = QuerySource.Root(),
            predicates = listOf(
                Predicate.HasEdgeWith<Item, Item>("children_storage", inner),
            ),
            orderBy = emptyList(),
            limit = null,
            offset = null,
            edges = emptyList(),
        )

        assertIs<ReadResult.Success<List<Item>>>(read(adapter, query))

        val rewritten = assertIs<Predicate.HasEdgeWith<Item, Item>>(
            checkNotNull(driver.predicates).single(),
        )
        assertEquals(
            Predicate.And(inner, Predicate.Leaf<Item>("target_visible", Op.EQ, true)),
            rewritten.inner,
        )
        val targetContext = adapter.interceptorContexts.single {
            it.operation == ReadOperation.EDGE_PREDICATE
        }
        assertEquals("children", targetContext.edgeName)
        assertEquals(
            listOf(EdgeStep(Item::class, "children", Item::class)),
            targetContext.path,
        )
    }

    @Test
    fun `all aggregates root denials and does not load selected edges`() {
        val events = mutableListOf<String>()
        val first = PrivacyDenial("Item", EntityKey("id", 1L), "one")
        val second = PrivacyDenial("Item", EntityKey("id", 2L), "two")
        val adapter = Adapter(events).apply {
            denials = listOf(first, null, second)
        }
        val driver = QueryDriver(
            rows = listOf(mapOf("id" to 1L), mapOf("id" to 9L), mapOf("id" to 2L)),
            events = events,
        )
        adapter.driver = driver

        val failed = assertIs<ReadResult.Failed>(readAll(adapter))
        val exception = assertIs<EntPrivacyDeniedException>(failed.exception)

        assertSame(LoadDenialOrigin.Root, exception.origin)
        assertEquals(listOf(first, second), exception.denials)
        assertEquals(false, events.any { it.startsWith("interceptors:EAGER_LOAD") })
    }

    @Test
    fun `firstOrNull clamps the driver limit and preserves caller zero`() {
        val events = mutableListOf<String>()
        val adapter = Adapter(events)
        val driver = QueryDriver(listOf(mapOf("id" to 1L), mapOf("id" to 2L)), events)
        adapter.driver = driver

        val result = readFirstOrNull(adapter)

        assertEquals(Item(1L), assertIs<ReadResult.Success<Item?>>(result).value)
        assertEquals(1, driver.limit)

        events.clear()
        adapter.spec = adapter.spec.copy(limit = 0)
        val zeroDriver = QueryDriver(emptyList(), events)
        adapter.driver = zeroDriver
        val zero = readFirstOrNull(adapter)

        assertEquals(null, assertIs<ReadResult.Success<Item?>>(zero).value)
        assertEquals(0, zeroDriver.limit)
        assertEquals(
            listOf(
                "privacy-context",
                "root-interceptors",
                "driver",
            ),
            events,
        )
    }

    @Test
    fun `firstOrNull reports exactly the selected row denial`() {
        val events = mutableListOf<String>()
        val denial = PrivacyDenial("Item", EntityKey("id", 1L), "hidden")
        val adapter = Adapter(events).apply { denials = listOf(denial) }
        val driver = QueryDriver(listOf(mapOf("id" to 1L), mapOf("id" to 2L)), events)
        adapter.driver = driver

        val failed = assertIs<ReadResult.Failed>(
            readFirstOrNull(adapter),
        )
        val exception = assertIs<EntPrivacyDeniedException>(failed.exception)

        assertEquals(listOf(denial), exception.denials)
        assertEquals(listOf("decode:1"), events.filter { it.startsWith("decode") })
        assertEquals(false, events.any { it.startsWith("interceptors:EAGER_LOAD") })
    }

    @Test
    fun `ordinary failures retain identity`() {
        val events = mutableListOf<String>()
        val marker = IllegalStateException("driver failed")
        val driver = QueryDriver(emptyList(), events, marker)
        val adapter = Adapter(events, driver)

        val failed = assertIs<ReadResult.Failed>(readAll(adapter))

        assertSame(marker, failed.exception)
        assertEquals("driver", events.last())
    }

    @Test
    fun `cancellation and JVM errors propagate`() {
        val cancellationEvents = mutableListOf<String>()
        val cancellation = CancellationException("cancelled")
        val cancellationAdapter = Adapter(cancellationEvents).apply { preparationFailure = cancellation }

        val thrownCancellation = assertFailsWith<CancellationException> {
            readAll(cancellationAdapter)
        }
        assertSame(cancellation, thrownCancellation)
        assertEquals("privacy-context", cancellationEvents.last())

        val errorEvents = mutableListOf<String>()
        val error = AssertionError("fatal")
        val errorDriver = QueryDriver(emptyList(), errorEvents, error)
        val errorAdapter = Adapter(errorEvents, errorDriver)

        val thrownError = assertFailsWith<AssertionError> {
            readAll(errorAdapter)
        }
        assertSame(error, thrownError)
        assertEquals("driver", errorEvents.last())
    }
}
