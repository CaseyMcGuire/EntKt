@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EdgeStorage
import entkt.runtime.query.EdgeTraversal
import entkt.runtime.query.EdgeVisibility
import entkt.runtime.query.EntInterceptorsConfig
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.QueryContext
import entkt.runtime.query.QuerySource
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.ToManyEdgeMapping
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JunctionRelationshipReaderTest {
    private data class Source(
        override val id: Long,
        val targets: List<Target> = emptyList(),
    ) : EntEntity.LongId

    private data class Target(
        override val id: Long,
        val evaluated: Boolean = false,
    ) : EntEntity.LongId

    private data class Membership(override val id: Long) : EntEntity.LongId

    private abstract class Mapping<Entity : EntEntity<*>>(
        override val entityName: String,
        override val clientName: String,
        override val entityClass: KClass<Entity>,
        override val table: String,
    ) : EntityMapping<Entity> {
        override fun edgeByStorageName(storageName: String): EdgeMapping<Entity, *>? = null
    }

    private object SourceMapping : Mapping<Source>("Source", "sources", Source::class, "sources") {
        override fun decode(row: Map<String, Any?>): Source = Source(row.getValue("id") as Long)
    }

    private object TargetMapping : Mapping<Target>("Target", "targets", Target::class, "targets") {
        override fun decode(row: Map<String, Any?>): Target = Target(row.getValue("id") as Long)
    }

    private object MembershipMapping :
        Mapping<Membership>("Membership", "memberships", Membership::class, "memberships") {
        override fun decode(row: Map<String, Any?>): Membership = Membership(row.getValue("id") as Long)
    }

    private object TargetsEdge : ToManyEdgeMapping<Source, Target> {
        override val name = "targets"
        override val storageName = "targets"
        override val source = SourceMapping
        override val target = TargetMapping
        override val traversal: EdgeTraversal<Source>? = null
        override val storageStrategy = EdgeStorage.Junction(
            table = "memberships",
            sourceColumn = "source_id",
            targetColumn = "target_id",
            junctionEntity = MembershipMapping,
            sourceKey = Source::id,
            targetKey = Target::id,
        )

        override fun attach(source: Source, targets: List<Target>): Source = source.copy(targets = targets)
    }

    private class RowsDriver(
        private val rows: Map<String, List<Map<String, Any?>>>,
    ) : DatabaseDriver by NoopDriver {
        data class Call(
            val table: String,
            val limit: Int?,
            val offset: Int?,
        )

        val calls = mutableListOf<Call>()

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            calls += Call(table, limit, offset)
            return rows.getValue(table)
                .filter { row -> predicates.all { matches(it, row) } }
                .let { ordered(it, orderBy) }
                .drop(offset ?: 0)
                .let { selected -> limit?.let(selected::take) ?: selected }
        }

        private fun matches(predicate: Predicate<*>, row: Map<String, Any?>): Boolean =
            when (predicate) {
                is Predicate.Leaf -> when (predicate.op) {
                    Op.IN -> row[predicate.field] in (predicate.value as Collection<*>)
                    else -> true
                }

                is Predicate.And -> matches(predicate.left, row) && matches(predicate.right, row)
                is Predicate.Or -> matches(predicate.left, row) || matches(predicate.right, row)
                else -> true
            }

        private fun ordered(
            rows: List<Map<String, Any?>>,
            orderBy: List<OrderField<*>>,
        ): List<Map<String, Any?>> {
            var orderedRows = rows
            for (order in orderBy.asReversed()) {
                val comparator = compareBy<Map<String, Any?>> {
                    (it[order.field] as Number).toLong()
                }
                orderedRows = if (order.direction == OrderDirection.ASC) {
                    orderedRows.sortedWith(comparator)
                } else {
                    orderedRows.sortedWith(comparator.reversed())
                }
            }
            return orderedRows
        }
    }

    private val privacyContext = PrivacyContext(Viewer.User(7L))
    private val targetPath = listOf(EdgeStep(Source::class, "targets", Target::class))

    @Test
    fun `loads memberships then correlates windowed targets in target-query order`() {
        val driver = RowsDriver(
            rows = mapOf(
                "memberships" to listOf(
                    mapOf("id" to 1L, "source_id" to 10L, "target_id" to 3L),
                    mapOf("id" to 2L, "source_id" to 10L, "target_id" to 3L),
                    mapOf("id" to 3L, "source_id" to 10L, "target_id" to 4L),
                    mapOf("id" to 4L, "source_id" to 20L, "target_id" to 4L),
                    mapOf("id" to 5L, "source_id" to 20L, "target_id" to 5L),
                ),
                "targets" to listOf(
                    mapOf("id" to 3L),
                    mapOf("id" to 4L),
                    mapOf("id" to 5L),
                ),
            ),
        )
        val contexts = mutableListOf<QueryContext>()
        val reader = reader(driver, contexts)
        val relationship = reader.loadRelationship(
            selection = EdgeSelection(
                TargetsEdge,
                targetQuery(limit = 1, offset = 1),
                EdgeVisibility.REQUIRE_VISIBLE,
            ),
            sources = listOf(Source(10), Source(20)),
            storage = TargetsEdge.storageStrategy,
            privacyContext = privacyContext,
            rootEntity = Source::class,
            targetPath = targetPath,
        )

        assertEquals(listOf(4L, 3L), relationship.targets.map(Target::id))

        val loaded = relationship.attach(
            relationship.targets.map { target -> target.copy(evaluated = true) },
        )

        assertEquals(listOf(3L), loaded[0].targets.map(Target::id))
        assertEquals(listOf(4L), loaded[1].targets.map(Target::id))
        assertTrue(loaded.flatMap(Source::targets).all(Target::evaluated))
        assertEquals(listOf("memberships", "targets"), driver.calls.map { it.table })
        assertEquals(listOf(null, null), driver.calls.map { it.limit })
        assertEquals(listOf(null, null), driver.calls.map { it.offset })
        assertEquals(
            listOf(ReadOperation.EAGER_JUNCTION, ReadOperation.EAGER_LOAD),
            contexts.map(QueryContext::operation),
        )
        assertTrue(contexts.all { it.edgeName == "targets" && it.path == targetPath })
    }

    @Test
    fun `empty source batch still compiles both interceptor stages without database reads`() {
        val driver = RowsDriver(rows = emptyMap())
        val contexts = mutableListOf<QueryContext>()
        val reader = reader(driver, contexts)

        val relationship = reader.loadRelationship(
            selection = EdgeSelection(
                TargetsEdge,
                targetQuery(),
                EdgeVisibility.REQUIRE_VISIBLE,
            ),
            sources = emptyList(),
            storage = TargetsEdge.storageStrategy,
            privacyContext = privacyContext,
            rootEntity = Source::class,
            targetPath = targetPath,
        )

        assertEquals(emptyList(), relationship.targets)
        assertEquals(emptyList(), relationship.attach(emptyList()))
        assertEquals(emptyList(), driver.calls)
        assertEquals(
            listOf(ReadOperation.EAGER_JUNCTION, ReadOperation.EAGER_LOAD),
            contexts.map(QueryContext::operation),
        )
    }

    private fun reader(
        driver: DatabaseDriver,
        contexts: MutableList<QueryContext>,
    ): JunctionRelationshipReader {
        val interceptors = EntInterceptorsConfig().apply {
            addEntity<Membership>("memberships", "junction") { _, context -> contexts += context }
            addEntity<Target>("targets", "target") { _, context -> contexts += context }
        }
        return JunctionRelationshipReader(
            driver = driver,
            queryCompiler = ReadQueryCompiler(
                driver = driver,
                registeredInterceptorsProvider = { interceptors },
            ),
        )
    }

    private fun targetQuery(
        limit: Int? = null,
        offset: Int? = null,
    ): EntityQuery<Target> = EntityQuery(
        entity = TargetMapping,
        source = QuerySource.Root(),
        predicates = emptyList(),
        orderBy = listOf(OrderField("id", OrderDirection.DESC)),
        limit = limit,
        offset = offset,
        edges = emptyList(),
    )
}
