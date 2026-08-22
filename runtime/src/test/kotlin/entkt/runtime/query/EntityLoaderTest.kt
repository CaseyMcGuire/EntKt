@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.query.execution.EntityLoader
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityLoaderTest {
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
        var receivedLimit: Int? = null
        var receivedOffset: Int? = null

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            receivedLimit = limit
            receivedOffset = offset
            return listOf(mapOf("id" to 1L), mapOf("id" to 2L))
        }
    }

    @Test
    fun `loads and decodes one entity type using exact caller supplied bounds`() {
        val driver = RecordingDriver()
        val query = FrozenQuerySpec<Item>(
            table = "items",
            predicates = emptyList(),
            orderBy = listOf(OrderField("id", OrderDirection.ASC)),
            limit = 8,
            offset = 9,
            flags = emptySet(),
            annotations = emptyMap(),
        )

        val loaded = EntityLoader(driver).load(
            entity = ItemMapping,
            query = query,
            limit = 1,
            offset = 3,
            maximumEntities = 1,
        )

        assertEquals(listOf(Item(1)), loaded)
        assertEquals(1, driver.receivedLimit)
        assertEquals(3, driver.receivedOffset)
    }
}
