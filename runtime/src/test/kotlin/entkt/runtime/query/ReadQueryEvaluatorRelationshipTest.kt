@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.driver.RelatedRow
import entkt.runtime.driver.RelatedRows
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.execution.LoadPrivacyEvaluator
import entkt.runtime.query.execution.ReadQueryEvaluator
import entkt.runtime.result.EntityKey
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.ReadResult
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ReadQueryEvaluatorRelationshipTest {
    private data class Parent(
        override val id: Long,
        val favoriteId: Long?,
        val children: List<Child> = emptyList(),
        val profile: Profile? = null,
        val favorite: Favorite? = null,
        val tags: List<Tag> = emptyList(),
    ) : EntEntity.LongId

    private data class Child(
        override val id: Long,
        val parentId: Long,
        val notes: List<Note> = emptyList(),
    ) : EntEntity.LongId

    private data class Note(
        override val id: Long,
        val childId: Long,
    ) : EntEntity.LongId

    private data class Profile(
        override val id: Long,
        val parentId: Long,
    ) : EntEntity.LongId

    private data class Favorite(override val id: Long) : EntEntity.LongId
    private data class Tag(override val id: Long) : EntEntity.LongId
    private data class Membership(override val id: Long) : EntEntity.LongId

    private abstract class Mapping<Entity : EntEntity<*>>(
        override val entityName: String,
        override val clientName: String,
        override val entityClass: KClass<Entity>,
        override val table: String,
    ) : EntityMapping<Entity> {
        override fun edgeByStorageName(storageName: String): EdgeMapping<Entity, *>? = null
    }

    private object ParentMapping : Mapping<Parent>("Parent", "parents", Parent::class, "parents") {
        override fun decode(row: Map<String, Any?>): Parent = Parent(
            id = row.getValue("id") as Long,
            favoriteId = row["favorite_id"] as Long?,
        )
    }

    private object ChildMapping : Mapping<Child>("Child", "children", Child::class, "children") {
        override fun decode(row: Map<String, Any?>): Child = Child(
            id = row.getValue("id") as Long,
            parentId = row.getValue("parent_id") as Long,
        )
    }

    private object NoteMapping : Mapping<Note>("Note", "notes", Note::class, "notes") {
        override fun decode(row: Map<String, Any?>): Note = Note(
            id = row.getValue("id") as Long,
            childId = row.getValue("child_id") as Long,
        )
    }

    private object ProfileMapping : Mapping<Profile>("Profile", "profiles", Profile::class, "profiles") {
        override fun decode(row: Map<String, Any?>): Profile = Profile(
            id = row.getValue("id") as Long,
            parentId = row.getValue("parent_id") as Long,
        )
    }

    private object FavoriteMapping : Mapping<Favorite>("Favorite", "favorites", Favorite::class, "favorites") {
        override fun decode(row: Map<String, Any?>): Favorite = Favorite(row.getValue("id") as Long)
    }

    private object TagMapping : Mapping<Tag>("Tag", "tags", Tag::class, "tags") {
        override fun decode(row: Map<String, Any?>): Tag = Tag(row.getValue("id") as Long)
    }

    private object MembershipMapping :
        Mapping<Membership>("Membership", "memberships", Membership::class, "memberships") {
        override fun decode(row: Map<String, Any?>): Membership = Membership(row.getValue("id") as Long)
    }

    private object ChildrenEdge : ToManyEdgeMapping<Parent, Child> {
        override val name = "children"
        override val storageName = "children"
        override val source = ParentMapping
        override val target = ChildMapping
        override val traversal: EdgeTraversal<Parent>? = null
        override val storageStrategy = EdgeStorage.ForeignKeyOnTarget(
            sourceColumn = "id",
            targetColumn = "parent_id",
            sourceKey = Parent::id,
            targetForeignKey = Child::parentId,
        )

        override fun attach(source: Parent, targets: List<Child>): Parent =
            source.copy(children = targets)
    }

    private object NotesEdge : ToManyEdgeMapping<Child, Note> {
        override val name = "notes"
        override val storageName = "notes"
        override val source = ChildMapping
        override val target = NoteMapping
        override val traversal: EdgeTraversal<Child>? = null
        override val storageStrategy = EdgeStorage.ForeignKeyOnTarget(
            sourceColumn = "id",
            targetColumn = "child_id",
            sourceKey = Child::id,
            targetForeignKey = Note::childId,
        )

        override fun attach(source: Child, targets: List<Note>): Child = source.copy(notes = targets)
    }

    private object ProfileEdge : ToOneEdgeMapping<Parent, Profile> {
        override val name = "profile"
        override val storageName = "profile"
        override val source = ParentMapping
        override val target = ProfileMapping
        override val traversal: EdgeTraversal<Parent>? = null
        override val storageStrategy = EdgeStorage.ForeignKeyOnTarget(
            sourceColumn = "id",
            targetColumn = "parent_id",
            sourceKey = Parent::id,
            targetForeignKey = Profile::parentId,
        )

        override fun attach(source: Parent, target: Profile?): Parent = source.copy(profile = target)
    }

    private object FavoriteEdge : ToOneEdgeMapping<Parent, Favorite> {
        override val name = "favorite"
        override val storageName = "favorite"
        override val source = ParentMapping
        override val target = FavoriteMapping
        override val traversal: EdgeTraversal<Parent>? = null
        override val storageStrategy = EdgeStorage.ForeignKeyOnSource(
            sourceColumn = "favorite_id",
            targetColumn = "id",
            sourceForeignKey = Parent::favoriteId,
            targetKey = Favorite::id,
        )

        override fun attach(source: Parent, target: Favorite?): Parent = source.copy(favorite = target)
    }

    private object TagsEdge : ToManyEdgeMapping<Parent, Tag> {
        override val name = "tags"
        override val storageName = "tags"
        override val source = ParentMapping
        override val target = TagMapping
        override val traversal: EdgeTraversal<Parent>? = null
        override val storageStrategy = EdgeStorage.Junction(
            table = "memberships",
            sourceColumn = "parent_id",
            targetColumn = "tag_id",
            junctionEntity = MembershipMapping,
            sourceKey = Parent::id,
            targetKey = Tag::id,
        )

        override fun attach(source: Parent, targets: List<Tag>): Parent = source.copy(tags = targets)
    }

    private class RowsDriver(
        private val rows: Map<String, List<Map<String, Any?>>>,
        private val capability: DirectToManyWindowCapability = DirectToManyWindowCapability.EMULATED,
        private val nativeRows: (DirectToManyQuery) -> RelatedRows = { error("native rows not configured") },
    ) : DatabaseDriver by NoopDriver {
        data class Call(
            val table: String,
            val operationPredicates: List<Predicate<*>>,
            val limit: Int?,
            val offset: Int?,
        )

        val calls = mutableListOf<Call>()
        val directCalls = mutableListOf<DirectToManyQuery>()

        override fun directToManyWindowCapability(): DirectToManyWindowCapability = capability

        override fun queryDirectToMany(query: DirectToManyQuery): RelatedRows {
            directCalls += query
            return nativeRows(query)
        }

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            calls += Call(table, predicates, limit, offset)
            return rows.getValue(table)
                .filter { row -> predicates.all { matches(it, row) } }
                .let { ordered(it, orderBy) }
                .drop(offset ?: 0)
                .let { selected -> limit?.let(selected::take) ?: selected }
        }

        private fun matches(predicate: Predicate<*>, row: Map<String, Any?>): Boolean =
            when (predicate) {
                is Predicate.Leaf -> when (predicate.op) {
                    Op.IN -> row[predicate.field] in values(predicate.value)
                    Op.EQ -> row[predicate.field] == predicate.value
                    else -> true
                }

                is Predicate.And -> matches(predicate.left, row) && matches(predicate.right, row)
                is Predicate.Or -> matches(predicate.left, row) || matches(predicate.right, row)
                else -> true
            }

        private fun values(value: Any?): List<Any?> = when (value) {
            is Iterable<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> emptyList()
        }

        private fun ordered(
            source: List<Map<String, Any?>>,
            orderBy: List<OrderField<*>>,
        ): List<Map<String, Any?>> {
            var result = source
            for (order in orderBy.asReversed()) {
                val comparator = compareBy<Map<String, Any?>> { (it[order.field] as Number).toLong() }
                result = if (order.direction == OrderDirection.ASC) {
                    result.sortedWith(comparator)
                } else {
                    result.sortedWith(comparator.reversed())
                }
            }
            return result
        }
    }

    private class RootRowsDriver(
        private val delegate: DatabaseDriver,
        private val parents: List<Parent>,
    ) : DatabaseDriver by delegate {
        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            if (table != ParentMapping.table) {
                return delegate.query(table, predicates, orderBy, limit, offset)
            }
            val rows = parents.map { parent ->
                mapOf(
                    "id" to parent.id,
                    "favorite_id" to parent.favoriteId,
                )
            }
            return rows.drop(offset ?: 0).let { selected -> limit?.let(selected::take) ?: selected }
        }
    }

    private class Privacy(
        private val configured: Set<EntityMapping<*>> = emptySet(),
        private val deniedIds: Set<Any> = emptySet(),
    ) : LoadPrivacyEvaluator {
        override fun isConfigured(entity: EntityMapping<*>): Boolean = entity in configured

        override fun <Entity : EntEntity<*>> evaluate(
            entity: EntityMapping<Entity>,
            privacyContext: PrivacyContext,
            entities: List<Entity>,
        ): List<PrivacyDenial?> = entities.map { value ->
            if (value.id in deniedIds) {
                PrivacyDenial(entity.entityName, EntityKey("id", value.id), "hidden")
            } else {
                null
            }
        }
    }

    private val privacyContext = PrivacyContext(Viewer.User(7L))

    @Test
    fun `direct to-many windows each parent then recursively loads retained targets once`() {
        val driver = RowsDriver(
            mapOf(
                "children" to listOf(
                    mapOf("id" to 1L, "parent_id" to 10L),
                    mapOf("id" to 2L, "parent_id" to 10L),
                    mapOf("id" to 3L, "parent_id" to 10L),
                    mapOf("id" to 4L, "parent_id" to 20L),
                    mapOf("id" to 5L, "parent_id" to 20L),
                ),
                "notes" to listOf(
                    mapOf("id" to 20L, "child_id" to 2L),
                    mapOf("id" to 50L, "child_id" to 5L),
                    mapOf("id" to 99L, "child_id" to 3L),
                ),
            ),
        )
        val contexts = mutableListOf<QueryContext>()
        val interceptors = EntInterceptorsConfig().apply {
            addEntity<Child>("children", "record") { _, context -> contexts += context }
            addEntity<Note>("notes", "record") { _, context -> contexts += context }
        }
        val noteQuery = query(NoteMapping)
        val childQuery = query(
            mapping = ChildMapping,
            orderBy = listOf(OrderField("id", OrderDirection.ASC)),
            limit = 1,
            offset = 1,
            edges = listOf(EdgeSelection(NotesEdge, noteQuery, EdgeVisibility.REQUIRE_VISIBLE)),
        )
        val parentQuery = query(
            mapping = ParentMapping,
            edges = listOf(EdgeSelection(ChildrenEdge, childQuery, EdgeVisibility.REQUIRE_VISIBLE)),
        )

        val loaded = loadGraph(
            driver = driver,
            query = parentQuery,
            parents = listOf(Parent(10, null), Parent(20, null)),
            interceptors = interceptors,
        )

        assertEquals(listOf(2L), loaded[0].children.map { it.id })
        assertEquals(listOf(20L), loaded[0].children.single().notes.map { it.id })
        assertEquals(listOf(5L), loaded[1].children.map { it.id })
        assertEquals(listOf(50L), loaded[1].children.single().notes.map { it.id })
        assertEquals(listOf("children", "notes"), contexts.mapNotNull(QueryContext::edgeName))
        assertEquals(
            listOf("children", "notes"),
            contexts.last().path.map(EdgeStep::edgeName),
        )
        assertEquals(listOf("children", "notes"), driver.calls.map { it.table })
        assertEquals(listOf(null, null), driver.calls.map { it.limit })
    }

    @Test
    fun `native direct to-many rows are not windowed a second time`() {
        val childRows = listOf(
            RelatedRow(10L, mapOf("id" to 2L, "parent_id" to 10L)),
            RelatedRow(20L, mapOf("id" to 5L, "parent_id" to 20L)),
        )
        val driver = RowsDriver(
            rows = emptyMap(),
            capability = DirectToManyWindowCapability.NATIVE,
            nativeRows = { RelatedRows(childRows, EagerWindowStrategy.STORAGE_NATIVE) },
        )
        val childQuery = query(
            mapping = ChildMapping,
            orderBy = listOf(OrderField("id", OrderDirection.ASC)),
            limit = 1,
            offset = 1,
        )
        val parentQuery = query(
            ParentMapping,
            edges = listOf(EdgeSelection(ChildrenEdge, childQuery, EdgeVisibility.REQUIRE_VISIBLE)),
        )

        val loaded = loadGraph(
            driver = driver,
            query = parentQuery,
            parents = listOf(Parent(10, null), Parent(20, null)),
        )

        assertEquals(listOf(2L), loaded[0].children.map { it.id })
        assertEquals(listOf(5L), loaded[1].children.map { it.id })
        assertEquals(1, driver.directCalls.size)
    }

    @Test
    fun `to-one and junction strategies correlate through typed keys`() {
        val driver = RowsDriver(
            mapOf(
                "profiles" to listOf(
                    mapOf("id" to 101L, "parent_id" to 10L),
                    mapOf("id" to 201L, "parent_id" to 20L),
                ),
                "favorites" to listOf(mapOf("id" to 7L), mapOf("id" to 8L)),
                "memberships" to listOf(
                    mapOf("id" to 1L, "parent_id" to 10L, "tag_id" to 3L),
                    mapOf("id" to 2L, "parent_id" to 10L, "tag_id" to 3L),
                    mapOf("id" to 3L, "parent_id" to 10L, "tag_id" to 4L),
                    mapOf("id" to 4L, "parent_id" to 20L, "tag_id" to 4L),
                ),
                "tags" to listOf(mapOf("id" to 4L), mapOf("id" to 3L)),
            ),
        )
        val parentQuery = query(
            ParentMapping,
            edges = listOf(
                EdgeSelection(ProfileEdge, query(ProfileMapping), EdgeVisibility.REQUIRE_VISIBLE),
                EdgeSelection(FavoriteEdge, query(FavoriteMapping), EdgeVisibility.REQUIRE_VISIBLE),
                EdgeSelection(
                    TagsEdge,
                    query(TagMapping, orderBy = listOf(OrderField("id", OrderDirection.ASC))),
                    EdgeVisibility.REQUIRE_VISIBLE,
                ),
            ),
        )

        val loaded = loadGraph(
            driver = driver,
            query = parentQuery,
            parents = listOf(Parent(10, 7), Parent(20, 8)),
        )

        assertEquals(101L, loaded[0].profile?.id)
        assertEquals(201L, loaded[1].profile?.id)
        assertEquals(7L, loaded[0].favorite?.id)
        assertEquals(8L, loaded[1].favorite?.id)
        assertEquals(listOf(3L, 4L), loaded[0].tags.map { it.id })
        assertEquals(listOf(4L), loaded[1].tags.map { it.id })
        assertEquals(1, loaded[0].tags.count { it.id == 3L })
        assertEquals(
            listOf("profiles", "favorites", "memberships", "tags"),
            driver.calls.map { it.table },
        )
    }

    @Test
    fun `edge privacy either filters denied targets or fails with the complete eager path`() {
        val rows = mapOf(
            "children" to listOf(
                mapOf("id" to 1L, "parent_id" to 10L),
                mapOf("id" to 2L, "parent_id" to 10L),
            ),
        )
        val filteredQuery = query(
            ParentMapping,
            edges = listOf(
                EdgeSelection(
                    ChildrenEdge,
                    query(ChildMapping),
                    EdgeVisibility.FILTER_INVISIBLE,
                ),
            ),
        )
        val privacy = Privacy(setOf(ChildMapping), deniedIds = setOf(2L))

        val filtered = loadGraph(
            driver = RowsDriver(rows),
            query = filteredQuery,
            parents = listOf(Parent(10, null)),
            privacy = privacy,
        )
        assertEquals(listOf(1L), filtered.single().children.map { it.id })

        val strictQuery = query(
            ParentMapping,
            edges = listOf(
                EdgeSelection(
                    ChildrenEdge,
                    query(ChildMapping),
                    EdgeVisibility.REQUIRE_VISIBLE,
                ),
            ),
        )
        val failure = assertFailsWith<EntPrivacyDeniedException> {
            loadGraph(
                driver = RowsDriver(rows),
                query = strictQuery,
                parents = listOf(Parent(10, null)),
                privacy = privacy,
            )
        }
        val origin = assertIs<LoadDenialOrigin.EagerEdge>(failure.origin)
        assertEquals(listOf("children"), origin.path.map { it.edgeName })
        assertEquals(2L, failure.denials.single().entityKey.value)
    }

    private fun loadGraph(
        driver: DatabaseDriver,
        query: EntityQuery<Parent>,
        parents: List<Parent>,
        privacy: LoadPrivacyEvaluator = Privacy(),
        interceptors: EntInterceptorsConfig = EntInterceptorsConfig(),
    ): List<Parent> {
        val evaluator = ReadQueryEvaluator<Parent>(
            driver = RootRowsDriver(driver, parents),
            privacyContextProvider = { privacyContext },
            registeredInterceptorsProvider = { interceptors },
            loadPrivacyEvaluatorProvider = { privacy },
        )
        return when (
            val result = evaluator.readRootQuery(
                captureQuery = { query },
                operation = ReadOperation.ALL,
                maximumRows = null,
            )
        ) {
            is ReadResult.Success -> result.value
            is ReadResult.Failed -> throw result.exception
        }
    }

    private fun <Entity : EntEntity<*>> query(
        mapping: EntityMapping<Entity>,
        orderBy: List<OrderField<Entity>> = emptyList(),
        limit: Int? = null,
        offset: Int? = null,
        edges: List<EdgeSelection<Entity, *>> = emptyList(),
    ): EntityQuery<Entity> = EntityQuery(
        entity = mapping,
        source = QuerySource.Root(),
        predicates = emptyList(),
        orderBy = orderBy,
        limit = limit,
        offset = offset,
        edges = edges,
    )
}
