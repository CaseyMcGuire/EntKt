package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.postgres.PostgresBindLimitException
import entkt.runtime.query.QueryInterceptor
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

        // The oversized IN list trips the render-time pre-check, whose
        // message carries the exact projected total and the offending
        // predicate.
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
        assertContains(ex.message!!, "users.id")
        assertContains(ex.message!!, "not yet chunked")
    }

    /**
     * A virtual collection that is never materialized: if any layer
     * copied it or expanded its placeholders before a budget check,
     * the read counter would show ten million element reads.
     */
    private class VirtualIds : AbstractList<Long>() {
        var reads = 0
        override val size: Int get() = 10_000_000
        override fun get(index: Int): Long {
            reads++
            return index.toLong()
        }
    }

    @Test
    fun `an oversized IN list is rejected at render without being expanded or copied`() {
        val driver = resetAndDriver()
        val virtual = VirtualIds()

        val ex = assertFailsWith<PostgresBindLimitException> {
            driver.query(
                "users",
                listOf(Predicate.Leaf<Any>("id", Op.IN, virtual)),
                emptyList(),
                null,
                null,
            )
        }
        assertContains(ex.message!!, "10,000,000")
        assertContains(ex.message!!, "65,535")
        assertEquals(0, virtual.reads, "the projected-size pre-check must reject before reading any element")
    }

    @Test
    fun `the public query path rejects an oversized IN before any snapshot copies`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        val virtual = VirtualIds()

        // The terminal-entry capacity check consults the driver's
        // declared budget from the lists' O(1) sizes BEFORE the spec
        // builder takes its defensive operand snapshots — the
        // ordinary client path must not deep-copy an enormous operand
        // several times on the way to the render-time rejection.
        val result = client.users.query {
            where(Predicate.Leaf<User>("id", Op.IN, virtual))
        }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<PostgresBindLimitException>(failed.exception)
        assertContains(ex.message!!, "10,000,000")
        assertContains(ex.message!!, "\"users\"")
        assertEquals(0, virtual.reads, "no layer may materialize the operand before the capacity check")
    }

    @Test
    fun `rawCount captures an oversized IN failure without copying its operand`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        val virtual = VirtualIds()
        recording.reset()

        val result = client.users.query {
            where(Predicate.Leaf<User>("id", Op.IN, virtual))
        }.rawCount()

        val failed = assertIs<ReadResult.Failed>(result)
        assertIs<PostgresBindLimitException>(failed.exception)
        assertEquals(0, virtual.reads, "query capture must check capacity before snapshotting")
        assertEquals(emptyList(), recording.calls, "no SQL may be submitted")
    }

    @Test
    fun `an interceptor-added oversized IN is rejected before its snapshot with no SQL submitted`() {
        val recording = RecordingDriver(resetAndDriver())
        val virtual = VirtualIds()
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf<User>("id", Op.IN, virtual))
                    },
                    name = "hostile-acl",
                )
            }
        }
        recording.reset()

        // The running budget is enforced at the addPredicate call
        // itself — before the operand snapshot — so a tenant/ACL
        // interceptor contributing a huge IN never materializes it
        // and nothing reaches the driver.
        val result = client.users.query().all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<PostgresBindLimitException>(failed.exception)
        assertContains(ex.message!!, "10,000,000")
        assertEquals(0, virtual.reads, "the interceptor's operand must never be iterated or copied")
        assertEquals(emptyList(), recording.calls, "no SQL may be submitted")
    }

    @Test
    fun `the typed DSL's in operator does not materialize an oversized collection`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        val virtual = VirtualIds()
        recording.reset()

        // `column in values` no longer copies at construction —
        // operands snapshot at terminal entry, after the capacity
        // check — so the idiomatic typed path is as lazy as raw
        // Predicate.Leaf construction.
        val result = client.users.query {
            where(User.id `in` virtual)
        }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        assertIs<PostgresBindLimitException>(failed.exception)
        assertEquals(0, virtual.reads, "the DSL must not copy the collection eagerly")
        assertEquals(emptyList(), recording.calls, "no SQL may be submitted")
    }

    @Test
    fun `the enum DSL's in operator does not materialize its mapped operand either`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        // The collection LENGTH is caller-controlled even though the
        // distinct constants are bounded: a huge list of repeated
        // enum values must not be mapped to storage names before the
        // capacity check.
        var reads = 0
        val virtual = object : AbstractList<entkt.integrationtest.schema.OrderStatus>() {
            override val size: Int get() = 10_000_000
            override fun get(index: Int): entkt.integrationtest.schema.OrderStatus {
                reads++
                return entkt.integrationtest.schema.OrderStatus.PENDING
            }
        }
        recording.reset()

        val result = client.orders.query {
            where(entkt.integrationtest.ent.Order.status `in` virtual)
        }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        assertIs<PostgresBindLimitException>(failed.exception)
        assertEquals(0, reads, "the enum mapping must be lazy — no element may be read or mapped")
        assertEquals(emptyList(), recording.calls, "no SQL may be submitted")
    }

    @Test
    fun `the capacity check also guards reads inside a transaction`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        val virtual = VirtualIds()

        val outcome = client.withTransaction { tx ->
            val result = tx.users.query {
                where(Predicate.Leaf<User>("id", Op.IN, virtual))
            }.all()
            val failed = assertIs<ReadResult.Failed>(result)
            assertIs<PostgresBindLimitException>(failed.exception)
            "checked"
        }.getOrThrow()

        assertEquals("checked", outcome)
        assertEquals(0, virtual.reads)
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
