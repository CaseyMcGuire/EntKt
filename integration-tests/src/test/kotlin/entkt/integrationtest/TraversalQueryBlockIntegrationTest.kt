// Fabricates a raw Predicate.Leaf to mutate a source query after the
// traversal call, exercising the snapshot boundary (same opt-in story
// as ReadInterceptorRound3FixesIntegrationTest).
@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.QueryContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Edge traversal query blocks: `queryX { ... }` accepts the same
 * defaulted target-query receiver block as repository and index
 * `query { ... }` helpers. The block configures the *target* query
 * only — traversal seeding, source snapshotting, and interceptor
 * context are byte-for-byte the chained-call behavior.
 */
class TraversalQueryBlockIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    private fun bypassClient(driver: PostgresDriver): EntClient = EntClient(driver) {
        privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
    }

    @Test
    fun `block form is equivalent to chaining on the returned query`() {
        val driver = freshDriver()
        val client = bypassClient(driver)
        val author = client.users.create { name = "A"; email = "a@x" }.saveOrThrow()
        for (t in listOf("c-article", "a-article", "b-article")) {
            client.articles.create { title = t; authorId = author.id }.saveOrThrow()
        }

        val chained = client.users.query().queryArticles()
            .where(Article.title neq "c-article")
            .orderBy(Article.title.desc())
            .limit(2)
            .allOrThrow()
        val blocked = client.users.query().queryArticles {
            where(Article.title neq "c-article")
            orderBy(Article.title.desc())
            limit(2)
        }.allOrThrow()

        assertEquals(listOf("b-article", "a-article"), chained.map { it.title })
        assertEquals(
            chained.map { it.id },
            blocked.map { it.id },
            "queryArticles { ... } must produce the same rows as chaining on queryArticles()",
        )
    }

    @Test
    fun `block predicates are applied to the target query`() {
        val driver = freshDriver()
        val client = bypassClient(driver)
        val author = client.users.create { name = "A"; email = "a@x" }.saveOrThrow()
        client.articles.create { title = "keep"; authorId = author.id }.saveOrThrow()
        client.articles.create { title = "drop"; authorId = author.id }.saveOrThrow()

        val result = client.users.query().queryArticles {
            where(Article.title eq "keep")
        }.allOrThrow()

        assertEquals(listOf("keep"), result.map { it.title })
    }

    @Test
    fun `M2M traversal accepts the same block`() {
        val driver = freshDriver()
        val client = bypassClient(driver)
        val post = client.posts.create { title = "p" }.saveOrThrow()
        val keep = client.tags.create { name = "keep" }.saveOrThrow()
        val drop = client.tags.create { name = "drop" }.saveOrThrow()
        client.postTags.create { postId = post.id; tagId = keep.id }.saveOrThrow()
        client.postTags.create { postId = post.id; tagId = drop.id }.saveOrThrow()

        val result = client.posts.query { where(Post.id eq post.id) }
            .queryTags { where(Tag.name eq "keep") }
            .allOrThrow()

        assertEquals(listOf("keep"), result.map { it.name })
    }

    @Test
    fun `block form still snapshots source state at traversal-call time`() {
        val driver = freshDriver()
        val client = bypassClient(driver)
        val alice = client.users.create { name = "alice"; email = "alice@x" }.saveOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@x" }.saveOrThrow()
        client.articles.create { title = "alice-article"; authorId = alice.id }.saveOrThrow()
        client.articles.create { title = "bob-article"; authorId = bob.id }.saveOrThrow()

        val users = client.users.query()
        val articles = users.queryArticles {
            orderBy(Article.title.asc())
        }

        // Mutate the source AFTER the traversal call: the block must
        // not change when the snapshot is taken, so this where stays
        // invisible to the bridge predicate.
        users.where(Predicate.Leaf("name", Op.EQ, "alice"))

        assertEquals(
            listOf("alice-article", "bob-article"),
            articles.allOrThrow().map { it.title },
            "queryX { ... } must snapshot source state at traversal-call time, same as queryX()",
        )
    }

    @Test
    fun `block form fires the same traversal interceptor context`() {
        val driver = freshDriver()
        val sourceOps = mutableListOf<ReadOperation>()
        var captured: QueryContext? = null
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { _, ctx -> sourceOps.add(ctx.operation) },
                    name = "user-observer",
                )
                articles(
                    QueryInterceptor { _, ctx -> captured = ctx },
                    name = "article-observer",
                )
            }
        }
        val author = client.users.create { name = "A"; email = "a@x" }.saveOrThrow()
        client.articles.create { title = "t"; authorId = author.id }.saveOrThrow()

        client.users.query().queryArticles { limit(5) }.allOrThrow()

        assertEquals(listOf(ReadOperation.EDGE_TRAVERSAL), sourceOps)
        val ctx = assertNotNull(captured)
        assertEquals(User::class, ctx.sourceEntity)
        assertEquals("articles", ctx.edgeName)
        assertEquals(listOf(EdgeStep(User::class, "articles", Article::class)), ctx.path)
        assertEquals(User::class, ctx.rootEntity)
        assertEquals(Article::class, ctx.currentEntity)
    }
}
