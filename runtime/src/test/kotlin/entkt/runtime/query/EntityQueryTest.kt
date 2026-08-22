@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class EntityQueryTest {
    private data class Parent(
        override val id: Long,
        val children: List<Child> = emptyList(),
    ) : EntEntity.LongId

    private data class Child(
        override val id: Long,
        val parentId: Long,
    ) : EntEntity.LongId

    private object ParentMapping : TestEntityMapping<Parent>("Parent", Parent::class) {
        override fun decode(row: Map<String, Any?>): Parent = Parent(row.getValue("id") as Long)
    }

    private object ChildMapping : TestEntityMapping<Child>("Child", Child::class) {
        override fun decode(row: Map<String, Any?>): Child = Child(
            id = row.getValue("id") as Long,
            parentId = row.getValue("parent_id") as Long,
        )
    }

    private object AlternateChildMapping : TestEntityMapping<Child>("AlternateChild", Child::class) {
        override fun decode(row: Map<String, Any?>): Child = Child(
            id = row.getValue("id") as Long,
            parentId = row.getValue("parent_id") as Long,
        )
    }

    private object ChildrenEdge : ToManyEdgeMapping<Parent, Child> {
        override val name: String = "children"
        override val storageName: String = "children"
        override val source: EntityMapping<Parent> = ParentMapping
        override val target: EntityMapping<Child> = ChildMapping
        override val traversal: EdgeTraversal<Parent> = EdgeTraversal.Direct(
            inverseStorageName = "parent",
            selectedColumn = "id",
        )
        override val storageStrategy: EdgeStorage<Parent, Child> =
            EdgeStorage.ForeignKeyOnTarget<Parent, Child, Long>(
                sourceColumn = "id",
                targetColumn = "parent_id",
                sourceKey = Parent::id,
                targetForeignKey = Child::parentId,
            )

        override fun attach(source: Parent, targets: List<Child>): Parent =
            source.copy(children = targets)
    }

    @Test
    fun `capture detaches mutable query inputs and retains recursive edges`() {
        val ids = mutableListOf(1L, 2L)
        val predicates = mutableListOf<Predicate<Parent>>(
            Predicate.Leaf("id", Op.IN, ids),
        )
        val orderBy = mutableListOf(
            OrderField<Parent>("id", OrderDirection.ASC),
        )
        val childQuery = childQuery()
        val selectedChildren = EdgeSelection(
            edge = ChildrenEdge,
            target = childQuery,
            visibility = EdgeVisibility.FILTER_INVISIBLE,
        )
        val edges = mutableListOf<EdgeSelection<Parent, *>>(selectedChildren)

        val captured = EntityQuery(
            entity = ParentMapping,
            source = QuerySource.Root(),
            predicates = predicates,
            orderBy = orderBy,
            limit = 5,
            offset = 2,
            edges = edges,
        )

        ids += 3L
        predicates.clear()
        orderBy.clear()
        edges.clear()

        val capturedPredicate = assertIs<Predicate.Leaf<Parent>>(captured.predicates.single())
        assertEquals(listOf(1L, 2L), capturedPredicate.value)
        assertEquals("id", captured.orderBy.single().field)
        assertSame(selectedChildren, captured.edges.single())
        assertSame(childQuery, captured.edges.single().target)
        assertEquals(5, captured.limit)
        assertEquals(2, captured.offset)
    }

    @Test
    fun `capture detaches array operands`() {
        val ids = longArrayOf(1L, 2L)
        val captured = EntityQuery(
            entity = ParentMapping,
            source = QuerySource.Root(),
            predicates = listOf(Predicate.Leaf("id", Op.IN, ids)),
            orderBy = emptyList(),
            limit = null,
            offset = null,
            edges = emptyList(),
        )

        ids[0] = 99L

        val predicate = assertIs<Predicate.Leaf<Parent>>(captured.predicates.single())
        assertContentEquals(longArrayOf(1L, 2L), predicate.value as LongArray)
    }

    @Test
    fun `traversal source retains the typed source query and edge`() {
        val source = EntityQuery(
            entity = ParentMapping,
            source = QuerySource.Root(),
            predicates = emptyList(),
            orderBy = emptyList(),
            limit = null,
            offset = null,
            edges = emptyList(),
        )
        val traversal = QuerySource.Traversal(source, ChildrenEdge)

        val target = EntityQuery(
            entity = ChildMapping,
            source = traversal,
            predicates = emptyList(),
            orderBy = emptyList(),
            limit = null,
            offset = null,
            edges = emptyList(),
        )

        assertSame(traversal, target.source)
        assertSame(source, traversal.source)
        assertSame(ChildrenEdge, traversal.edge)
    }

    @Test
    fun `selected edge rejects a target query with a different adapter`() {
        val mismatchedTarget = childQuery(AlternateChildMapping)

        val failure = assertFailsWith<IllegalArgumentException> {
            EdgeSelection(
                edge = ChildrenEdge,
                target = mismatchedTarget,
                visibility = EdgeVisibility.REQUIRE_VISIBLE,
            )
        }

        assertEquals(
            "Edge 'children' targets 'Child', but its selected query targets 'AlternateChild'",
            failure.message,
        )
    }

    @Test
    fun `edge adapter provides typed correlation and immutable attachment`() {
        val storage = assertIs<EdgeStorage.ForeignKeyOnTarget<Parent, Child, Long>>(
            ChildrenEdge.storageStrategy,
        )
        val child = Child(id = 7L, parentId = 3L)
        val parent = Parent(id = 3L)

        assertEquals(3L, storage.targetForeignKey(child))
        assertEquals(listOf(child), ChildrenEdge.attach(parent, listOf(child)).children)
        assertEquals(emptyList(), parent.children)
    }

    @Test
    fun `negative query bounds fail at capture`() {
        assertFailsWith<IllegalArgumentException> {
            EntityQuery(
                entity = ParentMapping,
                source = QuerySource.Root(),
                predicates = emptyList(),
                orderBy = emptyList(),
                limit = -1,
                offset = null,
                edges = emptyList(),
            )
        }
    }

    private fun childQuery(
        adapter: EntityMapping<Child> = ChildMapping,
    ): EntityQuery<Child> = EntityQuery(
        entity = adapter,
        source = QuerySource.Root(),
        predicates = emptyList(),
        orderBy = emptyList(),
        limit = null,
        offset = null,
        edges = emptyList(),
    )

    private abstract class TestEntityMapping<Entity : EntEntity<*>>(
        final override val entityName: String,
        final override val entityClass: KClass<Entity>,
    ) : EntityMapping<Entity> {
        final override val clientName: String = entityName.replaceFirstChar(Char::lowercase)
        final override val table: String = entityName.lowercase()

        final override fun edgeByStorageName(storageName: String): EdgeMapping<Entity, *>? = null
    }
}
