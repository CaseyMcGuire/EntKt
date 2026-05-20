package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.ent.User
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.EdgeStep
import entkt.runtime.EntOperation
import entkt.runtime.EntQueryRejectedException
import entkt.runtime.InMemoryDriver
import entkt.runtime.PrivacyContext
import entkt.runtime.QueryContext
import entkt.runtime.QueryInterceptor
import entkt.runtime.ReadOperation
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 5 coverage: interceptors fire correctly on edge traversal,
 * eager-load subqueries, and `has` / `hasWhere` edge predicates per
 * the read-path interceptors RFC. Pins:
 *
 *  - 5a edge traversal: `queryX()` fires source interceptors with
 *    `EDGE_TRAVERSAL`; target terminal sees `sourceEntity`,
 *    `edgeName`, `path` set per the RFC's multi-step chain rules
 *  - 5b eager-load: `.with{Edge}` fires target interceptors with
 *    `EAGER_LOAD` (`context.isEagerSubquery == true`); interceptor
 *    predicates flow into the eager subquery
 *  - 5c edge-predicate: `has` / `hasWhere` (Predicate.HasEdgeWith)
 *    fires target interceptors with `EDGE_PREDICATE`; interceptor
 *    predicates narrow the EXISTS subquery
 */
class ReadInterceptorEdgesIntegrationTest {

    private fun freshDriver(): InMemoryDriver = InMemoryDriver().apply {
        EntClient.SCHEMAS.forEach(::register)
    }

    // ---------- 5a: Edge traversal ----------

    @Test
    fun `queryX fires source interceptors with EDGE_TRAVERSAL operation`() {
        val driver = freshDriver()
        val seen = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                users(
                    QueryInterceptor { _, ctx -> seen.add(ctx.operation) },
                    name = "user-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveOrThrow()

        // queryArticles fires User.interceptors with EDGE_TRAVERSAL
        // before materializing the bridging predicate. The terminal
        // then fires Article.interceptors with ALL (Article has no
        // interceptors here so the count is just 1).
        client.users.query().queryArticles().allOrThrow()
        assertEquals(listOf(ReadOperation.EDGE_TRAVERSAL), seen)
    }

    @Test
    fun `terminal on traversal-derived query sets sourceEntity, edgeName, and path on context`() {
        val driver = freshDriver()
        var captured: QueryContext? = null
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> captured = ctx },
                    name = "article-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveOrThrow()

        client.users.query().queryArticles().allOrThrow()

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
            privacyContext { PrivacyContext(Viewer.System) }
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
        val alice = client.users.create { name = "alice"; email = "alice@x" }.saveOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@x" }.saveOrThrow()
        client.articles.create {
            title = "alice's article"
            authorId = alice.id
        }.saveOrThrow()
        client.articles.create {
            title = "bob's article"
            authorId = bob.id
        }.saveOrThrow()

        val result = client.users.query().queryArticles().allOrThrow()
        assertEquals(1, result.size)
        assertEquals("alice's article", result[0].title)
    }

