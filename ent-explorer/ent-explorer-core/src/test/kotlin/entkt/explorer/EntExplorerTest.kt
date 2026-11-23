package entkt.explorer

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.privacy.ViewerContext
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core explorer contract tests over a hand-built fake registry — routing,
 * authorization, privacy-context plumbing, filter/order validation,
 * pagination, redaction, and edge-link rules, all without generated code or
 * a database.
 */
class EntExplorerTest {

    private class FakeViewer

    private class FakeClient

    private class FakeEntity(
        override val routeName: String,
        override val displayName: String,
        table: String = routeName + "s",
        override val edges: List<EntExplorerEdge> = emptyList(),
        private val rows: MutableMap<String, EntExplorerRow> = mutableMapOf(),
        comment: String? = null,
    ) : EntExplorerEntity<FakeViewer, FakeClient> {
        var lastListRequest: EntExplorerListRequest? = null
        var lastViewerContext: ViewerContext<FakeViewer>? = null
        var listResult: List<EntExplorerRow> = emptyList()

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
            comment = comment,
        )

        override val columns = listOf(
            EntExplorerColumn("id", FieldType.LONG, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true),
            EntExplorerColumn("name", FieldType.STRING, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true),
            EntExplorerColumn("age", FieldType.INT, nullable = true, unique = false, sensitive = false, filterable = true, orderable = true),
            EntExplorerColumn("secret", FieldType.STRING, nullable = false, unique = false, sensitive = true, filterable = false, orderable = false),
        )

        fun row(id: String, name: String, age: Int?): EntExplorerRow = EntExplorerRow(
            id = id,
            values = listOf(
                EntExplorerValue.of("id", id),
                EntExplorerValue.of("name", name),
                EntExplorerValue.of("age", age?.toString()),
                EntExplorerValue.redacted("secret"),
            ),
        ).also { rows[id] = it }

        var throwOnList: RuntimeException? = null

        override fun list(
            client: FakeClient,
            viewerContext: ViewerContext<FakeViewer>,
            request: EntExplorerListRequest,
        ): EntExplorerListResult {
            lastViewerContext = viewerContext
            lastListRequest = request
            throwOnList?.let { throw it }
            val window = listResult.drop(request.offset)
            return EntExplorerListResult(
                rows = window.take(request.pageSize),
                hasNext = window.size > request.pageSize,
            )
        }

