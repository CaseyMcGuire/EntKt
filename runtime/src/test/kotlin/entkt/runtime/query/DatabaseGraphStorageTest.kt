@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.NoopDriver
import entkt.runtime.driver.RelatedRow
import entkt.runtime.driver.RelatedRows
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.execution.DatabaseGraphStorage
import entkt.runtime.query.execution.ReadQueryCompiler
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseGraphStorageTest {
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

        override fun attach(source: Parent, targets: List<Child>): Parent = source.copy(children = targets)
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
            val predicates: List<Predicate<*>>,
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

    private val privacyContext = PrivacyContext(Viewer.User(7L))

    @Test
    fun `root loading prepares the query and preserves the terminal row bound`() {
        val driver = RowsDriver(
            rows = mapOf(
                "parents" to listOf(
                    mapOf("id" to 1L, "favorite_id" to null),
                    mapOf("id" to 2L, "favorite_id" to null),
                ),
            ),
        )
        val contexts = mutableListOf<QueryContext>()
        val interceptors = EntInterceptorsConfig().apply {
            addEntity<Parent>("parents", "only-two") { scope, context ->
                contexts += context
                scope.addPredicate(Predicate.Leaf("id", Op.EQ, 2L))
            }
        }

        val loaded = storage(driver, interceptors).loadRoot(
            query = query(ParentMapping),
            operation = ReadOperation.FIRST,
            maximumRows = 1,
            privacyContext = privacyContext,
        )

        assertEquals(listOf(Parent(2, null)), loaded)
        assertEquals(1, driver.calls.single().limit)
        assertEquals(ReadOperation.FIRST, contexts.single().operation)
    }

    @Test
    fun `direct to-many windows each source and preserves target interceptor context`() {
        val driver = RowsDriver(
            mapOf(
                "children" to listOf(
                    mapOf("id" to 1L, "parent_id" to 10L),
                    mapOf("id" to 2L, "parent_id" to 10L),
                    mapOf("id" to 3L, "parent_id" to 10L),
                    mapOf("id" to 4L, "parent_id" to 20L),
                    mapOf("id" to 5L, "parent_id" to 20L),
                ),
            ),
        )
        val contexts = mutableListOf<QueryContext>()
        val interceptors = EntInterceptorsConfig().apply {
            addEntity<Child>("children", "record") { _, context -> contexts += context }
        }
        val relationship = storage(driver, interceptors).loadRelationship(
            selection = EdgeSelection(
                ChildrenEdge,
                query(
                    mapping = ChildMapping,
                    orderBy = listOf(OrderField("id", OrderDirection.ASC)),
                    limit = 1,
                    offset = 1,
                ),
                EdgeVisibility.REQUIRE_VISIBLE,
            ),
            sources = listOf(Parent(10, null), Parent(20, null)),
            privacyContext = privacyContext,
            rootEntity = Parent::class,
            targetPath = listOf(EdgeStep(Parent::class, "children", Child::class)),
        )

        val loaded = relationship.attach(relationship.targets)

        assertEquals(listOf(2L), loaded[0].children.map(Child::id))
        assertEquals(listOf(5L), loaded[1].children.map(Child::id))
        assertEquals(listOf(2L, 5L), relationship.targets.map(Child::id))
        assertEquals("children", contexts.single().edgeName)
        assertEquals(listOf("children"), contexts.single().path.map(EdgeStep::edgeName))
        assertEquals(listOf(null), driver.calls.map { it.limit })
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
        val relationship = storage(driver).loadRelationship(
            selection = EdgeSelection(
                ChildrenEdge,
                query(
                    mapping = ChildMapping,
                    orderBy = listOf(OrderField("id", OrderDirection.ASC)),
                    limit = 1,
                    offset = 1,
                ),
                EdgeVisibility.REQUIRE_VISIBLE,
            ),
            sources = listOf(Parent(10, null), Parent(20, null)),
            privacyContext = privacyContext,
            rootEntity = Parent::class,
            targetPath = listOf(EdgeStep(Parent::class, "children", Child::class)),
        )

        val loaded = relationship.attach(relationship.targets)

        assertEquals(listOf(2L), loaded[0].children.map(Child::id))
        assertEquals(listOf(5L), loaded[1].children.map(Child::id))
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
        val storage = storage(driver)
        var sources = listOf(Parent(10, 7), Parent(20, 8))

        val profiles = storage.loadRelationship(
            EdgeSelection(ProfileEdge, query(ProfileMapping), EdgeVisibility.REQUIRE_VISIBLE),
            sources,
            privacyContext,
            Parent::class,
            listOf(EdgeStep(Parent::class, "profile", Profile::class)),
        )
        sources = profiles.attach(profiles.targets)

        val favorites = storage.loadRelationship(
            EdgeSelection(FavoriteEdge, query(FavoriteMapping), EdgeVisibility.REQUIRE_VISIBLE),
            sources,
            privacyContext,
            Parent::class,
            listOf(EdgeStep(Parent::class, "favorite", Favorite::class)),
        )
        sources = favorites.attach(favorites.targets)

        val tags = storage.loadRelationship(
            EdgeSelection(
                TagsEdge,
                query(TagMapping, orderBy = listOf(OrderField("id", OrderDirection.ASC))),
                EdgeVisibility.REQUIRE_VISIBLE,
            ),
            sources,
            privacyContext,
            Parent::class,
            listOf(EdgeStep(Parent::class, "tags", Tag::class)),
        )
        sources = tags.attach(tags.targets)

        assertEquals(101L, sources[0].profile?.id)
        assertEquals(201L, sources[1].profile?.id)
        assertEquals(7L, sources[0].favorite?.id)
        assertEquals(8L, sources[1].favorite?.id)
        assertEquals(listOf(3L, 4L), sources[0].tags.map(Tag::id))
        assertEquals(listOf(4L), sources[1].tags.map(Tag::id))
        assertEquals(1, sources[0].tags.count { it.id == 3L })
        assertEquals(
            listOf("profiles", "favorites", "memberships", "tags"),
            driver.calls.map { it.table },
        )
    }

    private fun storage(
        driver: DatabaseDriver,
        interceptors: EntInterceptorsConfig = EntInterceptorsConfig(),
    ): DatabaseGraphStorage = DatabaseGraphStorage(
        driver = driver,
        queryCompiler = ReadQueryCompiler(
            driver = driver,
            registeredInterceptorsProvider = { interceptors },
        ),
    )

    private fun <Entity : EntEntity<*>> query(
        mapping: EntityMapping<Entity>,
        orderBy: List<OrderField<Entity>> = emptyList(),
        limit: Int? = null,
        offset: Int? = null,
    ): EntityQuery<Entity> = EntityQuery(
        entity = mapping,
        source = QuerySource.Root(),
        predicates = emptyList(),
        orderBy = orderBy,
        limit = limit,
        offset = offset,
        edges = emptyList(),
    )
}