    @Test
    fun `rejection on the source step surfaces as EntQueryRejectedException`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                users(
                    QueryInterceptor { scope, _ -> scope.reject("source rejection", code = "src_rej") },
                    name = "source-rejector",
                )
            }
        }
        val ex = assertFailsWith<EntQueryRejectedException> {
            client.users.query().queryArticles().allOrThrow()
        }
        // Rejection comes from the source step (User EDGE_TRAVERSAL).
        assertEquals("src_rej", ex.queryRejected.code)
        assertEquals("source-rejector", ex.queryRejected.interceptor)
        assertEquals("User", ex.queryRejected.entity)
    }

    @Test
    fun `M2M traversal terminal context carries source entity, edge, and path`() {
        val driver = freshDriver()
        var captured: QueryContext? = null
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        client.posts.create { title = "p1" }.saveOrThrow()

        // Post → tags (M2M traversal). The terminal on TagQuery
        // fires Tag.interceptors with operation ALL, but
        // sourceEntity / edgeName / path reflect the traversal.
        client.posts.query().queryTags().allOrThrow()

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
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "article-observer",
                )
            }
        }
        val u = client.users.create { name = "A"; email = "a@x" }.saveOrThrow()
        client.articles.create { title = "X"; authorId = u.id }.saveOrThrow()

        client.users.query().withArticles().allOrThrow()

        // Article interceptors fire once for the eager subquery.
        // (The root User.query() doesn't fire Article interceptors —
        // those only fire on the eager step.)
        assertEquals(listOf(ReadOperation.EAGER_LOAD), ops)
    }

    @Test
    fun `eager subquery context exposes isEagerSubquery and the source path`() {
        val driver = freshDriver()
        var captured: QueryContext? = null
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> captured = ctx },
                    name = "article-observer",
                )
            }
        }
        val u = client.users.create { name = "A"; email = "a@x" }.saveOrThrow()
        client.articles.create { title = "X"; authorId = u.id }.saveOrThrow()

        client.users.query().withArticles().allOrThrow()

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
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                articles(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("published", Op.EQ, true))
                    },
                    name = "only-published",
                )
            }
        }
        val u = client.users.create { name = "A"; email = "a@x" }.saveOrThrow()
        client.articles.create { title = "draft"; published = false; authorId = u.id }.saveOrThrow()
        client.articles.create { title = "published"; published = true; authorId = u.id }.saveOrThrow()

        val users = client.users.query().withArticles().allOrThrow()
        assertEquals(1, users.size)
        // Eager interceptor's predicate narrows the eager fetch.
        assertEquals(listOf("published"), users[0].edges.articles!!.map { it.title })
    }

    // ---------- 5c: Edge-predicate (has / hasWhere) ----------

    @Test
    fun `HasEdgeWith fires target interceptors with EDGE_PREDICATE operation`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "article-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveOrThrow()

        // Trigger an edge-predicate read on User via Article.author.has.
        client.users.query {
            where(User.articles.has { where(Article.published eq true) })
        }.allOrThrow()

        // EDGE_PREDICATE was fired on Article (the inner predicate's
        // target entity).
        assertEquals(listOf(ReadOperation.EDGE_PREDICATE), ops)
    }

    @Test
    fun `HasEdgeWith target interceptor narrows the EXISTS subquery`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        val alice = client.users.create { name = "alice"; email = "a@x" }.saveOrThrow()
        val bob = client.users.create { name = "bob"; email = "b@x" }.saveOrThrow()
        // Alice has only a draft article: with the interceptor active
        // she should NOT match "users with any article" (no published
        // articles → no matching row).
        client.articles.create { title = "alice draft"; published = false; authorId = alice.id }.saveOrThrow()
        // Bob has a published article: should match.
        client.articles.create { title = "bob published"; published = true; authorId = bob.id }.saveOrThrow()

        val users = client.users.query {
            where(User.articles.has { /* no inner — matches "any" */ })
        }.allOrThrow()

        assertEquals(listOf("bob"), users.map { it.name })
    }

    @Test
    fun `edge-predicate context carries sourceEntity, edgeName, and path`() {
        val driver = freshDriver()
        var captured: QueryContext? = null
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> captured = ctx },
                    name = "article-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveOrThrow()

        client.users.query {
            where(User.articles.has { where(Article.published eq true) })
        }.allOrThrow()

        val ctx = assertNotNull(captured)
        assertEquals(User::class, ctx.sourceEntity)
        assertEquals("articles", ctx.edgeName)
        assertEquals(1, ctx.path.size)
        assertEquals(EdgeStep(User::class, "articles", Article::class), ctx.path[0])
    }

    @Test
    fun `rejection from edge-predicate target interceptor surfaces as EntQueryRejectedException`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                articles(
                    QueryInterceptor { scope, _ -> scope.reject("nope", code = "edge_pred_rej") },
                    name = "article-rejector",
                )
            }
        }
        client.users.create { name = "A"; email = "a@x" }.saveOrThrow()

        val ex = assertFailsWith<EntQueryRejectedException> {
            client.users.query {
                where(User.articles.has { where(Article.published eq true) })
            }.allOrThrow()
        }
        assertEquals("edge_pred_rej", ex.queryRejected.code)
        assertEquals("article-rejector", ex.queryRejected.interceptor)
        assertEquals("Article", ex.queryRejected.entity)
    }

    // ---------- Sanity: no interceptors registered → identity ----------

    @Test
    fun `with no interceptors, traversal eager and has all behave exactly as before`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
        }
        val u = client.users.create { name = "A"; email = "a@x" }.saveOrThrow()
        client.articles.create { title = "x"; published = true; authorId = u.id }.saveOrThrow()

        // Edge traversal returns rows.
        assertEquals(1, client.users.query().queryArticles().allOrThrow().size)
        // Eager-load returns rows.
        val withEager = client.users.query().withArticles().allOrThrow()
        assertEquals(1, withEager[0].edges.articles!!.size)
        // has-predicate works.
        assertEquals(
            1,
            client.users.query {
                where(User.articles.has { where(Article.published eq true) })
            }.allOrThrow().size,
        )
    }
}
