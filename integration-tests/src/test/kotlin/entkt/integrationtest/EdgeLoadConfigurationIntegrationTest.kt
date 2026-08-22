package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleQuery
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserQuery
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.EdgeLoad
import entkt.runtime.query.EdgeState
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.isLoaded
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntQueryConfigurationException
import entkt.runtime.result.ReadResult
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the edge-load configuration contract from the generated
 * edge-loading API RFC: selecting one edge twice throws
 * [EntQueryConfigurationException] at the second `load{Name}` call;
 * non-entity terminals (raw count / existence / aggregates) capture
 * the same exception as `ReadResult.Failed` before any interceptor or
 * driver work; `query{Name}` traversal throws it directly; and none
 * of this restricts entity terminals —
 * a fully configured query stays executable any number of times.
 */
class EdgeLoadConfigurationIntegrationTest : PostgresTestBase() {

    private fun recordingClient(interceptorFires: AtomicInteger = AtomicInteger()): Pair<EntClient, RecordingDriver> {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { _, _ -> interceptorFires.incrementAndGet() },
                    name = "count-fires",
                )
            }
        }
        return client to recording
    }

    @Test
    fun `selecting the same edge twice throws at the second load call`() {
        val (client, recording) = recordingClient()
        recording.reset()

        val ex = assertFailsWith<EntQueryConfigurationException> {
            client.users.query {
                loadArticles { where(Article.title eq "t") }
                loadArticles()
            }
        }
        assertEquals("User", ex.entityType)
        assertContains(ex.reason, "User.articles")
        assertContains(ex.reason, "loadArticles()")
        // Thrown while configuring — before any terminal, interceptor,
        // or driver work.
        assertEquals(0, recording.callCount())
    }

    @Test
    fun `rawCount captures the configuration exception before interceptor and driver work`() {
        val fires = AtomicInteger()
        val (client, recording) = recordingClient(fires)
        val query = client.users.query { loadArticles() }
        recording.reset()

        val result = query.rawCount()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntQueryConfigurationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertContains(ex.reason, "rawCount()")
        assertContains(ex.reason, "User.articles")
        assertEquals(0, recording.callCount(), "no driver call may precede the failure")
        assertEquals(0, fires.get(), "no interceptor may fire before the failure")
        // The result-bearing boundary preserves the exact exception.
        val thrown = assertFailsWith<EntQueryConfigurationException> { result.getOrThrow() }
        assertSame(ex, thrown)
    }

    @Test
    fun `existence and aggregate terminals reject a selected graph the same way`() {
        val (client, recording) = recordingClient()
        val query = client.users.query { loadGroups() }
        recording.reset()

        val results = mapOf(
            "rawExists()" to query.rawExists(),
            "rawMin()" to query.rawMin(User.name),
            "rawCountBy()" to query.rawCountBy(User.name),
        )
        for ((operation, result) in results) {
            val failed = assertIs<ReadResult.Failed>(result, "expected $operation to fail")
            val ex = assertIs<EntQueryConfigurationException>(failed.exception)
            assertContains(ex.reason, operation)
            assertContains(ex.reason, "User.groups")
        }
        assertEquals(0, recording.callCount())
    }

    @Test
    fun `traversal rejects a source query with a selected edge`() {
        val (client, recording) = recordingClient()
        val query = client.users.query { loadGroups() }
        recording.reset()

        val ex = assertFailsWith<EntQueryConfigurationException> { query.queryArticles() }
        assertEquals("User", ex.entityType)
        assertContains(ex.reason, "queryArticles()")
        assertContains(ex.reason, "User.groups")
        assertEquals(0, recording.callCount())
    }

    @Test
    fun `traversing first and selecting loads on the target query succeeds`() {
        val (client, _) = recordingClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save().getOrThrow()

        val articles = client.users.query { }
            .queryArticles { loadAuthor() }
            .all()
            .getOrThrow()

        assertEquals(listOf("T"), articles.map { it.title })
        assertEquals(author.id, articles.single().edges.author.requireLoaded()?.id)
    }

    @Test
    fun `a fully configured query stays executable more than once`() {
        val (client, _) = recordingClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save().getOrThrow()
        val query = client.users.query { loadArticles() }

        val first = query.all().getOrThrow().single()
        val second = query.all().getOrThrow().single()

        // Re-execution is not a duplicate selection: the selected
        // graph remains part of the query until it is discarded.
        assertEquals(listOf("T"), first.edges.articles.requireLoaded().map { it.title })
        assertEquals(listOf("T"), second.edges.articles.requireLoaded().map { it.title })
    }

    @Test
    fun `a re-entrant load call inside the configuration block is rejected, not last-write-wins`() {
        val (client, _) = recordingClient()
        val query = client.users.query()

        // The slot is reserved before the block runs, so the inner
        // call hits the duplicate guard instead of installing a
        // selection the outer assignment would silently overwrite.
        val ex = assertFailsWith<EntQueryConfigurationException> {
            query.loadArticles {
                query.loadArticles { where(Article.title eq "inner") }
            }
        }
        assertContains(ex.reason, "User.articles")

        // The failed outer selection rolled back, so a clean retry
        // selects the edge normally.
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()
        query.loadArticles { where(Article.title eq "outer") }
        val user = query.all().getOrThrow().single()
        assertEquals(emptyList(), user.edges.articles.requireLoaded())
    }

    @Test
    fun `a failing configuration block rolls the selection back`() {
        val (client, _) = recordingClient()
        val query = client.users.query()

        assertFailsWith<IllegalStateException> { query.loadArticles { error("boom") } }

        // Nothing was installed: a non-entity terminal accepts the
        // query, and the edge can still be selected cleanly.
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()
        assertIs<ReadResult.Success<Long>>(query.rawCount())
        query.loadArticles()
        assertTrue(query.all().getOrThrow().single().edges.articles.isLoaded)
    }

    @Test
    fun `an interceptor mutation applies only to later captured queries`() {
        val recording = RecordingDriver(resetAndDriver())
        var target: UserQuery? = null
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { _, _ -> target?.loadArticles() },
                    name = "mid-flight-select",
                )
            }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()
        val query = client.users.query { }

        target = query
        val first = query.all().getOrThrow().single()
        assertEquals(EdgeState.Unloaded, first.edges.articles)

        // Terminal entry captured the original graph before the
        // interceptor mutated the reusable builder. A later terminal
        // captures and executes the newly selected edge.
        target = null
        val user = query.all().getOrThrow().single()
        assertTrue(user.edges.articles.isLoaded)
    }

    @Test
    fun `an interceptor nested mutation applies only to later captured graphs`() {
        val recording = RecordingDriver(resetAndDriver())
        var target: ArticleQuery? = null
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { _, _ -> target?.loadAuthor() },
                    name = "mid-flight-nested-select",
                )
            }
        }
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save().getOrThrow()

        // Retain the nested builder so the root interceptor can mutate it
        // after terminal entry has captured the complete recursive graph.
        val query = client.users.query { }
        var captured: ArticleQuery? = null
        query.loadArticles { captured = this }

        target = captured
        val first = query.all().getOrThrow().single()
        val firstArticle = first.edges.articles.requireLoaded().single()
        assertEquals(EdgeState.Unloaded, firstArticle.edges.author)

        // The builder mutation appears in the next recursive capture.
        target = null
        val user = query.all().getOrThrow().single()
        val article = user.edges.articles.requireLoaded().single()
        assertTrue(article.edges.author.isLoaded)
    }

    @Test
    fun `a retained handle changes only later captured privacy posture`() {
        val recording = RecordingDriver(resetAndDriver())
        var retained: EdgeLoad<UserQuery>? = null
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { _, _ -> retained?.filterVisible() },
                    name = "mid-flight-filter",
                )
            }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()
        val query = client.users.query { }
        val handle = query.loadArticles()

        retained = handle
        assertTrue(query.all().getOrThrow().single().edges.articles.isLoaded)

        // The interceptor changed the reusable builder after the first
        // capture. The same handle remains idempotent for later captures.
        retained = null
        handle.filterVisible()
        assertTrue(query.all().getOrThrow().single().edges.articles.isLoaded)
    }

    @Test
    fun `call order does not override schema-declaration execution order`() {
        val (client, recording) = recordingClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save().getOrThrow()
        recording.reset()

        // groups is selected first, articles second — but User declares
        // articles before groups, and the executor follows
        // schema-declaration order, not call order.
        client.users.query {
            loadGroups()
            loadArticles()
        }.all().getOrThrow()

        val articlesAt = recording.calls.indexOf("queryDirectToMany:articles")
        val junctionAt = recording.calls.indexOf("query:memberships")
        assertTrue(
            articlesAt >= 0 && junctionAt >= 0,
            "expected both edge loads to reach the driver: ${recording.calls}",
        )
        assertTrue(
            articlesAt < junctionAt,
            "articles (declared first) must load before groups: ${recording.calls}",
        )
    }

    @Test
    fun `mutating the query after a terminal affects only later executions`() {
        val (client, _) = recordingClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save().getOrThrow()
        val query = client.users.query { loadArticles() }

        val first = query.all().getOrThrow().single()
        // Selecting a *different* edge after a completed terminal is not
        // a duplicate selection; it extends the graph for later
        // executions only.
        query.loadGroups()
        val second = query.all().getOrThrow().single()

        assertEquals(EdgeState.Unloaded, first.edges.groups)
        assertTrue(first.edges.articles.isLoaded)
        assertTrue(second.edges.groups.isLoaded)
        assertTrue(second.edges.articles.isLoaded)
    }

    @Test
    fun `entity terminals accept a selected graph`() {
        val (client, _) = recordingClient()
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()
        val query = client.users.query { loadArticles() }

        val user = query.firstOrNull().getOrThrow()
        assertEquals(emptyList(), user?.edges?.articles?.requireLoaded())
    }
}
