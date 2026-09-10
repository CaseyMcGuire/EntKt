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
import entkt.runtime.result.EntQueryConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class EntityQueryScopeTest {
    private data class Node(override val id: Long) : EntEntity.LongId

    private object Mapping : EntityMapping<Node> {
        override val entityName = "Node"
        override val clientName = "nodes"
        override val entityClass = Node::class
        override val table = "nodes"
        override fun decode(row: Map<String, Any?>) = Node(row.getValue("id") as Long)
        override fun edgeByStorageName(storageName: String): EdgeMapping<Node, *>? =
            listOf(Children, Friends).firstOrNull { it.storageName == storageName }
    }

    private open class NodeEdge(override val name: String) : ToManyEdgeMapping<Node, Node> {
        override val storageName = name
        override val source = Mapping
        override val target = Mapping
        override val traversal = EdgeTraversal.Direct<Node>("parent", "id")
        override val storageStrategy = EdgeStorage.ForeignKeyOnTarget<Node, Node, Long>(
            sourceColumn = "id", targetColumn = "parent_id", sourceKey = Node::id, targetForeignKey = Node::id,
        )
        override fun attach(source: Node, targets: List<Node>): Node = source
    }

    private object Children : NodeEdge("children")
    private object Friends : NodeEdge("friends")

    private class Scope(
        driver: DatabaseDriver = NoopDriver,
        query: EntityQuery<Node> = EntityQuery(Mapping),
    ) : EntityQueryScope<Node, Scope>(driver, query, listOf(Children, Friends)) {
        override val self: Scope get() = this
        fun loadChildren(block: Scope.() -> Unit = {}): EdgeLoad<Scope> = loadEdge(Children, Scope(driver), block)
        fun loadFriends(block: Scope.() -> Unit = {}): EdgeLoad<Scope> = loadEdge(Friends, Scope(driver), block)
    }

    @Test
    fun `statements accumulate and build detaches caller and scope inputs`() {
        val ids = mutableListOf(1L, 2L)
        val scope = Scope().apply {
            where(Predicate.Leaf("id", Op.IN, ids))
            where(Predicate.Leaf("active", Op.EQ, true))
            orderBy(OrderField("id", OrderDirection.ASC))
            orderBy(OrderField("active", OrderDirection.DESC))
            limit(10)
            offset(3)
        }
        val built = scope.buildForInternalUse()
        ids.clear()
        scope.where(Predicate.Leaf("id", Op.EQ, 99L)).limit(1).offset(0)

        assertEquals(2, built.predicates.size)
        assertEquals(listOf(1L, 2L), assertIs<Predicate.Leaf<Node>>(built.predicates.first()).value)
        assertEquals(listOf("id", "active"), built.orderBy.map { it.field })
        assertEquals(10, built.limit)
        assertEquals(3, built.offset)
    }

    @Test
    fun `escaped nested scopes and edge handles cannot mutate a built graph`() {
        val scope = Scope()
        lateinit var nested: Scope
        val handle = scope.loadChildren {
            nested = this
            limit(3)
            loadFriends().filterVisible()
        }
        val built = scope.buildForInternalUse()
        handle.filterVisible()
        nested.limit(99).loadChildren()

        val selected = built.edges.single()
        assertEquals(EdgeVisibility.REQUIRE_VISIBLE, selected.visibility)
        assertEquals(3, selected.target.limit)
        assertSame(Friends, selected.target.edges.single().edge)
        assertEquals(EdgeVisibility.FILTER_INVISIBLE, selected.target.edges.single().visibility)
        assertEquals(EdgeVisibility.FILTER_INVISIBLE, scope.buildForInternalUse().edges.single().visibility)
    }

    @Test
    fun `selection order follows schema declaration and visibility is per edge`() {
        val scope = Scope()
        scope.loadFriends().filterVisible()
        scope.loadChildren()

        val edges = scope.buildForInternalUse().edges
        assertEquals(listOf(Children, Friends), edges.map { it.edge })
        assertEquals(
            listOf(EdgeVisibility.REQUIRE_VISIBLE, EdgeVisibility.FILTER_INVISIBLE),
            edges.map { it.visibility },
        )
    }

    @Test
    fun `duplicate selection rejects before running configuration`() {
        val scope = Scope()
        scope.loadChildren()
        var calls = 0
        assertFailsWith<EntQueryConfigurationException> { scope.loadChildren { calls++ } }
        assertEquals(0, calls)
        assertEquals(1, scope.buildForInternalUse().edges.size)
    }

    @Test
    fun `failed edge configuration can be retried and reentrant selection is rejected`() {
        val scope = Scope()
        assertFailsWith<EntQueryConfigurationException> {
            scope.loadChildren { scope.loadChildren() }
        }
        assertEquals(emptyList(), scope.buildForInternalUse().edges)
        scope.loadChildren { limit(2) }
        assertEquals(2, scope.buildForInternalUse().edges.single().target.limit)
    }

    @Test
    fun `refinement retains immutable source and existing selections`() {
        val original = Scope().apply { loadChildren(); limit(10) }.buildForInternalUse()
        val refined = Scope(query = original).apply { loadFriends(); offset(5) }.buildForInternalUse()

        assertEquals(1, original.edges.size)
        assertEquals(null, original.offset)
        assertEquals(2, refined.edges.size)
        assertEquals(10, refined.limit)
        assertEquals(5, refined.offset)
        assertSame(original.edges.single(), refined.edges.first())
        assertSame(original.source, refined.source)
        assertFailsWith<EntQueryConfigurationException> { Scope(query = original).loadChildren() }
    }

    @Test
    fun `invalid bounds leave scope configuration unchanged`() {
        val scope = Scope().limit(4).offset(2)
        assertFailsWith<IllegalArgumentException> { scope.limit(-1) }
        assertFailsWith<IllegalArgumentException> { scope.offset(-1) }
        assertEquals(4, scope.buildForInternalUse().limit)
        assertEquals(2, scope.buildForInternalUse().offset)
    }

    @Test
    fun `extracting a refinement predicate cannot expose the original query operands`() {
        val original = Scope().where(Predicate.Leaf("id", Op.IN, mutableListOf(1L))).buildForInternalUse()
        val scope = Scope(query = original)
        val extracted = assertIs<Predicate.Leaf<Node>>(scope.combinedPredicate())
        (extracted.value as MutableList<*>).clear()

        assertEquals(listOf(1L), assertIs<Predicate.Leaf<Node>>(original.predicates.single()).value)
    }

    @Test
    fun `capacity is checked before enumerating oversized predicate operands`() {
        val oversized = object : AbstractList<Long>() {
            override val size = 100
            override fun get(index: Int): Long = error("Must reject before reading elements")
        }
        val failure = IllegalArgumentException("too many binds")
        val driver = object : DatabaseDriver by NoopDriver {
            override fun requireBindCapacity(minimumParameters: Long, table: String) {
                assertEquals(100L, minimumParameters)
                assertEquals("nodes", table)
                throw failure
            }
        }
        val scope = Scope(driver).where(Predicate.Leaf("id", Op.IN, oversized))
        assertSame(failure, assertFailsWith<IllegalArgumentException> { scope.buildForInternalUse() })
    }
}
