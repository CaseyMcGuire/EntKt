package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleQuery
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
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
 * and its framework-owned explain metadata
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
 * - Each eager subplan carries typed `eagerExecution` metadata —
 *   set-batched nested execution, the effective order, and the
 *   in-memory window emulation with its overfetch risk — on a
 *   framework-owned field that caller-controlled annotations
 *   cannot override.
 */
class EagerEffectiveOrderIntegrationTest : PostgresTestBase() {

    private class ShapeCapture(
        val orderBy: List<OrderField<Article>>,
        val callerOrderBy: List<OrderField<Article>>,
        val hasCallerOrderBy: Boolean,
    )

    private fun capturingClient(captured: MutableList<ShapeCapture>): EntClient =
        EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        // Titles deliberately sort against creation order so an
        // id-ordered result can't be mistaken for title order.
        client.articles.create { title = "z-first"; authorId = a.id }.save().getOrThrow()
        client.articles.create { title = "a-second"; authorId = a.id }.save().getOrThrow()

        val users = client.users.query { loadArticles() }.all().getOrThrow()

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
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()

        client.users.query { loadArticles { orderBy(Article.title.desc()) } }.all().getOrThrow()

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
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()

        client.users.query { loadArticles { orderBy(Article.id.desc()) } }.all().getOrThrow()

        // The caller ordered by the primary key (any direction), so
        // the framework appends nothing — the caller's total order
        // stands.
        val shape = captured.single()
        assertEquals(listOf(OrderField<Article>("id", OrderDirection.DESC)), shape.orderBy)
        assertEquals(listOf(OrderField<Article>("id", OrderDirection.DESC)), shape.callerOrderBy)
    }

    @Test
    fun `tied rows enter a finite per-parent window deterministically by primary key`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        val first = client.articles.create { title = "same"; authorId = a.id }.saveAndLoad().getOrThrow()
        val second = client.articles.create { title = "same"; authorId = a.id }.saveAndLoad().getOrThrow()
        client.articles.create { title = "same"; authorId = a.id }.save().getOrThrow()

        val users = client.users.query {
            loadArticles {
                orderBy(Article.title.asc())
                limit(2)
            }
        }.all().getOrThrow()

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
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.ALL) rootOrderBy = scope.shape.orderBy
                    },
                    name = "root-shape-observer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()

        client.users.query { loadArticles() }.all().getOrThrow()

        // The deterministic-ordering rule is scoped to eager target
        // queries; the root read keeps exactly what the caller wrote
        // (here: nothing).
        assertEquals(emptyList(), assertNotNull(rootOrderBy))
    }

    @Test
    fun `explain reports set-batched execution, the effective order, and the emulated window per eager path`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()

        // Selected in reverse declaration order — the plan's edge
        // entries still follow schema-declaration order, matching the
        // depth-first execution order the runtime tests pin.
        val plan = client.users.query {
            loadGroups()
            loadArticles {
                orderBy(Article.title.asc())
                limit(5)
                offset(2)
                loadAuthor()
            }
        }.explainAll()

        assertNull(plan.eagerExecution, "framework metadata belongs to eager subplans, not the root")
        assertEquals(listOf("articles", "groups"), plan.eagerQueries.keys.toList())

        val articles = assertNotNull(plan.eagerQueries.getValue("articles").eagerExecution)
        assertTrue(articles.setBatchedNestedExecution)
        assertEquals(
            listOf(
                OrderField<Article>("title", OrderDirection.ASC),
                OrderField<Article>("id", OrderDirection.ASC),
            ),
            articles.effectiveOrder,
        )
        assertEquals(EagerWindowStrategy.IN_MEMORY_EMULATED, articles.windowStrategy)
        assertEquals(5, articles.perParentLimit)
        assertEquals(2, articles.perParentOffset)
        assertTrue(articles.windowOverfetchRisk, "a finite emulated window overfetches")

        // Nested eager subplans carry the metadata too — one per
        // logical edge step, recursively.
        val nestedAuthor = assertNotNull(
            plan.eagerQueries.getValue("articles").eagerQueries.getValue("author").eagerExecution,
        )
        assertEquals(listOf(OrderField<Any>("id", OrderDirection.ASC)), nestedAuthor.effectiveOrder)

        val groups = assertNotNull(plan.eagerQueries.getValue("groups").eagerExecution)
        assertEquals(listOf(OrderField<Any>("id", OrderDirection.ASC)), groups.effectiveOrder)
        assertNull(groups.perParentLimit)
        assertFalse(groups.windowOverfetchRisk, "an unbounded window discards nothing")

        // The human-readable rendering labels the emulation without
        // claiming any row-fetch reduction.
        val rendered = plan.render()
        assertContains(rendered, "set-batched nested loads")
        assertContains(rendered, "in-memory emulation")
    }

    @Test
    fun `overfetch risk is not reported for windows that fetch nothing`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()

        val plan = client.users.query {
            loadArticles { limit(0) }
            loadProfile { offset(1) }
        }.explainAll()

        // limit(0) skips the driver fetch on every edge kind, and a
        // to-one edge's fetch is gated on a window admitting its
        // at-most-one candidate — neither step can fetch a row it
        // discards, so neither may claim overfetch.
        val articles = assertNotNull(plan.eagerQueries.getValue("articles").eagerExecution)
        assertEquals(0, articles.perParentLimit)
        assertFalse(articles.windowOverfetchRisk, "limit(0) never reaches the driver")
        val profile = assertNotNull(plan.eagerQueries.getValue("profile").eagerExecution)
        assertEquals(1, profile.perParentOffset)
        assertFalse(profile.windowOverfetchRisk, "a to-one edge never fetches a row it discards")
        assertFalse(plan.render().contains("may overfetch"))
    }

    @Test
    fun `mid-explain bounds mutation cannot change the reported window metadata`() {
        var captured: ArticleQuery? = null
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                articles(
                    QueryInterceptor { _, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) captured?.offset(3)
                    },
                    name = "mid-explain-bounds-mutator",
                )
            }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()
        val query = client.users.query()
        var target: ArticleQuery? = null
        query.loadArticles { target = this }
        captured = target

        val plan = query.explainAll()

        // The metadata reads the spec frozen before the interceptor
        // chain ran, so it describes the window this plan's execution
        // would actually use — not the mid-flight mutation, which
        // governs later executions only.
        val exec = assertNotNull(plan.eagerQueries.getValue("articles").eagerExecution)
        assertNull(exec.perParentOffset)
        assertFalse(exec.windowOverfetchRisk)
    }

    @Test
    fun `an application annotation cannot override the framework execution metadata`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            scope.addAnnotation("entkt.eager.window", "native")
                            scope.addAnnotation("strategy", "not-set-batched")
                        }
                    },
                    name = "strategy-spoofer",
                )
            }
        }
        client.users.create { name = "A"; email = "a@example.com" }.save().getOrThrow()

        val plan = client.users.query { loadArticles { limit(3) } }.explainAll()

        // The annotations land in the caller-controlled map…
        val articlesPlan = plan.eagerQueries.getValue("articles")
        assertEquals("native", articlesPlan.annotations["entkt.eager.window"])
        // …while the typed framework field reports the real strategy.
        val exec = assertNotNull(articlesPlan.eagerExecution)
        assertTrue(exec.setBatchedNestedExecution)
        assertEquals(EagerWindowStrategy.IN_MEMORY_EMULATED, exec.windowStrategy)
        assertTrue(exec.windowOverfetchRisk)
    }
}
