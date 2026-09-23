@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacyEvaluation
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.ViewerContext
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
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
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
        val sourceIds = mutableListOf<List<Any>>()
        var rootRowBound: Int? = null

        @Suppress("UNCHECKED_CAST")
        override fun <Entity : EntEntity<*>> loadRoot(
            query: EntityQuery<Entity>,
            operation: ReadOperation,
            maximumRows: Int?,
            viewerContext: ViewerContext,
            lockMode: entkt.runtime.query.QueryLockMode,
        ): List<Entity> {
            events += "load:root"
            rootRowBound = maximumRows
            val selected = if (maximumRows == null) rootEntities else rootEntities.take(maximumRows)
            return selected as List<Entity>
        }

        @Suppress("UNCHECKED_CAST")
        override fun <Source : EntEntity<*>, Target : EntEntity<*>> loadRelationship(
            selection: EdgeSelection<Source, Target>,
            sources: List<Source>,
            context: RelationshipReadContext,
        ): LoadedRelationship<Source, Target> {
            val edgeName = selection.edge.name
            events += "load:$edgeName"
            rootEntitiesByRelationship += context.rootEntity
            paths += context.interceptorPath.map(EdgeStep::edgeName)
            sourceIds += sources.map { it.id }

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
    ) : LoadPrivacyDispatcher {
        override fun isConfigured(entity: EntityMapping<*>): Boolean = configured

        override fun <Entity : EntEntity<*>> evaluate(
            entity: EntityMapping<Entity>,
            viewerContext: ViewerContext,
            entities: List<Entity>,
        ): PrivacyEvaluation<Entity> {
            events += "privacy:${entities.joinToString { it.id.toString() }}"
            return privacyEvaluation(
                subjects = entities,
                decisions = entities.map { value ->
                    if (value.id in deniedIds) PrivacyDecision.Deny("hidden")
                    else PrivacyDecision.Allow
                },
            )
        }
    }

    private val viewerContext = ViewerContext(Viewer.User(7L))

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
            loadPrivacyDispatcher = RecordingPrivacy(events, configured = false),
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

        val loaded = loader.loadMany(
            query = rootQuery,
            viewerContext = viewerContext,
        ).map { it.getOrThrow() }

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
    fun `all denied roots retain individual failures without relationship loading`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = listOf(Item(1), Item(2)),
            targetsByEdge = mapOf("children" to emptyList()),
            events = events,
        )
        val loader = EntityGraphLoader(
            storage = storage,
            loadPrivacyDispatcher = RecordingPrivacy(events, deniedIds = setOf(1L, 2L)),
        )
        val query = query(
            EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.REQUIRE_VISIBLE),
        )

        val entries = loader.loadMany(query, viewerContext)
        val failures = entries.map { entry ->
            assertIs<EntPrivacyDeniedException>(assertIs<ReadResult.Failed>(entry).exception)
        }

        assertEquals(2, failures.size)
        failures.forEach { assertSame(LoadDenialOrigin.Root, it.origin) }
        assertEquals(listOf(1L, 2L), failures.map { it.denials.single().entityKey.value })
        assertEquals(listOf("load:root", "privacy:1, 2"), events)
    }

    @Test
    fun `mixed roots load only authorized graphs and preserve duplicate root occurrences`() {
        val events = mutableListOf<String>()
        val first = Item(1, mapOf("existing" to listOf(Item(8))))
        val last = Item(1, mapOf("existing" to listOf(Item(9))))
        val storage = RecordingGraphStorage(
            rootEntities = listOf(first, Item(2), last),
            targetsByEdge = mapOf("children" to listOf(Item(3))),
            events = events,
        )
        val loader = EntityGraphLoader(storage, RecordingPrivacy(events, deniedIds = setOf(2L)))
        val query = query(EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.REQUIRE_VISIBLE))

        val entries = loader.loadMany(query, viewerContext)
        val firstLoaded = assertIs<ReadResult.Success<Item>>(entries[0]).value
        val lastLoaded = assertIs<ReadResult.Success<Item>>(entries[2]).value

        assertEquals(3, entries.size)
        assertEquals(listOf(listOf<Any>(1L, 1L)), storage.sourceIds)
        assertEquals(1L, firstLoaded.id)
        assertEquals(1L, lastLoaded.id)
        assertEquals(listOf(Item(8)), firstLoaded.relationships.getValue("existing"))
        assertEquals(listOf(Item(9)), lastLoaded.relationships.getValue("existing"))
        assertEquals(listOf(Item(3)), firstLoaded.relationships.getValue("children"))
        assertEquals(listOf(Item(3)), lastLoaded.relationships.getValue("children"))
        assertIs<ReadResult.Failed>(entries[1])
        assertEquals(
            listOf("load:root", "privacy:1, 2, 1", "load:children", "privacy:3", "attach:children"),
            events,
        )
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
            loadPrivacyDispatcher = RecordingPrivacy(events, deniedIds = setOf(2L)),
        )
        val query = query(
            EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.FILTER_INVISIBLE),
        )

        val loaded = loader.loadMany(query, viewerContext).map { it.getOrThrow() }

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
            loadPrivacyDispatcher = RecordingPrivacy(events, deniedIds = setOf(3L)),
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
            loader.loadMany(query, viewerContext)
        }

        val origin = assertIs<LoadDenialOrigin.SelectedEdgePath>(failure.origin)
        assertEquals(listOf("children", "notes"), origin.steps.map { it.edgeName })
        assertEquals(3L, failure.denials.single().entityKey.value)
        assertEquals(
            listOf("load:root", "privacy:1", "load:children", "privacy:2", "load:notes", "privacy:3"),
            events,
        )

        events.clear()
        val singularFailure = assertFailsWith<EntPrivacyDeniedException> {
            loader.loadOne(query, ReadOperation.FIRST, viewerContext)
        }
        assertEquals(origin, singularFailure.origin)
        assertEquals(failure.denials, singularFailure.denials)
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
            loadPrivacyDispatcher = RecordingPrivacy(events),
        )
        val query = query(
            EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.REQUIRE_VISIBLE),
        )

        val loaded = loader.loadMany(query, viewerContext).map { it.getOrThrow() }

        assertEquals(emptyList(), loaded.single().relationships.getValue("children"))
        assertEquals(
            listOf("load:root", "privacy:1", "load:children", "privacy:", "attach:children"),
            events,
        )
    }

    @Test
    fun `singular loads authorize only the selected root and attach its visible graph`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = listOf(Item(1), Item(2)),
            targetsByEdge = mapOf("children" to listOf(Item(3), Item(4))),
            events = events,
        )
        val loader = EntityGraphLoader(storage, RecordingPrivacy(events, deniedIds = setOf(2L, 4L)))
        val query = query(EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.FILTER_INVISIBLE))

        val loaded = loader.loadOne(query, ReadOperation.FIRST, viewerContext)

        assertEquals(Item(1, mapOf("children" to listOf(Item(3)))), loaded)
        assertEquals(1, storage.rootRowBound)
        assertEquals(listOf(listOf<Any>(1L)), storage.sourceIds)
        assertEquals(
            listOf("load:root", "privacy:1", "load:children", "privacy:3, 4", "attach:children"),
            events,
        )
    }

    @Test
    fun `singular root denial prevents relationship loading`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = listOf(Item(1), Item(2)),
            targetsByEdge = mapOf("children" to listOf(Item(3))),
            events = events,
        )
        val loader = EntityGraphLoader(storage, RecordingPrivacy(events, deniedIds = setOf(1L)))
        val query = query(EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.REQUIRE_VISIBLE))

        val failure = assertFailsWith<EntPrivacyDeniedException> {
            loader.loadOne(query, ReadOperation.FIRST, viewerContext)
        }

        assertSame(LoadDenialOrigin.Root, failure.origin)
        assertEquals(1L, failure.denials.single().entityKey.value)
        assertEquals(emptyList(), storage.sourceIds)
        assertEquals(listOf("load:root", "privacy:1"), events)
    }

    @Test
    fun `absent singular root still visits selected paths without root privacy evaluation`() {
        val events = mutableListOf<String>()
        val storage = RecordingGraphStorage(
            rootEntities = emptyList(),
            targetsByEdge = mapOf("children" to emptyList()),
            events = events,
        )
        val loader = EntityGraphLoader(storage, RecordingPrivacy(events))
        val query = query(EdgeSelection(ItemEdge("children"), query(), EdgeVisibility.REQUIRE_VISIBLE))

        val loaded = loader.loadOne(query, ReadOperation.FIRST, viewerContext)

        assertEquals(null, loaded)
        assertEquals(listOf(emptyList()), storage.sourceIds)
        assertEquals(listOf("load:root", "load:children", "privacy:", "attach:children"), events)
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
