// Edge-predicate walker integration tests construct
// `Predicate.HasEdgeWith` / `Predicate.HasEdge` directly to drive
// the walker through specific shapes. Those constructors carry
// `@EntktInternal`; the
// file-level opt-in lets the test code construct them.
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
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.QueryContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * coverage: interceptors fire correctly on edge traversal,
 * eager-load subqueries, and `has` / `hasWhere` edge predicates per
 * the read-path interceptors. Pins:
 *
 *  - 5a edge traversal: a terminal on a `queryX()` chain fires source
 *    interceptors with `EDGE_TRAVERSAL`; target terminal sees
 *    `sourceEntity`, `edgeName`, `path` set by multi-step chain rules;
 *    source rejection is captured as
 *    `ReadResult.Failed(EntQueryRejectedException)`
 *  - 5b eager-load: `load{Edge}` fires target interceptors with
 *    `EAGER_LOAD` (`context.isEagerSubquery == true`); interceptor
 *    predicates flow into the eager subquery
 *  - 5c edge-predicate: `has` / `hasWhere` (Predicate.HasEdgeWith)
 *    fires target interceptors with `EDGE_PREDICATE`; interceptor
 *    predicates narrow the EXISTS subquery
 */
class ReadInterceptorEdgesIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    // ---------- 5a: Edge traversal ----------

    @Test
    fun `queryX fires source interceptors with EDGE_TRAVERSAL operation`() {
        val driver = freshDriver()
        val seen = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            interceptors {
                users(
                    QueryInterceptor { _, ctx -> seen.add(ctx.operation) },
                    name = "user-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()

        // The queryArticles chain fires User.interceptors with
        // EDGE_TRAVERSAL (deferred to terminal time) before
        // materializing the bridging predicate. The terminal then
        // fires Article.interceptors with ALL (Article has no
        // interceptors here so the count is just 1).
        client.users.query().queryArticles().all(testViewerContext).getOrThrow()
        assertEquals(listOf(ReadOperation.EDGE_TRAVERSAL), seen)
    }

    @Test
    fun `terminal on traversal-derived query sets sourceEntity, edgeName, and path on context`() {
        val driver = freshDriver()
        var captured: QueryContext? = null
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> captured = ctx },
                    name = "article-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()

        client.users.query().queryArticles().all(testViewerContext).getOrThrow()

        val ctx = assertNotNull(captured)
        assertEquals(User::class, ctx.sourceEntity)
        assertEquals("articles", ctx.edgeName)
        assertEquals(1, ctx.path.size)
        assertEquals(EdgeStep(User::class, "articles", Article::class), ctx.path[0])
        // rootEntity walks back to the chain's origin (User), not the
        // terminal entity (Article).
        assertEquals(User::class, ctx.rootEntity)
        assertEquals(Article::class, ctx.currentEntity)
    }

    @Test
    fun `source interceptor predicate narrows the bridging EXISTS subquery`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                // Only "alice" users feed the bridge. Article reads
                // via queryArticles see only articles whose author
                // matches the post-interceptor User predicate.
                users(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("name", Op.EQ, "alice"))
                    },
                    name = "only-alice",
                )
            }
        }
        val alice = client.users.create { name = "alice"; email = "alice@x" }.saveAndLoad(testViewerContext).getOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create {
            title = "alice's article"
            authorId = alice.id
        }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create {
            title = "bob's article"
            authorId = bob.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        val result = client.users.query().queryArticles().all(testViewerContext).getOrThrow()
        assertEquals(1, result.size)
        assertEquals("alice's article", result[0].title)
    }

    @Test
    fun `rejection on the source step surfaces as Failed(EntQueryRejectedException)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                users(
                    QueryInterceptor { scope, _ -> scope.reject("source rejection", code = "src_rej") },
                    name = "source-rejector",
                )
            }
        }
        val result = client.users.query().queryArticles().all(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        // Rejection comes from the source step (User EDGE_TRAVERSAL).
        assertEquals("src_rej", ex.code)
        assertEquals("source-rejector", ex.interceptor)
        assertEquals("User", ex.entityType)
    }

    @Test
    fun `M2M traversal terminal context carries source entity, edge, and path`() {
        val driver = freshDriver()
        var captured: QueryContext? = null
        val client = EntClient(driver) {

            interceptors {
                // Observe Tag (the traversal TARGET) at terminal time
                // to verify path / sourceEntity / edgeName are set
                // when the bridging traversal lands.
                tags(
                    QueryInterceptor { _, ctx -> captured = ctx },
                    name = "tag-observer",
                )
            }
        }
        client.posts.create { title = "p1" }.saveAndLoad(testViewerContext).getOrThrow()

        // Post → tags (M2M traversal). The terminal on TagQuery
        // fires Tag.interceptors with operation ALL, but
        // sourceEntity / edgeName / path reflect the traversal.
        client.posts.query().queryTags().all(testViewerContext).getOrThrow()

        val ctx = assertNotNull(captured)
        assertEquals(Post::class, ctx.sourceEntity)
        assertEquals("tags", ctx.edgeName)
        assertEquals(1, ctx.path.size)
        assertEquals(EdgeStep(Post::class, "tags", Tag::class), ctx.path[0])
        assertEquals(Post::class, ctx.rootEntity)
        assertEquals(Tag::class, ctx.currentEntity)
    }

    // ---------- 5b: Eager-load ----------

    @Test
    fun `eager-load subquery fires target interceptors with EAGER_LOAD operation`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "article-observer",
                )
            }
        }
        val u = client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "X"; authorId = u.id }.saveAndLoad(testViewerContext).getOrThrow()

        client.users.query { loadArticles() }.all(testViewerContext).getOrThrow()

        // Article interceptors fire once for the eager subquery.
        // (The root users query doesn't fire Article interceptors —
        // those only fire on the eager step.)
        assertEquals(listOf(ReadOperation.EAGER_LOAD), ops)
    }

    @Test
    fun `eager subquery context exposes isEagerSubquery and the source path`() {
        val driver = freshDriver()
        var captured: QueryContext? = null
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> captured = ctx },
                    name = "article-observer",
                )
            }
        }
        val u = client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "X"; authorId = u.id }.saveAndLoad(testViewerContext).getOrThrow()

        client.users.query { loadArticles() }.all(testViewerContext).getOrThrow()

        val ctx = assertNotNull(captured)
        assertTrue(ctx.isEagerSubquery)
        assertEquals(ReadOperation.EAGER_LOAD, ctx.operation)
        assertEquals(User::class, ctx.sourceEntity)
        assertEquals("articles", ctx.edgeName)
        assertEquals(1, ctx.path.size)
        assertEquals(EdgeStep(User::class, "articles", Article::class), ctx.path[0])
    }

    @Test
    fun `eager interceptor predicate narrows what targets get loaded`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("published", Op.EQ, true))
                    },
                    name = "only-published",
                )
            }
        }
        val u = client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "draft"; published = false; authorId = u.id }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "published"; published = true; authorId = u.id }.saveAndLoad(testViewerContext).getOrThrow()

        val users = client.users.query { loadArticles() }.all(testViewerContext).getOrThrow()
        assertEquals(1, users.size)
        // Eager interceptor's predicate narrows the eager fetch.
        assertEquals(listOf("published"), users[0].edges.articles.requireLoaded().map { it.title })
    }

    // ---------- 5c: Edge-predicate (has / hasWhere) ----------

    @Test
    fun `HasEdgeWith fires target interceptors with EDGE_PREDICATE operation`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "article-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()

        // Trigger an edge-predicate read on User via User.articles.has.
        client.users.query {
            where(User.articles.has { where(Article.published eq true) })
        }.all(testViewerContext).getOrThrow()

        // EDGE_PREDICATE was fired on Article (the inner predicate's
        // target entity).
        assertEquals(listOf(ReadOperation.EDGE_PREDICATE), ops)
    }

    @Test
    fun `HasEdgeWith target interceptor narrows the EXISTS subquery`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { scope, _ ->
                        // Stand-in for soft-delete: only "published"
                        // articles count for the EXISTS check.
                        scope.addPredicate(Predicate.Leaf("published", Op.EQ, true))
                    },
                    name = "only-published",
                )
            }
        }
        val alice = client.users.create { name = "alice"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
        val bob = client.users.create { name = "bob"; email = "b@x" }.saveAndLoad(testViewerContext).getOrThrow()
        // Alice has only a draft article: with the interceptor active
        // she should NOT match "users with any article" (no published
        // articles → no matching row).
        client.articles.create { title = "alice draft"; published = false; authorId = alice.id }.saveAndLoad(testViewerContext).getOrThrow()
        // Bob has a published article: should match.
        client.articles.create { title = "bob published"; published = true; authorId = bob.id }.saveAndLoad(testViewerContext).getOrThrow()

        val users = client.users.query {
            where(User.articles.has { /* no inner — matches "any" */ })
        }.all(testViewerContext).getOrThrow()

        assertEquals(listOf("bob"), users.map { it.name })
    }

    @Test
    fun `edge-predicate context carries sourceEntity, edgeName, and path`() {
        val driver = freshDriver()
        var captured: QueryContext? = null
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> captured = ctx },
                    name = "article-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()

        client.users.query {
            where(User.articles.has { where(Article.published eq true) })
        }.all(testViewerContext).getOrThrow()

        val ctx = assertNotNull(captured)
        assertEquals(User::class, ctx.sourceEntity)
        assertEquals("articles", ctx.edgeName)
        assertEquals(1, ctx.path.size)
        assertEquals(EdgeStep(User::class, "articles", Article::class), ctx.path[0])
    }

    @Test
    fun `rejection from edge-predicate target interceptor surfaces as Failed(EntQueryRejectedException)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { scope, _ -> scope.reject("nope", code = "edge_pred_rej") },
                    name = "article-rejector",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()

        val result = client.users.query {
            where(User.articles.has { where(Article.published eq true) })
        }.all(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("edge_pred_rej", ex.code)
        assertEquals("article-rejector", ex.interceptor)
        assertEquals("Article", ex.entityType)
    }

    // ---------- Sanity: no interceptors registered → identity ----------

    @Test
    fun `with no interceptors, traversal eager and has all behave exactly as before`() {
        val driver = freshDriver()
        val client = EntClient(driver)
        val u = client.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "x"; published = true; authorId = u.id }.saveAndLoad(testViewerContext).getOrThrow()

        // Edge traversal returns rows.
        assertEquals(1, client.users.query().queryArticles().all(testViewerContext).getOrThrow().size)
        // Eager-load returns rows.
        val withEager = client.users.query { loadArticles() }.all(testViewerContext).getOrThrow()
        assertEquals(1, withEager[0].edges.articles.requireLoaded().size)
        // has-predicate works.
        assertEquals(
            1,
            client.users.query {
                where(User.articles.has { where(Article.published eq true) })
            }.all(testViewerContext).getOrThrow().size,
        )
    }
}
