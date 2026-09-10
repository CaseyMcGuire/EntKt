package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.QueryContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Edge traversal query blocks: `queryX { ... }` accepts the same
 * defaulted target-scope receiver block as repository and index
 * `query { ... }` helpers. The block configures the *target* query
 * only — traversal seeding, source snapshotting, and interceptor
 * context are byte-for-byte the chained-call behavior.
 */
class TraversalQueryBlockIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    private fun bypassClient(driver: PostgresDriver): EntClient = EntClient(driver)

    @Test
    fun `block form is equivalent to chaining on the returned query`() {
        val driver = freshDriver()
        val client = bypassClient(driver)
        val author = client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
        for (t in listOf("c-article", "a-article", "b-article")) {
            client.articles.create { title = t; authorId = author.id }.save(testViewerContext).getOrThrow()
        }

        val chained = client.users.query().queryArticles()
            .where(Article.title neq "c-article")
            .orderBy(Article.title.desc())
            .limit(2)
            .all(testViewerContext)
            .getOrThrow()
        val blocked = client.users.query().queryArticles {
            where(Article.title neq "c-article")
            orderBy(Article.title.desc())
            limit(2)
        }.all(testViewerContext).getOrThrow()

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
        val author = client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "keep"; authorId = author.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "drop"; authorId = author.id }.save(testViewerContext).getOrThrow()

        val result = client.users.query().queryArticles {
            where(Article.title eq "keep")
        }.all(testViewerContext).getOrThrow()

        assertEquals(listOf("keep"), result.map { it.title })
    }

    @Test
    fun `M2M traversal accepts the same block`() {
        val driver = freshDriver()
        val client = bypassClient(driver)
        val post = client.posts.create { title = "p" }.saveAndLoad(testViewerContext).getOrThrow()
        val keep = client.tags.create { name = "keep" }.saveAndLoad(testViewerContext).getOrThrow()
        val drop = client.tags.create { name = "drop" }.saveAndLoad(testViewerContext).getOrThrow()
        client.postTags.create { postId = post.id; tagId = keep.id }.save(testViewerContext).getOrThrow()
        client.postTags.create { postId = post.id; tagId = drop.id }.save(testViewerContext).getOrThrow()

        val result = client.posts.query { where(Post.id eq post.id) }
            .queryTags { where(Tag.name eq "keep") }
            .all(testViewerContext)
            .getOrThrow()

        assertEquals(listOf("keep"), result.map { it.name })
    }

    @Test
    fun `block form retains its source query when another branch is refined`() {
        val driver = freshDriver()
        val client = bypassClient(driver)
        val alice = client.users.create { name = "alice"; email = "alice@x" }.saveAndLoad(testViewerContext).getOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "alice-article"; authorId = alice.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "bob-article"; authorId = bob.id }.save(testViewerContext).getOrThrow()

        val users = client.users.query()
        val articles = users.queryArticles {
            orderBy(Article.title.asc())
        }

        val onlyAlice = users.where(User.name eq "alice")
        assertEquals(listOf("alice"), onlyAlice.all(testViewerContext).getOrThrow().map { it.name })

        assertEquals(
            listOf("alice-article", "bob-article"),
            articles.all(testViewerContext).getOrThrow().map { it.title },
            "a traversal must retain the original source description, not a later branch",
        )
    }

    @Test
    fun `block form fires the same traversal interceptor context`() {
        val driver = freshDriver()
        val sourceOps = mutableListOf<ReadOperation>()
        var captured: QueryContext? = null
        val client = EntClient(driver) {

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
        val author = client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "t"; authorId = author.id }.save(testViewerContext).getOrThrow()

        client.users.query().queryArticles { limit(5) }.all(testViewerContext).getOrThrow()

        assertEquals(listOf(ReadOperation.EDGE_TRAVERSAL), sourceOps)
        val ctx = assertNotNull(captured)
        assertEquals(User::class, ctx.sourceEntity)
        assertEquals("articles", ctx.edgeName)
        assertEquals(listOf(EdgeStep(User::class, "articles", Article::class)), ctx.path)
        assertEquals(User::class, ctx.rootEntity)
        assertEquals(Article::class, ctx.currentEntity)
    }
}
