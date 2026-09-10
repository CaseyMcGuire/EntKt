package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleQueryScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.ent.UserQueryScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.allowAll
import entkt.runtime.query.EdgeLoad
import entkt.runtime.query.EdgeState
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.isLoaded
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntQueryConfigurationException
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the edge-load configuration contract from the generated
 * edge-loading API RFC: selecting one edge twice throws
 * [EntQueryConfigurationException] at the second `load{Name}` call;
 * `query{Name}` traversal rejects a source query carrying selected edges;
 * and none of this restricts entity terminals —
 * a fully configured query stays executable any number of times.
 */
class EdgeLoadConfigurationIntegrationTest : PostgresTestBase() {

    private fun recordingClient(): Pair<EntClient, RecordingDriver> {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording)
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
        assertContains(ex.reason, "single load block")
        // Thrown while configuring — before any terminal, interceptor,
        // or driver work.
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
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save(testViewerContext).getOrThrow()

        val articles = client.users.query { }
            .queryArticles { loadAuthor() }
            .all(testViewerContext)
            .getOrThrow()

        assertEquals(listOf("T"), articles.map { it.title })
        assertEquals(author.id, articles.single().edges.author.requireLoaded()?.id)
    }

    @Test
    fun `a fully configured query stays executable more than once`() {
        val (client, _) = recordingClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save(testViewerContext).getOrThrow()
        val query = client.users.query { loadArticles() }

        val first = query.all(testViewerContext).getOrThrow().single()
        val second = query.all(testViewerContext).getOrThrow().single()

        // Re-execution is not a duplicate selection: the selected
        // graph remains part of the query until it is discarded.
        assertEquals(listOf("T"), first.edges.articles.requireLoaded().map { it.title })
        assertEquals(listOf("T"), second.edges.articles.requireLoaded().map { it.title })
    }

    @Test
    fun `a re-entrant load call inside the configuration block is rejected, not last-write-wins`() {
        val (client, _) = recordingClient()
        val query = client.users.query outer@{
            // Reserve the slot before running the block. The failed outer
            // selection then releases it, allowing a retry in this scope.
            val ex = assertFailsWith<EntQueryConfigurationException> {
                loadArticles {
                    this@outer.loadArticles { where(Article.title eq "inner") }
                }
            }
            assertContains(ex.reason, "User.articles")
            loadArticles { where(Article.title eq "outer") }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()
        val user = query.all(testViewerContext).getOrThrow().single()
        assertEquals(emptyList(), user.edges.articles.requireLoaded())
    }

    @Test
    fun `a failing configuration block rolls the selection back`() {
        val (client, _) = recordingClient()
        val query = client.users.query {
            assertFailsWith<IllegalStateException> { loadArticles { error("boom") } }
            // Nothing was installed, so the edge can still be selected cleanly.
            loadArticles()
        }
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()
        assertTrue(query.all(testViewerContext).getOrThrow().single().edges.articles.isLoaded)
    }

    @Test
    fun `an interceptor mutating a retained scope cannot change any execution`() {
        val recording = RecordingDriver(resetAndDriver())
        var target: UserQueryScope? = null
        val client = EntClient(recording) {

            interceptors {
                users(
                    QueryInterceptor { _, _ -> target?.loadArticles() },
                    name = "mid-flight-select",
                )
            }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()
        val query = client.users.query { target = this }

        val first = query.all(testViewerContext).getOrThrow().single()
        assertEquals(EdgeState.Unloaded, first.edges.articles)

        // The published query owns its graph. The escaped scope's changes
        // cannot affect it on this or any later execution.
        target = null
        val user = query.all(testViewerContext).getOrThrow().single()
        assertEquals(EdgeState.Unloaded, user.edges.articles)
    }

    @Test
    fun `an interceptor mutating a retained nested scope cannot change the graph`() {
        val recording = RecordingDriver(resetAndDriver())
        var target: ArticleQueryScope? = null
        val client = EntClient(recording) {

            interceptors {
                users(
                    QueryInterceptor { _, _ -> target?.loadAuthor() },
                    name = "mid-flight-nested-select",
                )
            }
        }
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save(testViewerContext).getOrThrow()

        var captured: ArticleQueryScope? = null
        val query = client.users.query { loadArticles { captured = this } }

        target = captured
        val first = query.all(testViewerContext).getOrThrow().single()
        val firstArticle = first.edges.articles.requireLoaded().single()
        assertEquals(EdgeState.Unloaded, firstArticle.edges.author)

        // Nested configuration was frozen when the query was built too.
        target = null
        val user = query.all(testViewerContext).getOrThrow().single()
        val article = user.edges.articles.requireLoaded().single()
        assertEquals(EdgeState.Unloaded, article.edges.author)
    }

    @Test
    fun `a retained handle cannot change the built query's privacy posture`() {
        val recording = RecordingDriver(resetAndDriver())
        var retained: EdgeLoad<UserQueryScope>? = null
        val client = EntClient(recording) {
            policies {
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run {
                        privacy { load(allowAll) }
                    }
                })
                articles(object : EntityPolicy<Article, ArticlePolicyScope> {
                    override fun configure(scope: ArticlePolicyScope) = scope.run {
                        privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("hidden") }) }
                    }
                })
            }
            interceptors {
                users(
                    QueryInterceptor { _, _ -> retained?.filterVisible() },
                    name = "mid-flight-filter",
                )
            }
        }
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "hidden"; authorId = author.id }.save(testViewerContext).getOrThrow()
        val viewer = ViewerContext(Viewer.User(author.id))
        var handle: EdgeLoad<UserQueryScope>? = null
        val base = client.users.query()
        val query = base.configure { handle = loadArticles() }

        retained = assertNotNull(handle)
        val first = assertIs<ReadResult.Failed>(query.all(viewer))
        assertIs<EntPrivacyDeniedException>(first.exception)

        retained = null
        assertNotNull(handle).filterVisible()
        val second = assertIs<ReadResult.Failed>(query.all(viewer))
        assertIs<EntPrivacyDeniedException>(second.exception)

        val filtered = base.configure { loadArticles().filterVisible() }
        assertEquals(emptyList(), filtered.all(viewer).getOrThrow().single().edges.articles.requireLoaded())
    }

    @Test
    fun `call order does not override schema-declaration execution order`() {
        val (client, recording) = recordingClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save(testViewerContext).getOrThrow()
        recording.reset()

        // groups is selected first, articles second — but User declares
        // articles before groups, and the executor follows
        // schema-declaration order, not call order.
        client.users.query {
            loadGroups()
            loadArticles()
        }.all(testViewerContext).getOrThrow()

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
    fun `configuring a new branch after a terminal leaves the original graph unchanged`() {
        val (client, _) = recordingClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.save(testViewerContext).getOrThrow()
        val query = client.users.query { loadArticles() }

        val first = query.all(testViewerContext).getOrThrow().single()
        val withGroups = query.configure { loadGroups() }
        val second = withGroups.all(testViewerContext).getOrThrow().single()

        assertEquals(EdgeState.Unloaded, first.edges.groups)
        assertTrue(first.edges.articles.isLoaded)
        assertTrue(second.edges.groups.isLoaded)
        assertTrue(second.edges.articles.isLoaded)
        assertEquals(EdgeState.Unloaded, query.all(testViewerContext).getOrThrow().single().edges.groups)
    }

    @Test
    fun `entity terminals accept a selected graph`() {
        val (client, _) = recordingClient()
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()
        val query = client.users.query { loadArticles() }

        val user = query.firstOrNull(testViewerContext).getOrThrow()
        assertEquals(emptyList(), user?.edges?.articles?.requireLoaded())
    }
}
