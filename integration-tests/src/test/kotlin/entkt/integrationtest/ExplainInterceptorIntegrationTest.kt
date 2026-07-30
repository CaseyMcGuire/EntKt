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
 *  - Every per-terminal explain method (`explainAllOrThrow`,
 *    `explainAllOrError`, `explainVisibleAll`, `explainVisibleAllOrError`,
 *    `explainFirstOrThrow`, `explainFirstOrNull`, `explainFirstOrError`,
 *    `explainFirstVisibleOrNull`, `explainRawCount`, `explainVisibleCount`,
 *    `explainRawExists`, `explainVisibleExists`, plus repo-level
 *    `explainByIdOrThrow` / `explainByIdOrNull` /
 *    `explainVisibleByIdOrNull` / `explainByIdOrError`) runs interceptors
 *    with the right ReadOperation.
 *  - Rejection produces a `QueryPlan` with `rejected = true` and the
 *    rejection metadata — NOT a thrown exception. Callers that want
 *    exception-style explain chain `requireNotRejected()`.
 *  - Post-interceptor predicates / annotations / limits surface in the
 *    plan.
 */
class ExplainInterceptorIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    // ---------- Operation routing per explain method ----------

    @Test
    fun `explainAllOrThrow runs interceptors with operation = ALL`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "obs")
            }
        }
        client.posts.query().explainAllOrThrow()
        assertEquals(listOf(ReadOperation.ALL), ops)
    }

    @Test
    fun `every All-shaped explain method routes ALL`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "obs")
            }
        }
        client.posts.query().explainAllOrThrow()
        client.posts.query().explainAllOrError()
        client.posts.query().explainVisibleAll()
        client.posts.query().explainVisibleAllOrError()
        assertEquals(List(4) { ReadOperation.ALL }, ops)
    }

    @Test
    fun `every First-shaped explain method routes FIRST`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "obs")
            }
        }
        client.posts.query().explainFirstOrThrow()
        client.posts.query().explainFirstOrNull()
        client.posts.query().explainFirstOrError()
        client.posts.query().explainFirstVisibleOrNull()
        assertEquals(List(4) { ReadOperation.FIRST }, ops)
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
        client.posts.query().explainVisibleCount()
        client.posts.query().explainRawExists()
        client.posts.query().explainVisibleExists()
        assertEquals(
            listOf(
                ReadOperation.RAW_COUNT,
                ReadOperation.VISIBLE_COUNT,
                ReadOperation.RAW_EXISTS,
                ReadOperation.VISIBLE_EXISTS,
            ),
            ops,
        )
    }

    @Test
    fun `every explainByIdX variant runs interceptors with BY_ID`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "obs")
            }
        }
        client.posts.explainByIdOrThrow(1L)
        client.posts.explainByIdOrNull(1L)
        client.posts.explainVisibleByIdOrNull(1L)
        client.posts.explainByIdOrError(1L)
        assertEquals(List(4) { ReadOperation.BY_ID }, ops)
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
        val plan = client.posts.query().explainAllOrThrow()
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
        val plan = client.posts.query().explainAllOrThrow()
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
        val plan = client.posts.query().explainAllOrThrow()
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
            client.posts.query().explainAllOrThrow(),
            client.posts.query().explainAllOrError(),
            client.posts.query().explainVisibleAll(),
            client.posts.query().explainVisibleAllOrError(),
            client.posts.query().explainFirstOrThrow(),
            client.posts.query().explainFirstOrNull(),
            client.posts.query().explainFirstOrError(),
            client.posts.query().explainFirstVisibleOrNull(),
            client.posts.query().explainRawCount(),
            client.posts.query().explainVisibleCount(),
            client.posts.query().explainRawExists(),
            client.posts.query().explainVisibleExists(),
            client.posts.explainByIdOrThrow(1L),
            client.posts.explainByIdOrNull(1L),
            client.posts.explainVisibleByIdOrNull(1L),
            client.posts.explainByIdOrError(1L),
        ).forEach { plan ->
            assertTrue(plan.rejected, "expected rejected plan, got root=${plan.root}")
            assertEquals("rejector", plan.rejectedInterceptor)
        }
    }

    @Test
    fun `requireNotRejected throws on rejected plan and returns same plan otherwise`() {
        val driver = freshDriver()

        // Rejected case: throws.
        val rejectingClient = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { scope, _ -> scope.reject("nope") }, name = "rej")
            }
        }
        val rejected = rejectingClient.posts.query().explainAllOrThrow()
        val ex = assertFailsWith<EntQueryRejectedException> { rejected.requireNotRejected() }
        assertEquals("nope", ex.queryRejected.reason)
        assertEquals("rej", ex.queryRejected.interceptor)

        // Happy case: identity.
        val driver2 = freshDriver()
        val happyClient = EntClient(driver2) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }
        val plan = happyClient.posts.query().explainAllOrThrow()
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
        val plan = client.users.query().withArticles().explainAllOrThrow()
        assertFalse(plan.rejected, "root plan must not be rejected — the rejection is edge-scoped")
        assertTrue(plan.eagerQueries.getValue("articles").rejected)

        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        assertEquals("no articles", ex.queryRejected.reason)
        assertEquals("art-rej", ex.queryRejected.interceptor)
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
        val rendered = client.posts.query().explainAllOrThrow().render()
        assertTrue(rendered.contains("REJECTED by 'scan-guard'"), "render() should describe the rejector; was:\n$rendered")
        assertTrue(rendered.contains("code=broad"), "render() should include the code; was:\n$rendered")
        assertTrue(rendered.contains("no broad scans"), "render() should include the reason; was:\n$rendered")
    }

    // ---------- Sanity ----------

    @Test
    fun `with no interceptors explainAllOrThrow produces a plan with no synthetic predicates`() {
        val driver = freshDriver()
        val client = EntClient(driver) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }
        val plan = client.posts.query().explainAllOrThrow()
        assertFalse(plan.rejected)
        assertNotNull(plan.root)
        assertTrue(
            !plan.root!!.toString().contains("title"),
            "with no interceptors the plan should not have synthetic predicates; was: ${plan.root}",
        )
    }
}
