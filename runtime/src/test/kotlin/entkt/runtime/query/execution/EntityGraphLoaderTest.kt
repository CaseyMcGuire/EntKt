@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.EdgeMapping
import entkt.runtime.query.EdgeSelection
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.EdgeTraversal
import entkt.runtime.query.EdgeVisibility
import entkt.runtime.query.EntityQuery
import entkt.runtime.query.QuerySource
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.ToManyEdgeMapping
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.PrivacyDenial
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class EntityGraphLoaderTest {
    private data class Item(
        override val id: Long,
        val relationships: Map<String, List<Item>> = emptyMap(),
    ) : EntEntity.LongId

    private object ItemMapping : EntityMapping<Item> {
        override val entityName = "Item"
        override val clientName = "items"
        override val entityClass = Item::class
        override val table = "items"

        override fun decode(row: Map<String, Any?>): Item = Item(row.getValue("id") as Long)

        override fun edgeByStorageName(storageName: String): EdgeMapping<Item, *>? = null
    }

    private class ItemEdge(
        override val name: String,
    ) : ToManyEdgeMapping<Item, Item> {
        override val storageName = name
        override val source = ItemMapping
        override val target = ItemMapping
        override val traversal: EdgeTraversal<Item>? = null
        override val storageStrategy: Nothing
            get() = error("Fake graph storage does not inspect edge storage")

        override fun attach(source: Item, targets: List<Item>): Item = source.copy(
            relationships = source.relationships + (name to targets),
        )
    }

    private class RecordingGraphStorage(
        private val rootEntities: List<Item>,
        private val targetsByEdge: Map<String, List<Item>>,
        private val events: MutableList<String>,
    ) : GraphStorage {
        val rootEntitiesByRelationship = mutableListOf<KClass<*>>()
        val paths = mutableListOf<List<String>>()

        @Suppress("UNCHECKED_CAST")
        override fun <Entity : EntEntity<*>> loadRoot(
            query: EntityQuery<Entity>,
            operation: ReadOperation,
            maximumRows: Int?,
            privacyContext: PrivacyContext,
        ): List<Entity> {
            events += "load:root"
            return rootEntities as List<Entity>
        }

        @Suppress("UNCHECKED_CAST")
        override fun <Source : EntEntity<*>, Target : EntEntity<*>> loadRelationship(
            selection: EdgeSelection<Source, Target>,
            sources: List<Source>,
            privacyContext: PrivacyContext,
            rootEntity: KClass<*>,
            targetPath: List<EdgeStep>,
        ): LoadedRelationship<Source, Target> {
            val edgeName = selection.edge.name
            events += "load:$edgeName"
            rootEntitiesByRelationship += rootEntity
            paths += targetPath.map(EdgeStep::edgeName)

            val edge = selection.edge as? ToManyEdgeMapping<Source, Target>
                ?: error("Fake graph storage only supports to-many test edges")
            val targets = targetsByEdge.getValue(edgeName) as List<Target>
            return LoadedRelationship(targets) { evaluatedTargets ->
                events += "attach:$edgeName"
                sources.map { source -> edge.attach(source, evaluatedTargets) }
            }
        }
    }

    private class RecordingPrivacy(
        private val events: MutableList<String>,
        private val deniedIds: Set<Long> = emptySet(),
        private val configured: Boolean = true,
    ) : LoadPrivacyEvaluator {
        override fun isConfigured(entity: EntityMapping<*>): Boolean = configured

        override fun <Entity : EntEntity<*>> evaluate(
            entity: EntityMapping<Entity>,
            privacyContext: PrivacyContext,
            entities: List<Entity>,
        ): List<LoadPrivacyEvaluation<Entity>> {
            events += "privacy:${entities.joinToString { it.id.toString() }}"
            return entities.map { value ->
                val denial = if (value.id in deniedIds) {
                    PrivacyDenial(entity.entityName, EntityKey("id", value.id), "hidden")
                } else null
                if (denial == null) {
                    LoadPrivacyEvaluation.Allowed(value)
                } else {
                    LoadPrivacyEvaluation.Denied(value, denial)
                }
            }
        }
    }

    private val privacyContext = PrivacyContext(Viewer.User(7L))

    @Test
    fun `recurses before attachment and visits sibling relationships in declaration order`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = listOf(Item(1)),
            targetsByEdge = mapOf(
                "children" to listOf(Item(2)),
                "notes" to listOf(Item(3)),
                "peers" to listOf(Item(4)),
            ),
            events = events,
        )
        val loader = EntityGraphLoader(
            storage = storage,
            loadPrivacyEvaluatorProvider = {
                RecordingPrivacy(events, configured = false)
            },
        )
        val notes = ItemEdge("notes")
        val children = ItemEdge("children")
        val peers = ItemEdge("peers")
        val childQuery = query(
            EdgeSelection(notes, query(), EdgeVisibility.REQUIRE_VISIBLE),
        )
        val rootQuery = query(
            EdgeSelection(children, childQuery, EdgeVisibility.REQUIRE_VISIBLE),
            EdgeSelection(peers, query(), EdgeVisibility.REQUIRE_VISIBLE),
        )

        val loaded = loader.load(
            query = rootQuery,
            operation = ReadOperation.ALL,
            maximumRows = null,
            privacyContext = privacyContext,
        )

        assertEquals(
            listOf(
                "load:root",
                "load:children",
                "load:notes",
                "attach:notes",
                "attach:children",
                "load:peers",
                "attach:peers",
            ),
            events,
        )
        assertEquals(3L, loaded.single().relationships.getValue("children")
            .single().relationships.getValue("notes").single().id)
        assertEquals(4L, loaded.single().relationships.getValue("peers").single().id)
        assertEquals(
            listOf(listOf("children"), listOf("children", "notes"), listOf("peers")),
            storage.paths,
        )
        storage.rootEntitiesByRelationship.forEach { assertSame(Item::class, it) }
    }

    @Test
    fun `root privacy aggregates denials and stops before relationship loading`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = listOf(Item(1), Item(2)),
            targetsByEdge = mapOf("children" to emptyList()),
            events = events,
        )
        val loader = EntityGraphLoader(
            storage = storage,
            loadPrivacyEvaluatorProvider = {
                RecordingPrivacy(events, deniedIds = setOf(1L, 2L))
            },
        )
        val query = query(
            EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.REQUIRE_VISIBLE),
        )

        val failure = assertFailsWith<EntPrivacyDeniedException> {
            loader.load(query, ReadOperation.ALL, null, privacyContext)
        }

        assertSame(LoadDenialOrigin.Root, failure.origin)
        assertEquals(listOf(1L, 2L), failure.denials.map { it.entityKey.value })
        assertEquals(listOf("load:root", "privacy:1, 2"), events)
    }

    @Test
    fun `filter visibility removes denied targets before attachment`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = listOf(Item(1)),
            targetsByEdge = mapOf("children" to listOf(Item(2), Item(3))),
            events = events,
        )
        val loader = EntityGraphLoader(
            storage = storage,
            loadPrivacyEvaluatorProvider = {
                RecordingPrivacy(events, deniedIds = setOf(2L))
            },
        )
        val query = query(
            EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.FILTER_INVISIBLE),
        )

        val loaded = loader.load(query, ReadOperation.ALL, null, privacyContext)

        assertEquals(listOf(3L), loaded.single().relationships.getValue("children").map(Item::id))
        assertEquals(
            listOf("load:root", "privacy:1", "load:children", "privacy:2, 3", "attach:children"),
            events,
        )
    }

    @Test
    fun `nested strict denial reports the complete path and prevents attachment`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = listOf(Item(1)),
            targetsByEdge = mapOf(
                "children" to listOf(Item(2)),
                "notes" to listOf(Item(3)),
            ),
            events = events,
        )
        val loader = EntityGraphLoader(
            storage = storage,
            loadPrivacyEvaluatorProvider = {
                RecordingPrivacy(events, deniedIds = setOf(3L))
            },
        )
        val query = query(
            EdgeSelection(
                ItemEdge("children"),
                query(
                    EdgeSelection(ItemEdge("notes"), query(), EdgeVisibility.REQUIRE_VISIBLE),
                ),
                EdgeVisibility.REQUIRE_VISIBLE,
            ),
        )

        val failure = assertFailsWith<EntPrivacyDeniedException> {
            loader.load(query, ReadOperation.ALL, null, privacyContext)
        }

        val origin = assertIs<LoadDenialOrigin.EagerEdge>(failure.origin)
        assertEquals(listOf("children", "notes"), origin.path.map { it.edgeName })
        assertEquals(3L, failure.denials.single().entityKey.value)
        assertEquals(
            listOf("load:root", "privacy:1", "load:children", "privacy:2", "load:notes", "privacy:3"),
            events,
        )
    }

    @Test
    fun `configured edge privacy still evaluates an empty relationship batch`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = listOf(Item(1)),
            targetsByEdge = mapOf("children" to emptyList()),
            events = events,
        )
        val loader = EntityGraphLoader(
            storage = storage,
            loadPrivacyEvaluatorProvider = { RecordingPrivacy(events) },
        )
        val query = query(
            EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.REQUIRE_VISIBLE),
        )

        val loaded = loader.load(query, ReadOperation.ALL, null, privacyContext)

        assertEquals(emptyList(), loaded.single().relationships.getValue("children"))
        assertEquals(
            listOf("load:root", "privacy:1", "load:children", "privacy:", "attach:children"),
            events,
        )
    }

    private fun query(
        vararg edges: EdgeSelection<Item, *>,
    ): EntityQuery<Item> = EntityQuery(
        entity = ItemMapping,
        source = QuerySource.Root(),
        predicates = emptyList(),
        orderBy = emptyList(),
        limit = null,
        offset = null,
        edges = edges.toList(),
    )
}
