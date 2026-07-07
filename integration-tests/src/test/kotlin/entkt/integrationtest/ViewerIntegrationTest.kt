package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.GeneratedEntViewerRegistry
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.viewer.EntViewer
import entkt.viewer.EntViewerRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end: the generated viewer registry against real Postgres through
 * the generated client. Proves the generated adapters compile AND behave —
 * privacy context plumbing (fail-closed with Anonymous, visible with a
 * bypass context), filters through generated predicates, pagination, and
 * detail/edge rendering.
 */
class ViewerIntegrationTest : PostgresTestBase() {

    /** Truncates once, then hands back a seeding sysClient + a plain client for the viewer. */
    private fun fresh(): Pair<EntClient, EntClient> {
        val driver = resetAndDriver()
        return sysClient(driver) to EntClient(newDriver()) {}
    }

    private fun viewer(client: EntClient, context: PrivacyContext): EntViewer<EntClient> =
        EntViewer(client, GeneratedEntViewerRegistry) {
            authorize { true }
            privacyContext { context }
        }

    private fun bypass() = PrivacyContext(Viewer.PrivacyBypass("viewer-integration-test"))

    private fun get(viewer: EntViewer<EntClient>, path: String, vararg params: Pair<String, String>) =
        viewer.handle(EntViewerRequest(path = path, query = params.groupBy({ it.first }, { it.second })))

    private fun seed(sys: EntClient) {
        val author = sys.users.create { name = "Casey"; email = "casey@example.com" }.save()
        sys.articles.create { title = "First"; authorId = author.id }.save()
        sys.articles.create { title = "Second"; authorId = author.id }.save()
    }

    @Test
    fun `home, schema, and entity index render for the generated registry`() {
        val (_, plain) = fresh()
        val viewer = viewer(plain, bypass())
        val home = get(viewer, "/_ent")
        assertEquals(200, home.status)
        assertTrue("Article" in home.body && "User" in home.body)

        val schema = get(viewer, "/_ent/schema")
        assertEquals(200, schema.status)
        assertTrue("articles" in schema.body, "table names render")
        assertTrue("rects" in schema.body, "json columns appear in schema")
    }

    @Test
    fun `list and detail read through the privacy context — bypass sees rows, anonymous sees none`() {
        val (sys, plain) = fresh()
        seed(sys)
        val visible = viewer(plain, bypass())
        val list = get(visible, "/_ent/entities/article")
        assertEquals(200, list.status)
        assertTrue("First" in list.body && "Second" in list.body)

        val anonymous = viewer(plain, PrivacyContext(Viewer.Anonymous))
        val hidden = get(anonymous, "/_ent/entities/article")
        assertEquals(200, hidden.status)
        assertFalse("First" in hidden.body, "fail-closed privacy must hide rows from Anonymous")
        assertTrue("No visible rows" in hidden.body)
    }

    @Test
    fun `detail renders fields and edge links, and is a 404 under a denying context`() {
        val (sys, plain) = fresh()
        seed(sys)
        val article = sys.articles.query { }.allOrThrow().first { it.title == "First" }

        val visible = viewer(plain, bypass())
        val detail = get(visible, "/_ent/entities/article/${article.id}")
        assertEquals(200, detail.status)
        assertTrue("First" in detail.body)
        assertTrue("/_ent/entities/user/${article.authorId}" in detail.body, "belongsTo links to the author detail")

        val anonymous = viewer(plain, PrivacyContext(Viewer.Anonymous))
        assertEquals(404, get(anonymous, "/_ent/entities/article/${article.id}").status)
        assertEquals(404, get(visible, "/_ent/entities/article/999999").status, "missing row is the same 404")
        assertEquals(404, get(visible, "/_ent/entities/article/not-a-number").status, "unparseable id is the same 404")
    }

