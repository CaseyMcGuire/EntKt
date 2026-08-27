package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleQuery
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.runtime.query.EagerWindowStrategy
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireLoaded
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the set-based eager loader's deterministic-ordering contract
 * (`docs/possible-features/query/set-based-eager-graph-loader.md`,
 * "Ordering Contract" and "Explain"):
 *
 * - Every eager target query executes one total effective order —
 *   the caller's `orderBy` terms followed by the target primary key
 *   ascending, unless the caller already ordered by the primary
 *   key. Interceptors see that effective order on `shape.orderBy`
 *   and the caller-authored terms separately on
 *   `shape.callerOrderBy` / `hasCallerOrderBy`.
 * - Root (non-eager) queries gain no framework ordering term.
 * - Tied rows enter a finite per-parent window deterministically.
 */
class EagerEffectiveOrderIntegrationTest : PostgresTestBase() {

    private class ShapeCapture(
        val orderBy: List<OrderField<Article>>,
        val callerOrderBy: List<OrderField<Article>>,
        val hasCallerOrderBy: Boolean,
    )

    private fun capturingClient(captured: MutableList<ShapeCapture>): EntClient =
        EntClient(resetAndDriver()) {

            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            val shape = scope.shape
                            captured.add(
                                ShapeCapture(shape.orderBy, shape.callerOrderBy, shape.hasCallerOrderBy),
                            )
                        }
                    },
                    name = "article-shape-observer",
                )
            }
        }

    @Test
    fun `an eager query with no caller ordering executes primary-key ascending order`() {
        val captured = mutableListOf<ShapeCapture>()
        val client = capturingClient(captured)
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        // Titles deliberately sort against creation order so an
        // id-ordered result can't be mistaken for title order.
        client.articles.create { title = "z-first"; authorId = a.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "a-second"; authorId = a.id }.save(testViewerContext).getOrThrow()

        val users = client.users.query { loadArticles() }.all(testViewerContext).getOrThrow()

        val shape = captured.single()
        assertEquals(listOf(OrderField<Article>("id", OrderDirection.ASC)), shape.orderBy)
        assertEquals(emptyList(), shape.callerOrderBy)
        assertFalse(shape.hasCallerOrderBy)
        // The effective order drives storage and association order:
        // creation (id) order, not title order and not driver whim.
        assertEquals(
            listOf("z-first", "a-second"),
            users.single().edges.articles.requireLoaded().map { it.title },
        )
    }

    @Test
    fun `caller ordering gains the primary-key tie-breaker`() {
        val captured = mutableListOf<ShapeCapture>()
        val client = capturingClient(captured)
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()

        client.users.query { loadArticles { orderBy(Article.title.desc()) } }.all(testViewerContext).getOrThrow()

        val shape = captured.single()
        assertEquals(
            listOf(
                OrderField<Article>("title", OrderDirection.DESC),
                OrderField<Article>("id", OrderDirection.ASC),
            ),
            shape.orderBy,
        )
        assertEquals(listOf(OrderField<Article>("title", OrderDirection.DESC)), shape.callerOrderBy)
        assertTrue(shape.hasCallerOrderBy)
    }

    @Test
    fun `caller ordering that already includes the primary key is not extended`() {
        val captured = mutableListOf<ShapeCapture>()
        val client = capturingClient(captured)
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()

        client.users.query { loadArticles { orderBy(Article.id.desc()) } }.all(testViewerContext).getOrThrow()

        // The caller ordered by the primary key (any direction), so
        // the framework appends nothing — the caller's total order
        // stands.
        val shape = captured.single()
        assertEquals(listOf(OrderField<Article>("id", OrderDirection.DESC)), shape.orderBy)
        assertEquals(listOf(OrderField<Article>("id", OrderDirection.DESC)), shape.callerOrderBy)
    }

    @Test
    fun `tied rows enter a finite per-parent window deterministically by primary key`() {
        val client = EntClient(resetAndDriver())
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val first = client.articles.create { title = "same"; authorId = a.id }.saveAndLoad(testViewerContext).getOrThrow()
        val second = client.articles.create { title = "same"; authorId = a.id }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "same"; authorId = a.id }.save(testViewerContext).getOrThrow()

        val users = client.users.query {
            loadArticles {
                orderBy(Article.title.asc())
                limit(2)
            }
        }.all(testViewerContext).getOrThrow()

        // All three rows tie on the caller's term; the primary-key
        // tie-breaker decides which two enter the window, so the
        // result cannot vary with driver row order.
        assertEquals(
            listOf(first.id, second.id),
            users.single().edges.articles.requireLoaded().map { it.id },
        )
    }

    @Test
    fun `a root query gains no framework ordering term`() {
        var rootOrderBy: List<OrderField<User>>? = null
        val client = EntClient(resetAndDriver()) {

            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.ALL) rootOrderBy = scope.shape.orderBy
                    },
                    name = "root-shape-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()

        client.users.query { loadArticles() }.all(testViewerContext).getOrThrow()

        // The deterministic-ordering rule is scoped to eager target
        // queries; the root read keeps exactly what the caller wrote
        // (here: nothing).
        assertEquals(emptyList(), assertNotNull(rootOrderBy))
    }

}
