package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.EdgeNotLoadedException
import entkt.runtime.query.EdgeState
import entkt.runtime.query.isLoaded
import entkt.runtime.query.loadedOrNull
import entkt.runtime.query.requireLoaded
import entkt.runtime.query.valueOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the EdgeState contract on returned entities end to end: an edge
 * the query never requested is `Unloaded`, every requested edge is
 * `Loaded(...)` — `Loaded(emptyList())` for a to-many with no rows,
 * `Loaded(null)` for a to-one with no returned target (including a
 * *required* `belongsTo` whose target an eager predicate filtered
 * out) — and nested eager loads set state independently at every
 * level. Mutation returns never eager-load, so create/update entities
 * keep the all-`Unloaded` default.
 */
class EdgeStateIntegrationTest : PostgresTestBase() {

    private fun freshClient(): EntClient = EntClient(resetAndDriver()) {
        privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
    }

    @Test
    fun `an edge not requested by the query is Unloaded`() {
        val client = freshClient()
        client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()

        val user = client.users.query { }.allOrThrow().single()

        assertEquals(EdgeState.Unloaded, user.edges.articles)
        assertEquals(EdgeState.Unloaded, user.edges.groups)
        assertFalse(user.edges.articles.isLoaded)
        assertNull(user.edges.articles.loadedOrNull())
        assertNull(user.edges.articles.valueOrNull())
        assertFailsWith<EdgeNotLoadedException> { user.edges.articles.requireLoaded() }
    }

    @Test
    fun `a requested edge is Loaded while an unrequested sibling stays Unloaded`() {
        val client = freshClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.saveOrThrow()

        val user = client.users.query { withArticles() }.allOrThrow().single()

        assertEquals(listOf("T"), user.edges.articles.requireLoaded().map { it.title })
        assertEquals(EdgeState.Unloaded, user.edges.groups)
    }

    @Test
    fun `an empty to-many load is Loaded(emptyList) not Unloaded`() {
        val client = freshClient()
        client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()

        val user = client.users.query { withArticles() }.allOrThrow().single()

        assertEquals(EdgeState.Loaded(emptyList()), user.edges.articles.loadedOrNull())
        assertTrue(user.edges.articles.requireLoaded().isEmpty())
    }

    @Test
    fun `an empty M2M load is Loaded(emptyList) not Unloaded`() {
        val client = freshClient()
        client.posts.create { title = "p" }.saveOrThrow()

        val post = client.posts.query { withTags() }.allOrThrow().single()

        assertEquals(EdgeState.Loaded(emptyList()), post.edges.tags.loadedOrNull())
    }

    @Test
    fun `a non-empty M2M load is Loaded(list)`() {
        val client = freshClient()
        val post = client.posts.create { title = "p" }.saveOrThrow()
        val tag = client.tags.create { name = "t" }.saveOrThrow()
        client.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tag.id) }.saveOrThrow() }

        val loaded = client.posts.query { withTags() }.allOrThrow().single()

        assertEquals(listOf(tag.id), loaded.edges.tags.requireLoaded().map { it.id })
    }

    @Test
    fun `a present to-one load is Loaded(target)`() {
        val client = freshClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.saveOrThrow()

        val article = client.articles.query { withAuthor() }.allOrThrow().single()

        assertTrue(article.edges.author.isLoaded)
        assertEquals(author.id, article.edges.author.requireLoaded()?.id)
    }

    @Test
    fun `an absent nullable to-one load is Loaded(null) not Unloaded`() {
        val client = freshClient()
        client.reminders.create { body = "unassigned" }.saveOrThrow()

        val reminder = client.reminders.query { withAssignee() }.allOrThrow().single()

        // loadedOrNull() preserves the distinction valueOrNull() collapses:
        // the edge WAS requested, there just is no target.
        assertEquals(EdgeState.Loaded(null), reminder.edges.assignee.loadedOrNull())
        assertNull(reminder.edges.assignee.valueOrNull())
        assertNull(reminder.edges.assignee.requireLoaded())
    }

    @Test
    fun `a required belongsTo filtered out by an eager predicate is Loaded(null)`() {
        val client = freshClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.saveOrThrow()

        // authorId is a required FK — the row names a target — but the
        // eager subquery's own predicate excludes it, so the edge is
        // loaded with no returned target, never Unloaded.
        val article = client.articles.query {
            withAuthor { where(User.name eq "nobody") }
        }.allOrThrow().single()

        assertEquals(EdgeState.Loaded(null), article.edges.author.loadedOrNull())
    }

    @Test
    fun `a required belongsTo filtered out by an eager interceptor is Loaded(null)`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("name", Op.EQ, "nobody"))
                    },
                    name = "exclude-authors",
                )
            }
        }
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.saveOrThrow()

        val article = client.articles.query { withAuthor() }.allOrThrow().single()

        assertEquals(EdgeState.Loaded(null), article.edges.author.loadedOrNull())
    }

    @Test
    fun `nested eager loads set state at every requested level`() {
        val client = freshClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.saveOrThrow()

        val user = client.users.query { withArticles { withAuthor() } }.allOrThrow().single()

        val article = user.edges.articles.requireLoaded().single()
        assertEquals(author.id, article.edges.author.requireLoaded()?.id)
    }

    @Test
    fun `a nested level not requested stays Unloaded under a loaded parent`() {
        val client = freshClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        client.articles.create { title = "T"; authorId = author.id }.saveOrThrow()

        val user = client.users.query { withArticles() }.allOrThrow().single()

        val article = user.edges.articles.requireLoaded().single()
        assertEquals(EdgeState.Unloaded, article.edges.author)
    }

    @Test
    fun `create and update returns keep the default unloaded state`() {
        val client = freshClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        val created = client.articles.create { title = "T"; authorId = author.id }.saveOrThrow()

        assertEquals(EdgeState.Unloaded, created.edges.author)

        val updated = client.withTransaction { tx ->
            tx.articles.update(created.id) { title = "T2" }.saveOrThrow()
        }
        assertEquals(EdgeState.Unloaded, updated.edges.author)
    }
}
