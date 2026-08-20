package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresBindLimitException
import entkt.query.Op
import entkt.query.Predicate
import entkt.integrationtest.ent.User
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pins the PostgreSQL bind-parameter guard: a rendered statement whose
 * FINAL parameter count exceeds the protocol's 65,535-parameter limit
 * is rejected deterministically before it is prepared or sent —
 * surfaced as [PostgresBindLimitException] with an actionable message —
 * instead of the JDBC driver's opaque protocol error. The count covers
 * every bind the statement carries (IDs, other predicates, ordering
 * operands), because they all share one budget.
 *
 * Eager relationship loads are the common trigger (their `IN (...)`
 * lists grow with the parent set and are not yet chunked); the guard
 * lives on the driver's data-dependent operations, so these tests
 * exercise it directly with large `IN` predicates rather than seeding
 * tens of thousands of parent rows. `insertMany` and `deleteManyByIds`
 * already chunk physical statements and are deliberately unguarded.
 */
class BindLimitPostgresIntegrationTest : PostgresTestBase() {

    private val limit = 65_535

    private fun idsOfSize(n: Int): List<Long> = (1L..n.toLong()).toList()

    @Test
    fun `a statement binding exactly the limit executes`() {
        val driver = resetAndDriver()

        val rows = driver.query(
            "users",
            listOf(Predicate.Leaf<Any>("id", Op.IN, idsOfSize(limit))),
            emptyList(),
            null,
            null,
        )

        assertEquals(emptyList(), rows)
    }

    @Test
    fun `a statement one bind over the limit is rejected before reaching PostgreSQL`() {
        val driver = resetAndDriver()

        val ex = assertFailsWith<PostgresBindLimitException> {
            driver.query(
                "users",
                listOf(Predicate.Leaf<Any>("id", Op.IN, idsOfSize(limit + 1))),
                emptyList(),
                null,
                null,
            )
        }
        assertContains(ex.message!!, "65,536")
        assertContains(ex.message!!, "65,535")
        assertContains(ex.message!!, "query")
        assertContains(ex.message!!, "\"users\"")
        assertContains(ex.message!!, "not yet chunked")
    }

    @Test
    fun `every bind counts toward the same budget, not only relationship IDs`() {
        val driver = resetAndDriver()

        // Exactly at the limit on its own, pushed one over by an
        // ordinary extra predicate.
        val ex = assertFailsWith<PostgresBindLimitException> {
            driver.query(
                "users",
                listOf(
                    Predicate.Leaf<Any>("id", Op.IN, idsOfSize(limit)),
                    Predicate.Leaf<Any>("name", Op.EQ, "x"),
                ),
                emptyList(),
                null,
                null,
            )
        }
        assertContains(ex.message!!, "65,536")
    }

    @Test
    fun `the rejection surfaces as a Failed read result through a terminal`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }

        val result = client.users.query {
            where(Predicate.Leaf<User>("id", Op.IN, idsOfSize(limit + 1)))
        }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<PostgresBindLimitException>(failed.exception)
        val thrown = assertFailsWith<PostgresBindLimitException> { result.getOrThrow() }
        assertSame(ex, thrown)
    }
}
