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

        var throwOnList: RuntimeException? = null

        override fun list(client: FakeClient, request: EntViewerListRequest): EntViewerListResult {
            lastListRequest = request
            throwOnList?.let { throw it }
            val window = listResult.drop(request.offset)
            return EntViewerListResult(
                rows = window.take(request.pageSize),
                hasNext = window.size > request.pageSize,
            )
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
    fun `unauthorized requests are cloaked 404s that disclose nothing`() {
        val (viewer, _) = viewer(FakeEntity("user", "User"), configure = {})
        val response = viewer.handle(get("/_ent"))
        assertEquals(404, response.status)
        assertTrue(response.unmapped, "hosts should render their native not-found")
        assertFalse("EntKt" in response.body, "no branding for unauthorized callers")
        assertFalse("viewer" in response.body.lowercase())
        assertFalse("<nav" in response.body)

        // The method check must not fire first: a POST probe without
        // authorization is the same cloaked 404, never a revealing 405.
        val probe = viewer.handle(EntViewerRequest(path = "/_ent", method = "POST"))
        assertEquals(404, probe.status)
        assertTrue(probe.unmapped)
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
        assertEquals(50, entity.lastListRequest!!.pageSize)
        assertEquals(0, entity.lastListRequest!!.offset)
        assertTrue("Next" in response.body)

        viewer.handle(get("/_ent/entities/user", "page" to "2", "size" to "10"))
        assertEquals(10, entity.lastListRequest!!.offset)

        assertEquals(
            400,
            viewer.handle(get("/_ent/entities/user", "page" to "2147483647", "size" to "200")).status,
            "absurdly deep pages are rejected, not overflowed",
        )
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
            EntViewerEdge("posts", "post", "to-many", targetFilterColumn = "age"),
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
        assertTrue("f=age%3Aeq%3A1" in body, "to-many links to a filtered target list")
        assertTrue("no generated traversal link in V1" in body, "M2M renders disabled")
        assertTrue("not viewable" in body, "edges to unregistered targets render disabled")
    }
}

/**
 * Contract tests added after the adversarial review: interceptor-rejection
 * handling, privacy-windowed pagination presentation, path-segment id
 * round-trips, strict order direction, edge-link stripping, and fail-fast
 * redaction config.
 */
class EntViewerReviewContractTest {

    private class FakeClient

