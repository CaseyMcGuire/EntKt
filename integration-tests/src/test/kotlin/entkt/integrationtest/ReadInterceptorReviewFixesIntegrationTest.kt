// Integration test exercises the EDGE_PREDICATE walker by directly
// constructing `Predicate.HasEdgeWith(...)` predicates against the
// generated query classes. Those constructors carry @EntktInternal
// The file-level opt-in lets the
// fabrication compile. The test scenario is exactly what the walker
// is designed for, just bypassing the generated `EdgeRef.has(...)`
// DSL to control the test inputs precisely.
@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.query.GlobalQueryInterceptor
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.privacy.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for fixes identified post-contract review:
 *
 * - **P1** limit interceptor methods are no-ops on read shapes where
 *   row limits have no meaning (BY_ID / FIRST / aggregates / EAGER /
 *   EDGE_PREDICATE) — so `global { rejectIfLimitGreaterThan(10) }`
 *   no longer rejects `findById` / `rawCount` / `rawExists`, and
 *   `setDefaultLimitIfAbsent` / `requireLimitAtMost` never silently
 *   clamp the no-limit shapes.
 *
 * - **P2b** by-id reads route through the *Query's
 *   `runReadInterceptors` so `Edge.has { ... }` predicates added by
 *   a by-id interceptor are walked and fire target `EDGE_PREDICATE`
 *   interceptors.
 */
class ReadInterceptorReviewFixesIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    // ---------- Limit ops scoped by ReadOperation ----------

    @Test
    fun `rejectIfLimitGreaterThan is a silent no-op for rawCount`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                global(
                    GlobalQueryInterceptor { scope, _ ->
                        scope.rejectIfLimitGreaterThan(10) { "no broad scans" }
                    },
                    name = "max-limit",
                )
            }
        }
        client.posts.create { title = "x" }.saveAndLoad().getOrThrow()
        // rawCount has no row limit semantics; the gate should
        // NOT trigger even though effective limit is null.
        assertEquals(1L, client.posts.query().rawCount().getOrThrow())
    }

    @Test
    fun `rejectIfLimitGreaterThan is a silent no-op for findById`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                global(
                    GlobalQueryInterceptor { scope, _ ->
                        scope.rejectIfLimitGreaterThan(10) { "no broad scans" }
                    },
                    name = "max-limit",
                )
            }
        }
        val p = client.posts.create { title = "x" }.saveAndLoad().getOrThrow()
        // by-id is intrinsic single-row; the gate should not fire.
        assertNotNull(client.posts.findById(p.id).getOrThrow())
    }

    @Test
    fun `setDefaultLimitIfAbsent(0) is a silent no-op for firstOrNull`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                // Without the P1 fix this would set spec.limit = 0
                // and firstOrNull's driver fetch would be
                // `minOf(1, 0) = 0` rows — a caller who set no bound
                // would silently never see a row.
                global(
                    GlobalQueryInterceptor { scope, _ ->
                        scope.setDefaultLimitIfAbsent(0)
                    },
                    name = "default-limit-0",
                )
            }
        }
        client.posts.create { title = "x" }.saveAndLoad().getOrThrow()
        // FIRST is a no-limit-ops shape: only the caller's own bound
        // may shrink the single-row fetch.
        assertNotNull(client.posts.query().firstOrNull().getOrThrow())
    }

    @Test
    fun `requireLimitAtMost(0) is a silent no-op for rawExists`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                // Without the P1 fix this would clamp spec.limit to 0,
                // and rawExists' driver call would fetch 0 rows
                // and always return false.
                global(
                    GlobalQueryInterceptor { scope, _ -> scope.requireLimitAtMost(0) },
                    name = "clamp-zero",
                )
            }
        }
        client.posts.create { title = "x" }.saveAndLoad().getOrThrow()
        assertTrue(client.posts.query().rawExists().getOrThrow())
    }

    @Test
    fun `requireLimitAtMost applies normally for all`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                global(
                    GlobalQueryInterceptor { scope, _ -> scope.requireLimitAtMost(2) },
                    name = "cap-2",
                )
            }
        }
        repeat(5) { i -> client.posts.create { title = "p$i" }.saveAndLoad().getOrThrow() }
        // ALL is the operation where limit ops apply normally —
        // here the clamp DOES take effect.
        assertEquals(2, client.posts.query().all().getOrThrow().size)
    }

    @Test
    fun `findById walks HasEdgeWith predicates added by a by-id interceptor`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        val u = client.users.create { name = "A"; email = "a@x" }.saveAndLoad().getOrThrow()
        // The user has no published article, so the narrowed by-id read
        // returns Success(null); the walker still ran.
        assertNull(client.users.findById(u.id).getOrThrow())

        // Article observer must have fired with EDGE_PREDICATE
        // (the walker ran on the by-id User's inner predicate).
        assertTrue(
            ops.contains(ReadOperation.EDGE_PREDICATE),
            "findById should run the edge-predicate walker; expected EDGE_PREDICATE in $ops",
        )
    }

}
