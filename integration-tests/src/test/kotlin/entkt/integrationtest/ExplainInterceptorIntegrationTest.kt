package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.privacy.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the explain API per the read-path interceptors.
 * Pins:
 *
 *  - Every per-terminal explain method (`explainAll`,
 *    `explainFirstOrNull`, `explainRawCount`, `explainRawExists`,
 *    plus repo-level `explainFindById`) runs interceptors with the
 *    right ReadOperation.
 *  - Rejection produces a `QueryPlan` with `rejected = true` and the
 *    rejection metadata — NOT a thrown exception. Callers that want
 *    exception-style explain chain `requireNotRejected()`, which
 *    throws the stored typed EntQueryRejectedException.
 *  - Post-interceptor predicates / annotations / limits surface in the
 *    plan.
 */
class ExplainInterceptorIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    // ---------- Operation routing per explain method ----------

    @Test
    fun `explainAll runs interceptors with operation = ALL`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "obs")
            }
        }
        client.posts.query().explainAll()
        assertEquals(listOf(ReadOperation.ALL), ops)
    }

    @Test
    fun `explainFirstOrNull runs interceptors with operation = FIRST`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "obs")
            }
        }
        client.posts.query().explainFirstOrNull()
        assertEquals(listOf(ReadOperation.FIRST), ops)
    }

    @Test
    fun `aggregate explain methods route their own operations`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "obs")
            }
        }
        client.posts.query().explainRawCount()
        client.posts.query().explainRawExists()
        assertEquals(
            listOf(
                ReadOperation.RAW_COUNT,
                ReadOperation.RAW_EXISTS,
            ),
            ops,
        )
    }

    @Test
    fun `explainFindById runs interceptors with BY_ID`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "obs")
            }
        }
        client.posts.explainFindById(1L)
        assertEquals(listOf(ReadOperation.BY_ID), ops)
    }

    // ---------- Predicate & annotation surfacing ----------

    @Test
    fun `explain output reflects interceptor-added predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "intercepted"))
                    },
                    name = "title-filter",
                )
            }
        }
        val plan = client.posts.query().explainAll()
        assertFalse(plan.rejected)
        assertNotNull(plan.root)
        assertTrue(
            plan.root.toString().contains("title"),
            "explain plan should mention interceptor-added title predicate; was: ${plan.root}",
        )
    }

    @Test
    fun `explainRawCount reflects interceptor-added predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "x"))
                    },
                    name = "title-filter",
                )
            }
        }
        val plan = client.posts.query().explainRawCount()
        assertNotNull(plan.root)
        assertTrue(
            plan.root.toString().contains("title"),
            "explainRawCount plan should mention interceptor predicate; was: ${plan.root}",
        )
    }

    @Test
    fun `explain plans carry annotations`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addAnnotation("tenant", "acme")
                    },
                    name = "annotator",
                )
            }
        }
        val plan = client.posts.query().explainAll()
        assertEquals("acme", plan.annotations["tenant"])
    }

    // ---------- Rejection mapping (plan, not throw) ----------

    @Test
    fun `interceptor reject produces a rejected plan with metadata, not a throw`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope", code = "ex_rej") },
                    name = "rejector",
                )
            }
        }
        val plan = client.posts.query().explainAll()
        assertTrue(plan.rejected)
        assertEquals("nope", plan.rejectedReason)
        assertEquals("ex_rej", plan.rejectedCode)
        assertEquals("rejector", plan.rejectedInterceptor)
        // No driver subplan on rejection.
        assertNull(plan.root)
    }

    @Test
    fun `every explain variant returns a rejected plan, never throws, on reject`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rejector",
                )
            }
        }
        // Verify NO throw on any explain variant.
        listOf(
            client.posts.query().explainAll(),
            client.posts.query().explainFirstOrNull(),
            client.posts.query().explainRawCount(),
            client.posts.query().explainRawExists(),
            client.posts.explainFindById(1L),
        ).forEach { plan ->
            assertTrue(plan.rejected, "expected rejected plan, got root=${plan.root}")
            assertEquals("rejector", plan.rejectedInterceptor)
        }
    }

    @Test
    fun `requireNotRejected throws on rejected plan and returns same plan otherwise`() {
        val driver = freshDriver()

        // Rejected case: throws the stored typed exception.
        val rejectingClient = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { scope, _ -> scope.reject("nope") }, name = "rej")
            }
        }
        val rejected = rejectingClient.posts.query().explainAll()
        val ex = assertFailsWith<EntQueryRejectedException> { rejected.requireNotRejected() }
        assertEquals("nope", ex.reason)
        assertEquals("rej", ex.interceptor)

        // Happy case: identity.
        val driver2 = freshDriver()
        val happyClient = EntClient(driver2) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }
        val plan = happyClient.posts.query().explainAll()
        assertFalse(plan.rejected)
        assertTrue(plan === plan.requireNotRejected())
    }

    @Test
    fun `requireNotRejected throws when only an eager subplan is rejected`() {
        val driver = freshDriver()
        // The rejector is installed on the eager TARGET entity, so it
        // fires on the EAGER_LOAD step, not on the root users query:
        // the plan's root stays clean while eagerQueries["articles"]
        // records the rejection.
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                articles(
                    QueryInterceptor { scope, _ -> scope.reject("no articles", code = "art") },
                    name = "art-rej",
                )
            }
        }
        val plan = client.users.query { loadArticles() }.explainAll()
        assertFalse(plan.rejected, "root plan must not be rejected — the rejection is edge-scoped")
        assertTrue(plan.eagerQueries.getValue("articles").rejected)

        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        assertEquals("no articles", ex.reason)
        assertEquals("art-rej", ex.interceptor)
    }

    @Test
    fun `rejected plan render includes the rejection metadata`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("no broad scans", code = "broad") },
                    name = "scan-guard",
                )
            }
        }
        val rendered = client.posts.query().explainAll().render()
        assertTrue(rendered.contains("REJECTED by 'scan-guard'"), "render() should describe the rejector; was:\n$rendered")
        assertTrue(rendered.contains("code=broad"), "render() should include the code; was:\n$rendered")
        assertTrue(rendered.contains("no broad scans"), "render() should include the reason; was:\n$rendered")
    }

    // ---------- Sanity ----------

    @Test
    fun `with no interceptors explainAll produces a plan with no synthetic predicates`() {
        val driver = freshDriver()
        val client = EntClient(driver) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }
        val plan = client.posts.query().explainAll()
        assertFalse(plan.rejected)
        assertNotNull(plan.root)
        assertTrue(
            !plan.root!!.toString().contains("title"),
            "with no interceptors the plan should not have synthetic predicates; was: ${plan.root}",
        )
    }
}
