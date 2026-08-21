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
 * driver work; their explain variants and `query{Name}` traversal
 * throw it directly; and none of this restricts entity terminals —
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
    fun `incompatible explain variants throw before driver explain work`() {
        val (client, recording) = recordingClient()
        val query = client.users.query { loadArticles() }
        recording.reset()

        val countEx = assertFailsWith<EntQueryConfigurationException> { query.explainRawCount() }
        assertContains(countEx.reason, "explainRawCount()")
        assertContains(countEx.reason, "User.articles")
        val existsEx = assertFailsWith<EntQueryConfigurationException> { query.explainRawExists() }
        assertContains(existsEx.reason, "explainRawExists()")
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
    fun `an interceptor cannot select an edge on the in-flight query`() {
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
        val failed = assertIs<ReadResult.Failed>(query.all())
        val ex = assertIs<EntQueryConfigurationException>(failed.exception)
        assertContains(ex.reason, "loadArticles()")
        assertContains(ex.reason, "terminal entry")

        // The rejected mid-flight selection installed nothing: with
        // the interceptor disarmed, the same query still loads no
        // edges.
        target = null
        val user = query.all().getOrThrow().single()
        assertEquals(EdgeState.Unloaded, user.edges.articles)
    }

    @Test
    fun `an interceptor cannot select a nested edge on the in-flight graph`() {
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

        // Retain the *nested* target query from the configuration
        // block: terminal entry acquires the guard across the whole
        // selected graph, not just the root query object.
        val query = client.users.query { }
        var captured: ArticleQuery? = null
        query.loadArticles { captured = this }

        target = captured
        val failed = assertIs<ReadResult.Failed>(query.all())
        val ex = assertIs<EntQueryConfigurationException>(failed.exception)
        assertContains(ex.reason, "loadAuthor()")
        assertContains(ex.reason, "terminal entry")

        // The rejected nested selection installed nothing: with the
        // interceptor disarmed, articles load but their author edge
        // stays unselected.
        target = null
        val user = query.all().getOrThrow().single()
        val article = user.edges.articles.requireLoaded().single()
        assertEquals(EdgeState.Unloaded, article.edges.author)
    }

    @Test
    fun `a privacy-context provider cannot select an edge during an entity explain`() {
        val recording = RecordingDriver(resetAndDriver())
        var target: UserQuery? = null
        val client = EntClient(recording) {
            // The provider runs inside the explain's guarded region:
            // the topology guard is acquired before privacy capture,
            // so even this callback cannot mutate the plan being
            // described.
            privacyContext {
                target?.loadGroups()
                PrivacyContext(Viewer.PrivacyBypass("test"))
            }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()
        val query = client.users.query { loadArticles() }

        target = query
        val ex = assertFailsWith<EntQueryConfigurationException> { query.explainAll() }
        assertContains(ex.reason, "loadGroups()")
        assertContains(ex.reason, "terminal entry")

        // The guard was released through the finally, and the rejected
        // selection installed nothing: disarmed, the same query
        // explains its original topology only.
        target = null
        val plan = query.explainAll()
        assertTrue("articles" in plan.eagerQueries, "original selection survives")
        assertTrue("groups" !in plan.eagerQueries, "rejected mid-explain selection must not appear")
    }

    @Test
    fun `a retained handle cannot change the in-flight operation's privacy posture`() {
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
        val failed = assertIs<ReadResult.Failed>(query.all())
        val ex = assertIs<EntQueryConfigurationException>(failed.exception)
        assertContains(ex.reason, "filterVisible()")
        assertContains(ex.reason, "User.articles")

        // Outside a terminal the same handle works normally.
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
    fun `entity terminals and entity explains still accept a selected graph`() {
        val (client, _) = recordingClient()
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()
        val query = client.users.query { loadArticles() }

        // Entity explain includes the selected topology under the
        // declaration-derived edge name rather than rejecting it.
        val plan = query.explainAll()
        assertTrue("articles" in plan.eagerQueries, "explainAll should describe the selected edge")

        val user = query.firstOrNull().getOrThrow()
        assertEquals(emptyList(), user?.edges?.articles?.requireLoaded())
    }
}
