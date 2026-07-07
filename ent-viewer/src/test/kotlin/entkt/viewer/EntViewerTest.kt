package entkt.viewer

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core viewer contract tests over a hand-built fake registry — routing,
 * authorization, privacy-context plumbing, filter/order validation,
 * pagination, redaction, and edge-link rules, all without generated code or
 * a database.
 */
class EntViewerTest {

    private class FakeClient

    private class FakeEntity(
        override val routeName: String,
        override val displayName: String,
        table: String = routeName + "s",
        override val edges: List<EntViewerEdge> = emptyList(),
        private val rows: MutableMap<String, EntViewerRow> = mutableMapOf(),
    ) : EntViewerEntity<FakeClient> {
        var lastListRequest: EntViewerListRequest? = null
        var listResult: List<EntViewerRow> = emptyList()

        override val schema = EntitySchema(
            table = table,
            idColumn = "id",
            idStrategy = IdStrategy.EXPLICIT,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false),
                ColumnMetadata("age", FieldType.INT, nullable = true),
                ColumnMetadata("secret", FieldType.STRING, nullable = false, sensitive = true),
            ),
            edges = emptyMap(),
        )

        override val columns = listOf(
            EntViewerColumn("id", FieldType.LONG, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true),
            EntViewerColumn("name", FieldType.STRING, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true),
            EntViewerColumn("age", FieldType.INT, nullable = true, unique = false, sensitive = false, filterable = true, orderable = true),
            EntViewerColumn("secret", FieldType.STRING, nullable = false, unique = false, sensitive = true, filterable = false, orderable = false),
        )

        fun row(id: String, name: String, age: Int?): EntViewerRow = EntViewerRow(
            id = id,
            values = listOf(
                EntViewerValue.of("id", id),
                EntViewerValue.of("name", name),
                EntViewerValue.of("age", age?.toString()),
                EntViewerValue.redacted("secret"),
            ),
        ).also { rows[id] = it }

        override fun list(client: FakeClient, request: EntViewerListRequest): EntViewerListResult {
            lastListRequest = request
            return EntViewerListResult(listResult.drop(request.offset).take(request.limit))
        }

        override fun get(client: FakeClient, id: String): EntViewerRow? = rows[id]
    }

    private class FakeRegistry(
        override val entities: List<EntViewerEntity<FakeClient>>,
    ) : EntViewerRegistry<FakeClient> {
        var lastContext: PrivacyContext? = null
        override fun <T> withPrivacyContext(client: FakeClient, context: PrivacyContext, block: (FakeClient) -> T): T {
            lastContext = context
            return block(client)
        }
    }

    private fun viewer(
        vararg entities: FakeEntity,
        configure: EntViewerConfig.() -> Unit = { authorize { true } },
    ): Pair<EntViewer<FakeClient>, FakeRegistry> {
        val registry = FakeRegistry(entities.toList())
        return EntViewer(FakeClient(), registry, configure) to registry
    }

    private fun get(path: String, vararg params: Pair<String, String>): EntViewerRequest =
        EntViewerRequest(
            path = path,
            query = params.groupBy({ it.first }, { it.second }),
        )

    // ---------- security surface ----------

    @Test
    fun `denies everything until authorize is configured`() {
        val (viewer, _) = viewer(FakeEntity("user", "User"), configure = {})
        val response = viewer.handle(get("/_ent"))
        assertEquals(403, response.status)
        assertTrue("authorize" in response.body)
    }

    @Test
    fun `rejects non-GET methods — the viewer is read-only`() {
        val (viewer, _) = viewer(FakeEntity("user", "User"))
        assertEquals(405, viewer.handle(EntViewerRequest(path = "/_ent", method = "POST")).status)
    }

    @Test
    fun `the per-request privacy context reaches every read`() {
        val entity = FakeEntity("user", "User")
        val marker = PrivacyContext(Viewer.PrivacyBypass("viewer-test"))
        val (viewer, registry) = viewer(entity, configure = {
            authorize { true }
            privacyContext { marker }
        })
        viewer.handle(get("/_ent/entities/user"))
        assertEquals(marker, registry.lastContext)
    }

    // ---------- routing ----------

    @Test
    fun `unknown paths, unknown entities, and excluded entities are the same 404`() {
        val user = FakeEntity("user", "User")
        val session = FakeEntity("session", "Session")
        val (viewer, _) = viewer(user, session, configure = {
            authorize { true }
            entities { exclude("session") }
        })
        val unknownPath = viewer.handle(get("/_ent/nope"))
        val unknownEntity = viewer.handle(get("/_ent/entities/ghost"))
        val excluded = viewer.handle(get("/_ent/entities/session"))
        assertEquals(404, unknownPath.status)
        assertEquals(404, unknownEntity.status)
        assertEquals(404, excluded.status)
        assertEquals(unknownEntity.body, excluded.body, "excluded must be indistinguishable from unknown")
        assertEquals(404, viewer.handle(get("/other/entities/user")).status, "wrong mount prefix")
    }

    @Test
    fun `home lists visible entities and omits excluded ones`() {
        val (viewer, _) = viewer(FakeEntity("user", "User"), FakeEntity("session", "Session"), configure = {
            authorize { true }
            entities { exclude("session") }
        })
        val body = viewer.handle(get("/_ent")).body
        assertTrue("User" in body)
        assertFalse("Session" in body)
    }

    // ---------- list behavior ----------

    @Test
    fun `pagination is always applied and drives prev-next links`() {
        val entity = FakeEntity("user", "User")
        entity.listResult = (1..60).map { entity.row(it.toString(), "u$it", it) }
        val (viewer, _) = viewer(entity)

        val response = viewer.handle(get("/_ent/entities/user", "size" to "50"))
        assertEquals(51, entity.lastListRequest!!.limit, "fetches size+1 to detect next page")
        assertEquals(0, entity.lastListRequest!!.offset)
        assertTrue("next" in response.body)

        viewer.handle(get("/_ent/entities/user", "page" to "2", "size" to "10"))
        assertEquals(10, entity.lastListRequest!!.offset)
    }

    @Test
    fun `filters parse into the adapter request and invalid ones fail with 400 before any query`() {
        val entity = FakeEntity("user", "User")
        val (viewer, _) = viewer(entity)

        viewer.handle(get("/_ent/entities/user", "f" to "name:eq:casey", "f" to "age:isnull"))
        assertEquals(
            listOf(
                EntViewerFilter("name", EntViewerFilterOp.EQ, "casey"),
                EntViewerFilter("age", EntViewerFilterOp.IS_NULL, null),
            ),
            entity.lastListRequest!!.filters,
        )

        entity.lastListRequest = null
        assertEquals(400, viewer.handle(get("/_ent/entities/user", "f" to "ghost:eq:1")).status)
        assertEquals(400, viewer.handle(get("/_ent/entities/user", "f" to "name:zap:1")).status)
        assertEquals(400, viewer.handle(get("/_ent/entities/user", "f" to "secret:eq:x")).status, "sensitive not filterable")
        assertEquals(400, viewer.handle(get("/_ent/entities/user", "f" to "name:isnull")).status, "non-nullable isnull")
        assertNull(entity.lastListRequest, "no adapter call happened for rejected filters")
    }

    @Test
    fun `the add-filter form triple parses like a filter token`() {
        val entity = FakeEntity("user", "User")
        val (viewer, _) = viewer(entity)
        viewer.handle(get("/_ent/entities/user", "fc" to "name", "fo" to "contains", "fv" to "ca"))
        assertEquals(
            listOf(EntViewerFilter("name", EntViewerFilterOp.CONTAINS, "ca")),
            entity.lastListRequest!!.filters,
        )
    }

    @Test
    fun `ordering validates orderability`() {
        val entity = FakeEntity("user", "User")
        val (viewer, _) = viewer(entity)
        viewer.handle(get("/_ent/entities/user", "order" to "age", "dir" to "desc"))
        assertEquals(EntViewerOrder("age", descending = true), entity.lastListRequest!!.order)
        assertEquals(400, viewer.handle(get("/_ent/entities/user", "order" to "secret")).status)
    }

    // ---------- redaction ----------

    @Test
    fun `sensitive cells render as stars and never their value`() {
        val entity = FakeEntity("user", "User")
        entity.listResult = listOf(entity.row("1", "casey", 30))
        val (viewer, _) = viewer(entity)
        val body = viewer.handle(get("/_ent/entities/user")).body
        assertTrue("***" in body)
    }

    @Test
    fun `extra redaction masks values the adapter emitted and blocks filtering`() {
        val entity = FakeEntity("user", "User")
        entity.row("1", "casey", 30)
        val (viewer, _) = viewer(entity, configure = {
            authorize { true }
            redaction { extra("users", "name") }
        })
        val body = viewer.handle(get("/_ent/entities/user/1")).body
        assertFalse("casey" in body, "extra-redacted value must not render")
        assertEquals(400, viewer.handle(get("/_ent/entities/user", "f" to "name:eq:casey")).status)
    }

    // ---------- detail + edges ----------

    @Test
    fun `unknown and missing ids are the same 404`() {
        val entity = FakeEntity("user", "User")
        val (viewer, _) = viewer(entity)
        assertEquals(404, viewer.handle(get("/_ent/entities/user/999")).status)
    }

    @Test
    fun `edge links follow the fk, filter, and disabled rules`() {
        val edges = listOf(
            EntViewerEdge("author", "user", "to-one", localFkColumn = "age"),
            EntViewerEdge("posts", "post", "to-many", targetFilterColumn = "author_id"),
            EntViewerEdge("tags", "tag", "many-to-many"),
            EntViewerEdge("ghosts", "ghost", "to-many", targetFilterColumn = "x_id"),
        )
        val entity = FakeEntity("user", "User", edges = edges)
        entity.row("1", "casey", 30)
        val post = FakeEntity("post", "Post")
        val tag = FakeEntity("tag", "Tag")
        val (viewer, _) = viewer(entity, post, tag)
        val body = viewer.handle(get("/_ent/entities/user/1")).body
        assertTrue("/_ent/entities/user/30" in body, "to-one links via the local FK value")
        assertTrue("f=author_id%3Aeq%3A1" in body, "to-many links to a filtered target list")
        assertTrue("no generated traversal link in V1" in body, "M2M renders disabled")
        assertTrue("not viewable" in body, "edges to unregistered targets render disabled")
    }
}
