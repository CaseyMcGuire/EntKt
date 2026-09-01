@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.entity

import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EdgeTraversal
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class EntityDescriptorTest {
    private data class Widget(
        override val id: Long,
    ) : EntEntity.LongId

    private object WidgetDescriptor : EntityDescriptor<Widget, Long> {
        override val entityName: String = "Widget"
        override val clientName: String = "widget"
        override val entityClass: KClass<Widget> = Widget::class
        override val schema: EntitySchema = EntitySchema(
            table = "widgets",
            idColumn = "widget_id",
            idStrategy = IdStrategy.EXPLICIT,
            columns = emptyList(),
            edges = emptyMap(),
        )
        override val edgesByStorageName: Map<String, EdgeMapping<Widget, *>> by lazy {
            mapOf("friends" to FriendsEdge)
        }

        override fun decode(row: Map<String, Any?>): Widget =
            Widget(row.getValue("widget_id") as Long)
    }

    private object FriendsEdge : EdgeMapping<Widget, Widget> {
        override val name: String = "friends"
        override val storageName: String = "friends"
        override val source: EntityMapping<Widget>
            get() = WidgetDescriptor
        override val target: EntityMapping<Widget>
            get() = WidgetDescriptor
        override val traversal: EdgeTraversal<Widget>? = null
    }

    @Test
    fun `derives storage identity from schema and resolves typed edges`() {
        assertEquals("widgets", WidgetDescriptor.table)
        assertEquals("widget_id", WidgetDescriptor.idColumn)
        assertSame(FriendsEdge, WidgetDescriptor.edgeByStorageName("friends"))
        assertNull(WidgetDescriptor.edgeByStorageName("missing"))
        assertEquals(
            7L,
            decodedId(WidgetDescriptor, mapOf("widget_id" to 7L)),
        )
    }

    private fun <Entity : EntEntity<ID>, ID : Any> decodedId(
        descriptor: EntityDescriptor<Entity, ID>,
        row: Map<String, Any?>,
    ): ID = descriptor.decode(row).id
}