        override fun get(
            client: FakeClient,
            viewerContext: ViewerContext<FakeViewer>,
            id: String,
        ): EntExplorerRow? {
            lastViewerContext = viewerContext
            return rows[id]
        }
    }

    /** Builds an explorer that reads under a bypass context unless [configure] supplies its own provider. */
    private fun explorer(
        vararg entities: FakeEntity,
        configure: EntExplorerConfig<FakeViewer>.() -> Unit = { authorize { true } },
    ): EntExplorer<FakeViewer, FakeClient> = EntExplorer(FakeClient(), entities.toList()) {
        viewerContext { ViewerContext.privacyBypass_DANGEROUS("explorer-test default") }
        configure()
    }

    private fun get(path: String, vararg params: Pair<String, String>): EntExplorerRequest =
        EntExplorerRequest(
            path = path,
            query = params.groupBy({ it.first }, { it.second }),
        )

    // ---------- security surface ----------

    @Test
    fun `unauthorized requests are cloaked 404s that disclose nothing`() {
        val explorer = explorer(FakeEntity("user", "User"), configure = {})
        val response = explorer.handle(get("/_ent"))
        assertEquals(404, response.status)
        assertTrue(response.unmapped, "hosts should render their native not-found")
        assertFalse("EntKt" in response.body, "no branding for unauthorized callers")
        assertFalse("explorer" in response.body.lowercase())
        assertFalse("<nav" in response.body)

        // The method check must not fire first: a POST probe without
        // authorization is the same cloaked 404, never a revealing 405.
        val probe = explorer.handle(EntExplorerRequest(path = "/_ent", method = "POST"))
        assertEquals(404, probe.status)
        assertTrue(probe.unmapped)
    }

    @Test
    fun `rejects non-GET methods — the explorer is read-only`() {
        val explorer = explorer(FakeEntity("user", "User"))
        assertEquals(405, explorer.handle(EntExplorerRequest(path = "/_ent", method = "POST")).status)
    }

    @Test
    fun `an explorer cannot be constructed without a viewer context provider`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EntExplorer(FakeClient(), listOf(FakeEntity("user", "User"))) { authorize { true } }
        }

        assertTrue("viewerContext" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the provider's viewer reaches a read as the context the application built`() {
        val entity = FakeEntity("user", "User")
        val viewer = FakeViewer()
        val explorer = explorer(entity, configure = {
            authorize { true }
            viewerContext { ViewerContext.AsViewer(viewer) }
        })

        explorer.handle(get("/_ent/entities/user"))

        assertEquals(ViewerContext.AsViewer(viewer), entity.lastViewerContext)
    }

    @Test
    fun `the per-request viewer context reaches every read`() {
        val entity = FakeEntity("user", "User")
        val marker = ViewerContext.privacyBypass_DANGEROUS("explorer-test")
        val explorer = explorer(entity, configure = {
            authorize { true }
            viewerContext { marker }
        })
        explorer.handle(get("/_ent/entities/user"))
        assertEquals(marker, entity.lastViewerContext)
    }

    // ---------- routing ----------

    @Test
    fun `unknown paths, unknown entities, and excluded entities are the same 404`() {
        val user = FakeEntity("user", "User")
        val session = FakeEntity("session", "Session")
        val explorer = explorer(user, session, configure = {
            authorize { true }
            entities { exclude("session") }
        })
        val unknownPath = explorer.handle(get("/_ent/nope"))
        val unknownEntity = explorer.handle(get("/_ent/entities/ghost"))
        val excluded = explorer.handle(get("/_ent/entities/session"))
        assertEquals(404, unknownPath.status)
        assertEquals(404, unknownEntity.status)
        assertEquals(404, excluded.status)
        assertEquals(unknownEntity.body, excluded.body, "excluded must be indistinguishable from unknown")
        assertEquals(404, explorer.handle(get("/other/entities/user")).status, "wrong mount prefix")
    }

    @Test
    fun `home lists visible entities and omits excluded ones`() {
        val explorer = explorer(FakeEntity("user", "User"), FakeEntity("session", "Session"), configure = {
            authorize { true }
            entities { exclude("session") }
        })
        val body = explorer.handle(get("/_ent")).body
        assertTrue("User" in body)
        assertFalse("Session" in body)
    }

    @Test
    fun `schema comments render beneath the heading without loading rows`() {
        val comment = "People who can sign in to the application."
        val entity = FakeEntity("user", "User", comment = comment)

        val response = explorer(entity).handle(get("/_ent/schema/user"))

        assertEquals(200, response.status)
        assertTrue("<p class=\"muted\">$comment</p>" in response.body)
        assertTrue(response.body.indexOf("</h1>") < response.body.indexOf(comment))
        assertTrue(response.body.indexOf(comment) < response.body.indexOf("Browse rows"))
        assertNull(entity.lastListRequest)
        assertNull(entity.lastViewerContext)
    }

    @Test
    fun `absent and blank schema comments leave the page unchanged`() {
        val baseline = explorer(FakeEntity("user", "User")).handle(get("/_ent/schema/user"))

        for (comment in listOf(null, "", " \n\t")) {
            val entity = FakeEntity("user", "User", comment = comment)
            val response = explorer(entity).handle(get("/_ent/schema/user"))

            assertEquals(200, response.status)
            assertEquals(baseline.body, response.body)
        }
    }

    @Test
    fun `schema comments render HTML as escaped text`() {
        val entity = FakeEntity("user", "User", comment = "<script>alert(1)</script> & <b>Accounts</b>")

        val response = explorer(entity).handle(get("/_ent/schema/user"))

        assertEquals(200, response.status)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &lt;b&gt;Accounts&lt;/b&gt;" in response.body)
        assertFalse("<script>" in response.body)
        assertFalse("<b>Accounts</b>" in response.body)
    }

    @Test
    fun `schema comments are not disclosed without authorization or for excluded entities`() {
        val comment = "Internal account documentation."
        val entity = FakeEntity("user", "User", comment = comment)
        val unauthorized = explorer(entity, configure = {})
        val excluded = explorer(entity, configure = {
            authorize { true }
            entities { exclude("user") }
        })

        for (explorer in listOf(unauthorized, excluded)) {
            val response = explorer.handle(get("/_ent/schema/user"))

            assertEquals(404, response.status)
            assertFalse(comment in response.body)
        }
    }

    // ---------- list behavior ----------

    @Test
    fun `pagination is always applied and drives prev-next links`() {
        val entity = FakeEntity("user", "User")
        entity.listResult = (1..60).map { entity.row(it.toString(), "u$it", it) }
        val explorer = explorer(entity)

        val response = explorer.handle(get("/_ent/entities/user", "size" to "50"))
        assertEquals(50, entity.lastListRequest!!.pageSize)
        assertEquals(0, entity.lastListRequest!!.offset)
        assertTrue("Next" in response.body)

        explorer.handle(get("/_ent/entities/user", "page" to "2", "size" to "10"))
        assertEquals(10, entity.lastListRequest!!.offset)

        assertEquals(
            400,
            explorer.handle(get("/_ent/entities/user", "page" to "2147483647", "size" to "200")).status,
            "absurdly deep pages are rejected, not overflowed",
        )
    }

    @Test
    fun `filters parse into the adapter request and invalid ones fail with 400 before any query`() {
        val entity = FakeEntity("user", "User")
        val explorer = explorer(entity)

        explorer.handle(get("/_ent/entities/user", "f" to "name:eq:casey", "f" to "age:isnull"))
        assertEquals(
            listOf(
                EntExplorerFilter("name", EntExplorerFilterOp.EQ, "casey"),
                EntExplorerFilter("age", EntExplorerFilterOp.IS_NULL, null),
            ),
            entity.lastListRequest!!.filters,
        )

        entity.lastListRequest = null
        assertEquals(400, explorer.handle(get("/_ent/entities/user", "f" to "ghost:eq:1")).status)
        assertEquals(400, explorer.handle(get("/_ent/entities/user", "f" to "name:zap:1")).status)
        assertEquals(400, explorer.handle(get("/_ent/entities/user", "f" to "secret:eq:x")).status, "sensitive not filterable")
        assertEquals(400, explorer.handle(get("/_ent/entities/user", "f" to "name:isnull")).status, "non-nullable isnull")
        assertNull(entity.lastListRequest, "no adapter call happened for rejected filters")
    }

    @Test
    fun `the add-filter form triple parses like a filter token`() {
        val entity = FakeEntity("user", "User")
        val explorer = explorer(entity)
        explorer.handle(get("/_ent/entities/user", "fc" to "name", "fo" to "contains", "fv" to "ca"))
        assertEquals(
            listOf(EntExplorerFilter("name", EntExplorerFilterOp.CONTAINS, "ca")),
            entity.lastListRequest!!.filters,
        )
    }

    @Test
    fun `ordering validates orderability`() {
        val entity = FakeEntity("user", "User")
        val explorer = explorer(entity)
        explorer.handle(get("/_ent/entities/user", "order" to "age", "dir" to "desc"))
        assertEquals(EntExplorerOrder("age", descending = true), entity.lastListRequest!!.order)
        assertEquals(400, explorer.handle(get("/_ent/entities/user", "order" to "secret")).status)
    }

    // ---------- redaction ----------

    @Test
    fun `sensitive cells render as stars and never their value`() {
        val entity = FakeEntity("user", "User")
        entity.listResult = listOf(entity.row("1", "casey", 30))
        val explorer = explorer(entity)
        val body = explorer.handle(get("/_ent/entities/user")).body
        assertTrue("***" in body)
    }

    @Test
    fun `extra redaction masks values the adapter emitted and blocks filtering`() {
        val entity = FakeEntity("user", "User")
        entity.row("1", "casey", 30)
        val explorer = explorer(entity, configure = {
            authorize { true }
            redaction { extra("users", "name") }
        })
        val body = explorer.handle(get("/_ent/entities/user/1")).body
        assertFalse("casey" in body, "extra-redacted value must not render")
        assertEquals(400, explorer.handle(get("/_ent/entities/user", "f" to "name:eq:casey")).status)
    }

    // ---------- detail + edges ----------

    @Test
    fun `unknown and missing ids are the same 404`() {
        val entity = FakeEntity("user", "User")
        val explorer = explorer(entity)
        assertEquals(404, explorer.handle(get("/_ent/entities/user/999")).status)
    }

    @Test
    fun `edge links follow the fk, filter, and disabled rules`() {
        val edges = listOf(
            EntExplorerEdge("author", "user", "to-one", localFkColumn = "age"),
            EntExplorerEdge("posts", "post", "to-many", targetFilterColumn = "age"),
            EntExplorerEdge("tags", "tag", "many-to-many"),
            EntExplorerEdge("ghosts", "ghost", "to-many", targetFilterColumn = "x_id"),
        )
        val entity = FakeEntity("user", "User", edges = edges)
        entity.row("1", "casey", 30)
        val post = FakeEntity("post", "Post")
        val tag = FakeEntity("tag", "Tag")
        val explorer = explorer(entity, post, tag)
        val body = explorer.handle(get("/_ent/entities/user/1")).body
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
class EntExplorerReviewContractTest {

    private class FakeViewer

    private class FakeClient

    private class Entity(
        override val routeName: String,
        override val displayName: String,
        override val edges: List<EntExplorerEdge> = emptyList(),
        fkFilterable: Boolean = true,
    ) : EntExplorerEntity<FakeViewer, FakeClient> {
        val gotIds = mutableListOf<String>()
        var result: EntExplorerListResult = EntExplorerListResult(emptyList(), hasNext = false)
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
            EntExplorerColumn("id", entkt.schema.FieldType.STRING, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true),
            EntExplorerColumn("owner_id", entkt.schema.FieldType.STRING, nullable = false, unique = false, sensitive = !fkFilterable, filterable = fkFilterable, orderable = fkFilterable),
        )

        override fun list(
            client: FakeClient,
            viewerContext: ViewerContext<FakeViewer>,
            request: EntExplorerListRequest,
        ): EntExplorerListResult {
            throwOnList?.let { throw it }
            return result
        }

        override fun get(
            client: FakeClient,
            viewerContext: ViewerContext<FakeViewer>,
            id: String,
        ): EntExplorerRow? {
            gotIds.add(id)
            return EntExplorerRow(id, listOf(EntExplorerValue.of("id", id), EntExplorerValue.of("owner_id", "o1")))
        }
    }

    private fun explorer(
        vararg entities: Entity,
        configure: EntExplorerConfig<FakeViewer>.() -> Unit = { authorize { true } },
    ) = EntExplorer(FakeClient(), entities.toList()) {
        viewerContext { ViewerContext.privacyBypass_DANGEROUS("explorer-test default") }
        configure()
    }

    private fun get(path: String, vararg params: Pair<String, String>) =
        EntExplorerRequest(path = path, query = params.groupBy({ it.first }, { it.second }))

    @kotlin.test.Test
    fun `read-interceptor rejections render as a controlled 400, not a 500`() {
        val entity = Entity("doc", "Doc")
        entity.throwOnList = entkt.runtime.result.EntQueryRejectedException(
            entityType = "Doc",
            reason = "tenant scope required",
            code = null,
            interceptor = "tenantGuard",
        )
        val response = explorer(entity).handle(get("/_ent/entities/doc"))
        kotlin.test.assertEquals(400, response.status)
        kotlin.test.assertTrue("tenantGuard" in response.body)
        kotlin.test.assertTrue("tenant scope required" in response.body)
    }

    @kotlin.test.Test
    fun `privacy-windowed pages banner and offer next unconditionally`() {
        val entity = Entity("doc", "Doc")
        entity.result = EntExplorerListResult(emptyList(), hasNext = null, privacyFiltered = true)
        val body = explorer(entity).handle(get("/_ent/entities/doc")).body
        kotlin.test.assertTrue("Row-level privacy applies" in body)
        kotlin.test.assertTrue("Next" in body, "navigation offered even for a sparse window")
    }

    @kotlin.test.Test
    fun `exact hasNext=false hides the next link`() {
        val entity = Entity("doc", "Doc")
        entity.result = EntExplorerListResult(emptyList(), hasNext = false)
        val body = explorer(entity).handle(get("/_ent/entities/doc")).body
        kotlin.test.assertTrue("Next &gt;" !in body)
    }

    @kotlin.test.Test
    fun `string ids round-trip through percent-encoded path segments`() {
        val entity = Entity("doc", "Doc")
        val explorer = explorer(entity)
        val response = explorer.handle(get("/_ent/entities/doc/hello%20world%2Fx"))
        kotlin.test.assertEquals(200, response.status)
        kotlin.test.assertEquals(listOf("hello world/x"), entity.gotIds, "segment is percent-decoded once")
        kotlin.test.assertEquals(404, explorer.handle(get("/_ent/entities/doc/bad%zz")).status, "malformed encoding is a 404")
    }

    @kotlin.test.Test
    fun `order direction is strict`() {
        val entity = Entity("doc", "Doc")
        val explorer = explorer(entity)
        kotlin.test.assertEquals(200, explorer.handle(get("/_ent/entities/doc", "order" to "id", "dir" to "desc")).status)
        kotlin.test.assertEquals(400, explorer.handle(get("/_ent/entities/doc", "order" to "id", "dir" to "DESC")).status)
    }

    @kotlin.test.Test
    fun `edge filter-links are stripped when the target fk is not filterable`() {
        val edges = listOf(EntExplorerEdge("items", "item", "to-many", targetFilterColumn = "owner_id"))
        val source = Entity("doc", "Doc", edges = edges)
        val linkableTarget = Entity("item", "Item", fkFilterable = true)
        val body1 = explorer(source, linkableTarget).handle(get("/_ent/entities/doc/1")).body
        kotlin.test.assertTrue("f=owner_id" in body1, "filterable target fk keeps the link")

        val unlinkableTarget = Entity("item", "Item", fkFilterable = false)
        val body2 = explorer(source, unlinkableTarget).handle(get("/_ent/entities/doc/1")).body
        kotlin.test.assertTrue("f=owner_id" !in body2, "non-filterable target fk must not produce a guaranteed-400 link")
        kotlin.test.assertTrue("no generated traversal link" in body2)
    }

    @kotlin.test.Test
    fun `redaction extra typos fail at construction`() {
        val entity = Entity("doc", "Doc")
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            explorer(entity, configure = {
                authorize { true }
                redaction { extra("docz", "owner_id") }
            })
        }
        kotlin.test.assertTrue("docz" in ex.message!!)
    }
}
