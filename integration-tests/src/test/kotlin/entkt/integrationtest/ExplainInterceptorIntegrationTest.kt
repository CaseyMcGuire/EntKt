package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.EntQueryRejectedException
import entkt.runtime.InMemoryDriver
import entkt.runtime.PrivacyContext
import entkt.runtime.QueryInterceptor
import entkt.runtime.ReadOperation
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 8 coverage: every explain method runs the interceptor chain
 * with the operation that matches its terminal counterpart, and the
 * resulting [QueryPlan] reflects every predicate, limit, and offset
 * mutation the chain produced.
 *
 *  - explain() → ALL
 *  - explainFirst() → FIRST (clamped at 1)
 *  - explainExists() → RAW_EXISTS (clamped at 1)
 *  - explainVisibleCount() → VISIBLE_COUNT
 *  - explainRawCount() → RAW_COUNT
 *
 * Rejection on an explain method surfaces as EntQueryRejectedException
 * (same throw model as non-result terminals like `rawCount`).
 */
class ExplainInterceptorIntegrationTest {

    private fun freshDriver(): InMemoryDriver = InMemoryDriver().apply {
        EntClient.SCHEMAS.forEach(::register)
    }

    @Test
    fun `explain runs interceptors with operation = ALL`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "observer",
                )
            }
        }
        client.posts.query().explain()
        assertEquals(listOf(ReadOperation.ALL), ops)
    }

    @Test
    fun `explainFirst runs interceptors with operation = FIRST`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "observer")
            }
        }
        client.posts.query().explainFirst()
        assertEquals(listOf(ReadOperation.FIRST), ops)
    }

    @Test
    fun `explainExists runs interceptors with operation = RAW_EXISTS`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "observer")
            }
        }
        client.posts.query().explainExists()
        assertEquals(listOf(ReadOperation.RAW_EXISTS), ops)
    }

    @Test
    fun `explainVisibleCount runs interceptors with operation = VISIBLE_COUNT`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "observer")
            }
        }
        client.posts.query().explainVisibleCount()
        assertEquals(listOf(ReadOperation.VISIBLE_COUNT), ops)
    }

    @Test
    fun `explainRawCount runs interceptors with operation = RAW_COUNT`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "observer")
            }
        }
        client.posts.query().explainRawCount()
        assertEquals(listOf(ReadOperation.RAW_COUNT), ops)
    }

    @Test
    fun `explain output reflects an interceptor-added predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "intercepted"))
                    },
                    name = "title-filter",
                )
            }
        }
        val plan = client.posts.query().explain()
        // The plan's root description should mention the interceptor-
        // added predicate's field, since explain renders the post-
        // interceptor spec.
        assertTrue(
            plan.root.toString().contains("title"),
            "explain plan should mention interceptor-added title predicate; was: ${plan.root}",
        )
    }

    @Test
    fun `explainRawCount reflects an interceptor-added predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        assertTrue(
            plan.root.toString().contains("title"),
            "explainRawCount plan should mention interceptor predicate; was: ${plan.root}",
        )
    }

    @Test
    fun `interceptor reject surfaces as EntQueryRejectedException from explain`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("denied", code = "ex_rej") },
                    name = "rejector",
                )
            }
        }
        val ex = assertFailsWith<EntQueryRejectedException> {
            client.posts.query().explain()
        }
        assertEquals("ex_rej", ex.queryRejected.code)
    }

    @Test
    fun `interceptor reject surfaces from explainRawCount, explainFirst, explainExists, explainVisibleCount`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rejector",
                )
            }
        }
        assertFailsWith<EntQueryRejectedException> { client.posts.query().explainRawCount() }
        assertFailsWith<EntQueryRejectedException> { client.posts.query().explainFirst() }
        assertFailsWith<EntQueryRejectedException> { client.posts.query().explainExists() }
        assertFailsWith<EntQueryRejectedException> { client.posts.query().explainVisibleCount() }
    }

    @Test
    fun `with no interceptors registered explain produces a plan with no extra predicates`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
        }
        val plan = client.posts.query().explain()
        // No interceptor predicates means the root's predicates list is
        // exactly what the caller wrote (empty here).
        assertTrue(
            !plan.root.toString().contains("title"),
            "with no interceptors the plan should not have synthetic predicates; was: ${plan.root}",
        )
    }
}
