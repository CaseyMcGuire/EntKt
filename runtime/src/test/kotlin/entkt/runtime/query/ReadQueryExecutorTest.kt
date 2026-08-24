@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyContextProvider
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.execution.LoadPrivacyEvaluator
import entkt.runtime.query.execution.LoadPrivacyEvaluation
import entkt.runtime.query.execution.ReadQueryExecutor
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReadQueryExecutorTest {
    private data class Item(override val id: Long) : EntEntity.LongId

    private object ItemMapping : EntityMapping<Item> {
        override val entityName = "Item"
        override val clientName = "items"
        override val entityClass = Item::class
        override val table = "items"

        override fun decode(row: Map<String, Any?>): Item = Item(row.getValue("id") as Long)

        override fun edgeByStorageName(storageName: String): EdgeMapping<Item, *>? = null
    }

    private class RecordingDriver : DatabaseDriver by NoopDriver {
        var queryCalls = 0
        var countCalls = 0

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            queryCalls++
            return listOf(mapOf("id" to 1L))
        }

        override fun count(table: String, predicates: List<Predicate<*>>): Long {
            countCalls++
            return 3L
        }
    }

    private val privacyContext = PrivacyContext(Viewer.User(7L))

    private fun query(): EntityQuery<Item> = EntityQuery(
        entity = ItemMapping,
        source = QuerySource.Root(),
        predicates = listOf(Predicate.Leaf("active", Op.EQ, true)),
        orderBy = emptyList(),
        limit = null,
        offset = null,
        edges = emptyList(),
    )

    @Test
    fun `one read query executor owns entity and raw query execution`() {
        val driver = RecordingDriver()
        var interceptorRuns = 0
        val interceptors = EntInterceptorsConfig().apply {
            addEntity<Item>("items", "record") { _, _ -> interceptorRuns++ }
        }
        val executor = ReadQueryExecutor<Item>(
            driver = driver,
            privacyContextProvider = PrivacyContextProvider { privacyContext },
            registeredInterceptorsProvider = { interceptors },
            loadPrivacyEvaluatorProvider = {
                object : LoadPrivacyEvaluator {
                    override fun isConfigured(entity: EntityMapping<*>): Boolean = false

                    override fun <Entity : EntEntity<*>> evaluate(
                        entity: EntityMapping<Entity>,
                        privacyContext: PrivacyContext,
                        entities: List<Entity>,
                    ): List<LoadPrivacyEvaluation<Entity>> = error("LOAD privacy is not configured")
                }
            },
        )

        val entities = assertIs<ReadResult.Success<List<Item>>>(
            executor.readRootQuery(
                captureQuery = ::query,
                operation = ReadOperation.ALL,
                maximumRows = null,
            ),
        )
        val count = assertIs<ReadResult.Success<Long>>(executor.rawCount(::query))

        assertEquals(listOf(Item(1L)), entities.value)
        assertEquals(3L, count.value)
        assertEquals(2, interceptorRuns)
        assertEquals(1, driver.queryCalls)
        assertEquals(1, driver.countCalls)
    }
}