    @Test
    fun `filters run through generated predicates and bad ones fail with 400`() {
        val (sys, plain) = fresh()
        seed(sys)
        val viewer = viewer(plain, bypass())
        val filtered = get(viewer, "/_ent/entities/article", "f" to "title:eq:First")
        assertTrue("First" in filtered.body)
        assertFalse(">Second<" in filtered.body, "eq filter excludes the other row")

        assertEquals(400, get(viewer, "/_ent/entities/article", "f" to "title:zap:x").status)
        assertEquals(400, get(viewer, "/_ent/entities/article", "f" to "nope:eq:x").status)
        assertEquals(400, get(viewer, "/_ent/entities/article", "f" to "rects:eq:x").status, "json columns are display-only")
    }

    @Test
    fun `pagination windows over raw rows for load-privacy entities`() {
        val (sys, plain) = fresh()
        val author = sys.users.create { name = "A"; email = "p@example.com" }.save()
        repeat(5) { i -> sys.articles.create { title = "t$i"; authorId = author.id }.save() }

        val viewer = viewer(plain, bypass())
        val page1 = get(viewer, "/_ent/entities/article", "size" to "2")
        assertEquals(200, page1.status)
        // Every integration entity has load privacy (fail-closed model), so
        // pages are raw windows: pagination stays bounded, and next is
        // offered unconditionally with the windowed banner — never derived
        // from visible counts, which would leak denied-row information.
        assertTrue("Row-level privacy applies" in page1.body)
        assertTrue("Next" in page1.body)
        val page2 = get(viewer, "/_ent/entities/article", "size" to "2", "page" to "2")
        assertTrue("t2" in page2.body && "t3" in page2.body, "windows are disjoint and in order")
        assertFalse("t1<" in page2.body.substringAfter("tbody"), "no duplicated rows across pages")

        // Exactly at the visible-scan cap (default 100): deterministic 400,
        // regardless of how many rows exist.
        val atCap = get(viewer, "/_ent/entities/article", "size" to "100")
        assertEquals(400, atCap.status)
        assertTrue("visible-scan cap (100)" in atCap.body, atCap.body)
    }

    @Test
    fun `sensitive fields are redacted end to end and never filterable`() {
        val (sys, plain) = fresh()
        val author = sys.users.create {
            name = "Casey"; email = "s@example.com"; apiToken = "super-secret-token"
        }.save()

        val viewer = viewer(plain, bypass())
        val detail = get(viewer, "/_ent/entities/user/${author.id}")
        assertEquals(200, detail.status)
        assertTrue("***" in detail.body, "sensitive cell renders redacted")
        assertFalse("super-secret-token" in detail.body, "the value must never reach HTML")

        val list = get(viewer, "/_ent/entities/user")
        assertFalse("super-secret-token" in list.body)
        assertEquals(400, get(viewer, "/_ent/entities/user", "f" to "api_token:eq:x").status)
        assertEquals(400, get(viewer, "/_ent/entities/user", "order" to "api_token").status)
    }

    @Test
    fun `extra redaction masks generated-adapter values at runtime`() {
        val (sys, plain) = fresh()
        sys.users.create { name = "Casey"; email = "redact-me@example.com" }.save()
        val viewer = EntViewer(plain, GeneratedEntViewerRegistry) {
            authorize { true }
            privacyContext { bypass() }
            redaction { extra("users", "email") }
        }
        val list = get(viewer, "/_ent/entities/user")
        assertFalse("redact-me@example.com" in list.body, "extra-redacted value must not render")
        assertEquals(400, get(viewer, "/_ent/entities/user", "f" to "email:eq:x").status)
    }

    @Test
    fun `privacy-windowed lists banner instead of turning next into an oracle, and M2M edges render disabled`() {
        val (sys, plain) = fresh()
        val user = sys.users.create { name = "Casey"; email = "m2m@example.com" }.save()

        val viewer = viewer(plain, bypass())
        val list = get(viewer, "/_ent/entities/user")
        assertTrue("Row-level privacy applies" in list.body, "load-privacy entities are labeled as windowed")

        val detail = get(viewer, "/_ent/entities/user/${user.id}")
        assertTrue("no generated traversal link in V1" in detail.body, "M2M edges render disabled through generated adapters")
    }
}