    private class Entity(
        override val routeName: String,
        override val displayName: String,
        override val edges: List<EntViewerEdge> = emptyList(),
        fkFilterable: Boolean = true,
    ) : EntViewerEntity<FakeClient> {
        val gotIds = mutableListOf<String>()
        var result: EntViewerListResult = EntViewerListResult(emptyList(), hasNext = false)
        var throwOnList: RuntimeException? = null

        override val schema = entkt.runtime.driver.EntitySchema(
            table = routeName + "s",
            idColumn = "id",
            idStrategy = entkt.runtime.driver.IdStrategy.EXPLICIT,
            columns = listOf(
                entkt.runtime.driver.ColumnMetadata("id", entkt.schema.FieldType.STRING, nullable = false, primaryKey = true),
                entkt.runtime.driver.ColumnMetadata("owner_id", entkt.schema.FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )
        override val columns = listOf(
            EntViewerColumn("id", entkt.schema.FieldType.STRING, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true),
            EntViewerColumn("owner_id", entkt.schema.FieldType.STRING, nullable = false, unique = false, sensitive = !fkFilterable, filterable = fkFilterable, orderable = fkFilterable),
        )

        override fun list(client: FakeClient, request: EntViewerListRequest): EntViewerListResult {
            throwOnList?.let { throw it }
            return result
        }

        override fun get(client: FakeClient, id: String): EntViewerRow? {
            gotIds.add(id)
            return EntViewerRow(id, listOf(EntViewerValue.of("id", id), EntViewerValue.of("owner_id", "o1")))
        }
    }

    private class Registry(override val entities: List<EntViewerEntity<FakeClient>>) : EntViewerRegistry<FakeClient> {
        override fun <T> withPrivacyContext(
            client: FakeClient,
            context: entkt.runtime.privacy.PrivacyContext,
            block: (FakeClient) -> T,
        ): T = block(client)
    }

    private fun viewer(vararg entities: Entity, configure: EntViewerConfig.() -> Unit = { authorize { true } }) =
        EntViewer(FakeClient(), Registry(entities.toList()), configure)

    private fun get(path: String, vararg params: Pair<String, String>) =
        EntViewerRequest(path = path, query = params.groupBy({ it.first }, { it.second }))

    @kotlin.test.Test
    fun `read-interceptor rejections render as a controlled 400, not a 500`() {
        val entity = Entity("doc", "Doc")
        entity.throwOnList = entkt.runtime.result.EntQueryRejectedException(
            entkt.runtime.result.EntError.QueryRejected(
                entity = "Doc",
                operation = entkt.runtime.result.EntOperation.QUERY,
                reason = "tenant scope required",
                interceptor = "tenantGuard",
            ),
        )
        val response = viewer(entity).handle(get("/_ent/entities/doc"))
        kotlin.test.assertEquals(400, response.status)
        kotlin.test.assertTrue("tenantGuard" in response.body)
        kotlin.test.assertTrue("tenant scope required" in response.body)
    }

    @kotlin.test.Test
    fun `privacy-windowed pages banner and offer next unconditionally`() {
        val entity = Entity("doc", "Doc")
        entity.result = EntViewerListResult(emptyList(), hasNext = null, privacyFiltered = true)
        val body = viewer(entity).handle(get("/_ent/entities/doc")).body
        kotlin.test.assertTrue("Row-level privacy applies" in body)
        kotlin.test.assertTrue("Next" in body, "navigation offered even for a sparse window")
    }

    @kotlin.test.Test
    fun `exact hasNext=false hides the next link`() {
        val entity = Entity("doc", "Doc")
        entity.result = EntViewerListResult(emptyList(), hasNext = false)
        val body = viewer(entity).handle(get("/_ent/entities/doc")).body
        kotlin.test.assertTrue("Next &gt;" !in body)
    }

    @kotlin.test.Test
    fun `string ids round-trip through percent-encoded path segments`() {
        val entity = Entity("doc", "Doc")
        val viewer = viewer(entity)
        val response = viewer.handle(get("/_ent/entities/doc/hello%20world%2Fx"))
        kotlin.test.assertEquals(200, response.status)
        kotlin.test.assertEquals(listOf("hello world/x"), entity.gotIds, "segment is percent-decoded once")
        kotlin.test.assertEquals(404, viewer.handle(get("/_ent/entities/doc/bad%zz")).status, "malformed encoding is a 404")
    }

    @kotlin.test.Test
    fun `order direction is strict`() {
        val entity = Entity("doc", "Doc")
        val viewer = viewer(entity)
        kotlin.test.assertEquals(200, viewer.handle(get("/_ent/entities/doc", "order" to "id", "dir" to "desc")).status)
        kotlin.test.assertEquals(400, viewer.handle(get("/_ent/entities/doc", "order" to "id", "dir" to "DESC")).status)
    }

    @kotlin.test.Test
    fun `edge filter-links are stripped when the target fk is not filterable`() {
        val edges = listOf(EntViewerEdge("items", "item", "to-many", targetFilterColumn = "owner_id"))
        val source = Entity("doc", "Doc", edges = edges)
        val linkableTarget = Entity("item", "Item", fkFilterable = true)
        val body1 = viewer(source, linkableTarget).handle(get("/_ent/entities/doc/1")).body
        kotlin.test.assertTrue("f=owner_id" in body1, "filterable target fk keeps the link")

        val unlinkableTarget = Entity("item", "Item", fkFilterable = false)
        val body2 = viewer(source, unlinkableTarget).handle(get("/_ent/entities/doc/1")).body
        kotlin.test.assertTrue("f=owner_id" !in body2, "non-filterable target fk must not produce a guaranteed-400 link")
        kotlin.test.assertTrue("no generated traversal link" in body2)
    }

    @kotlin.test.Test
    fun `redaction extra typos fail at construction`() {
        val entity = Entity("doc", "Doc")
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            viewer(entity, configure = {
                authorize { true }
                redaction { extra("docz", "owner_id") }
            })
        }
        kotlin.test.assertTrue("docz" in ex.message!!)
    }
}
