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
import entkt.runtime.EdgeStep
import entkt.runtime.EntQueryRejectedException
import entkt.runtime.GlobalQueryInterceptor
import entkt.runtime.PrivacyContext
import entkt.runtime.QueryInterceptor
import entkt.runtime.ReadOperation
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for four fixes identified post-RFC review:
 *
 * - **P1** limit interceptor methods are no-ops on read shapes where
 *   row limits have no meaning (BY_ID / FIRST / aggregates / EAGER /
 *   EDGE_PREDICATE) — so `global { rejectIfLimitGreaterThan(10) }`
 *   no longer rejects `byIdOrNull` / `rawCount` / `rawExists`, and
 *   `setDefaultLimitIfAbsent(100)` no longer silently truncates
 *   `visibleCount`.
 *
 * - **P2a** eager-load `explain()` plans fire target interceptors
 *   with `EAGER_LOAD` (not `ALL`) and reflect post-interceptor
 *   predicates / annotations.
 *
 * - **P2b** by-id reads route through the *Query's
 *   `runReadInterceptors` so `Edge.has { ... }` predicates added by
 *   a by-id interceptor are walked and fire target `EDGE_PREDICATE`
 *   interceptors.
 *
 * - **P3** annotations attached via `scope.addAnnotation` surface
 *   on `QueryPlan.annotations` so observability / explain consumers
 *   can read them.
 */
class ReadInterceptorReviewFixesIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    // ---------- P1: limit ops scoped by ReadOperation ----------

    @Test
    fun `rejectIfLimitGreaterThan is a silent no-op for rawCount`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                global(
                    GlobalQueryInterceptor { scope, _ ->
                        scope.rejectIfLimitGreaterThan(10) { "no broad scans" }
                    },
                    name = "max-limit",
                )
            }
        }
        client.posts.create { title = "x" }.saveOrThrow()
        // rawCount has no row limit semantics; the gate should
        // NOT trigger even though effective limit is null.
        assertEquals(1L, client.posts.query().rawCount())
    }

    @Test
    fun `rejectIfLimitGreaterThan is a silent no-op for byIdOrNull`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                global(
                    GlobalQueryInterceptor { scope, _ ->
                        scope.rejectIfLimitGreaterThan(10) { "no broad scans" }
                    },
                    name = "max-limit",
                )
            }
        }
        val p = client.posts.create { title = "x" }.saveOrThrow()
        // byId is intrinsic single-row; the gate should not fire.
        assertNotNull(client.posts.byIdOrNull(p.id))
    }

    @Test
    fun `setDefaultLimitIfAbsent is a silent no-op for visibleCount`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                // Without the P1 fix this would set spec.limit = 2,
                // and visibleCount would return "count of first 2
                // scanned rows" instead of "count of all rows".
                global(
                    GlobalQueryInterceptor { scope, _ ->
                        scope.setDefaultLimitIfAbsent(2)
                    },
                    name = "default-limit-2",
                )
            }
        }
        repeat(5) { i -> client.posts.create { title = "p$i" }.saveOrThrow() }
        // visibleCount must materialize all matching rows to evaluate
        // privacy correctly. A 2-row clamp would corrupt the count.
        assertEquals(5L, client.posts.query().visibleCount())
    }

    @Test
    fun `requireLimitAtMost(0) is a silent no-op for visibleExists`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                // Without the P1 fix this would clamp spec.limit to 0,
                // and visibleExists' driver call would fetch 0 rows
                // and always return false.
                global(
                    GlobalQueryInterceptor { scope, _ -> scope.requireLimitAtMost(0) },
                    name = "clamp-zero",
                )
            }
        }
        client.posts.create { title = "x" }.saveOrThrow()
        assertTrue(client.posts.query().visibleExists())
    }

    @Test
    fun `requireLimitAtMost applies normally for allOrThrow`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                global(
                    GlobalQueryInterceptor { scope, _ -> scope.requireLimitAtMost(2) },
                    name = "cap-2",
                )
            }
        }
        repeat(5) { i -> client.posts.create { title = "p$i" }.saveOrThrow() }
        // ALL is the operation where limit ops apply normally —
        // here the clamp DOES take effect.
        assertEquals(2, client.posts.query().allOrThrow().size)
    }

    // ---------- P2a: eager-load explain fires EAGER_LOAD ----------

    @Test
    fun `eager-load explain fires target interceptors with EAGER_LOAD`() {
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

        client.users.query().withArticles().explainAllOrThrow()

        // The eager subquery for "articles" should have fired
        // Article's interceptors with EAGER_LOAD (not ALL).
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "explain on a withArticles() query should fire Article interceptors with EAGER_LOAD; observed: $ops",
        )
        assertTrue(
            !ops.contains(ReadOperation.ALL),
            "explain on a withArticles() query must NOT fire Article interceptors with ALL; observed: $ops",
        )
    }

    @Test
    fun `eager-load explain plan reflects target interceptor predicates`() {
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
        val plan = client.users.query().withArticles().explainAllOrThrow()

        val articlesEdge = plan.eagerQueries["articles"]
        assertNotNull(articlesEdge, "explain plan should include an 'articles' edge subplan")
        // The eager subplan's root description should mention the
        // interceptor-added 'published' predicate.
        assertNotNull(articlesEdge.root, "eager subplan should have a driver root")
        assertTrue(
            articlesEdge.root!!.toString().contains("published"),
            "eager-load explain subplan should reflect target interceptor predicates; was: ${articlesEdge.root}",
        )
    }

    // ---------- P2b: byId edge-predicate walk ----------

    @Test
    fun `byIdOrNull walks HasEdgeWith predicates added by a by-id interceptor`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                // A by-id User interceptor adds an Edge predicate.
                // The walker should fire Article's EDGE_PREDICATE
                // interceptors on the inner.
                users(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(
                            Predicate.HasEdgeWith(
                                "articles",
                                Predicate.Leaf("published", Op.EQ, true),
                            ),
                        )
                    },
                    name = "require-published-article",
                )
                articles(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "article-observer",
                )
            }
        }
        val u = client.users.create { name = "A"; email = "a@x" }.saveOrThrow()
        client.users.byIdOrNull(u.id)

        // Article observer must have fired with EDGE_PREDICATE
        // (the walker ran on the by-id User's inner predicate).
        assertTrue(
            ops.contains(ReadOperation.EDGE_PREDICATE),
            "byIdOrNull should run the edge-predicate walker; expected EDGE_PREDICATE in $ops",
        )
    }

    // ---------- P3: annotations on QueryPlan ----------

    @Test
    fun `explain reflects scope addAnnotation on QueryPlan annotations`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addAnnotation("tenant", "acme")
                        scope.addAnnotation("audit-id", "abc-123")
                    },
                    name = "annotator",
                )
            }
        }
        val plan = client.posts.query().explainAllOrThrow()
        assertEquals("acme", plan.annotations["tenant"])
        assertEquals("abc-123", plan.annotations["audit-id"])

        // Annotations also surface in render() output for human
        // observability consumers.
        assertTrue(
            plan.render().contains("tenant=acme"),
            "render() should include the annotation suffix; was:\n${plan.render()}",
        )
    }

    @Test
    fun `explainRawCount carries annotations on QueryPlan`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.addAnnotation("k", "v") },
                    name = "annotator",
                )
            }
        }
        val plan = client.posts.query().explainRawCount()
        assertEquals("v", plan.annotations["k"])
    }
}
