package entkt.runtime

import entkt.runtime.query.QueryPlan
import entkt.runtime.result.EntError
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntQueryRejectedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Pins [QueryPlan.requireNotRejected]'s tree walk: a rejection
 * anywhere in the plan tree (root or any eager subplan, at any
 * depth) throws, because executing the represented query runs the
 * eager interceptor pass too — a root-only check would declare a
 * plan clean that cannot execute. The thrown payload is the first
 * rejection in depth-first order.
 */
class QueryPlanTest {

    private fun rejection(reason: String, interceptor: String = "rej") = EntError.QueryRejected(
        entity = "Post",
        operation = EntOperation.QUERY,
        reason = reason,
        interceptor = interceptor,
    )

    @Test
    fun `requireNotRejected returns the same plan when no node is rejected`() {
        val plan = QueryPlan(
            root = null,
            eagerQueries = mapOf("author" to QueryPlan(root = null)),
        )
        assertSame(plan, plan.requireNotRejected())
    }

    @Test
    fun `requireNotRejected throws on a rejected eager subplan even when the root is clean`() {
        val plan = QueryPlan(
            root = null,
            eagerQueries = mapOf("author" to QueryPlan.rejected(rejection("no authors"))),
        )
        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        assertEquals("no authors", ex.queryRejected.reason)
    }

    @Test
    fun `requireNotRejected finds rejections nested below the first eager level`() {
        val plan = QueryPlan(
            root = null,
            eagerQueries = mapOf(
                "author" to QueryPlan(
                    root = null,
                    eagerQueries = mapOf("posts" to QueryPlan.rejected(rejection("nested"))),
                ),
            ),
        )
        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        assertEquals("nested", ex.queryRejected.reason)
    }

    @Test
    fun `requireNotRejected carries the root rejection when root and eager subplan are both rejected`() {
        val plan = QueryPlan(
            root = null,
            eagerQueries = mapOf("author" to QueryPlan.rejected(rejection("eager"))),
            rejection = rejection("root"),
        )
        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        assertEquals("root", ex.queryRejected.reason)
    }

    @Test
    fun `requireNotRejected walks eager edges in declaration order`() {
        val plan = QueryPlan(
            root = null,
            eagerQueries = mapOf(
                "author" to QueryPlan.rejected(rejection("first")),
                "tags" to QueryPlan.rejected(rejection("second")),
            ),
        )
        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        assertEquals("first", ex.queryRejected.reason)
    }
}
