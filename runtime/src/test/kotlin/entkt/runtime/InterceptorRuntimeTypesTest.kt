package entkt.runtime

import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * coverage: the immutable runtime types added for the
 * read-path interceptors. Wiring into generated reads lands in
 * later phases; this suite pins the data-class invariants
 * (attribution identity, derived properties) and the
 * Err(QueryRejected) ↔ EntQueryRejectedException round-trip so
 * nothing downstream can drift from the documented contract.
 */
class InterceptorRuntimeTypesTest {

    private class A
    private class B

    // ---- QueryShape ----

    @Test
    fun `QueryShape attribution identity holds at construction`() {
        val p1 = Predicate.Leaf<A>("x", Op.EQ, 1)
        val p2 = Predicate.Leaf<A>("y", Op.EQ, 2)
        val shape = QueryShape<A>(
            table = "as",
            predicates = listOf(p1, p2),
            orderBy = emptyList(),
            limit = null,
            callerLimit = null,
            offset = null,
            flags = emptySet(),
            annotations = emptyMap(),
            callerPredicateCount = 1,
            structuralPredicateCount = 0,
            interceptorPredicateCount = 1,
        )
        assertEquals(2, shape.predicates.size)
        assertTrue(shape.hasCallerPredicates)
        assertTrue(shape.hasInterceptorPredicates)
    }

    @Test
    fun `QueryShape rejects construction when attribution sums don't match predicates size`() {
        val ex = assertFailsWith<IllegalStateException> {
            QueryShape<A>(
                table = "as",
                predicates = listOf(Predicate.Leaf<A>("x", Op.EQ, 1), Predicate.Leaf<A>("y", Op.EQ, 2)),
                orderBy = emptyList(),
                limit = null,
                callerLimit = null,
                offset = null,
                flags = emptySet(),
                annotations = emptyMap(),
                callerPredicateCount = 1,
                structuralPredicateCount = 0,
                interceptorPredicateCount = 0,  // total = 1 but predicates.size = 2
            )
        }
        assertTrue(ex.message!!.contains("attribution identity"))
    }

    @Test
    fun `QueryShape derived flags reflect the counts`() {
        val empty = QueryShape<A>(
            table = "as",
            predicates = emptyList(),
            orderBy = emptyList(),
            limit = null,
            callerLimit = null,
            offset = null,
            flags = emptySet(),
            annotations = emptyMap(),
            callerPredicateCount = 0,
            structuralPredicateCount = 0,
            interceptorPredicateCount = 0,
        )
        assertFalse(empty.hasCallerPredicates)
        assertFalse(empty.hasInterceptorPredicates)
    }

    // ---- UntypedQueryShape ----

    @Test
    fun `UntypedQueryShape attribution identity holds at construction`() {
        val shape = UntypedQueryShape(
            table = "as",
            entity = A::class,
            predicateCount = 3,
            callerPredicateCount = 1,
            structuralPredicateCount = 1,
            interceptorPredicateCount = 1,
            limit = null,
            callerLimit = null,
            offset = null,
            hasOrderBy = false,
            annotations = emptyMap(),
        )
        assertTrue(shape.hasCallerPredicates)
        assertTrue(shape.hasInterceptorPredicates)
    }

    @Test
    fun `UntypedQueryShape rejects construction when attribution sums don't match predicate count`() {
        val ex = assertFailsWith<IllegalStateException> {
            UntypedQueryShape(
                table = "as",
                entity = A::class,
                predicateCount = 5,
                callerPredicateCount = 1,
                structuralPredicateCount = 1,
                interceptorPredicateCount = 1,  // total = 3 but predicateCount = 5
                limit = null,
                callerLimit = null,
                offset = null,
                hasOrderBy = false,
                annotations = emptyMap(),
            )
        }
        assertTrue(ex.message!!.contains("attribution identity"))
    }

    // ---- QueryContext ----

    @Test
    fun `QueryContext isEagerSubquery is true only for EAGER_LOAD operation`() {
        val rootCtx = QueryContext(
            privacy = PrivacyContext(Viewer.System),
            operation = ReadOperation.ALL,
            rootEntity = A::class,
            currentEntity = A::class,
            sourceEntity = null,
            edgeName = null,
            path = emptyList(),
            flags = emptySet(),
        )
        assertFalse(rootCtx.isEagerSubquery)

        val eagerCtx = rootCtx.copy(operation = ReadOperation.EAGER_LOAD)
        assertTrue(eagerCtx.isEagerSubquery)
    }

    @Test
    fun `QueryContext path carries each EdgeStep in chain order`() {
        val step1 = EdgeStep(source = A::class, edgeName = "bs", target = B::class)
        val ctx = QueryContext(
            privacy = PrivacyContext(Viewer.System),
            operation = ReadOperation.EDGE_TRAVERSAL,
            rootEntity = A::class,
            currentEntity = B::class,
            sourceEntity = A::class,
            edgeName = "bs",
            path = listOf(step1),
            flags = emptySet(),
        )
        assertEquals(1, ctx.path.size)
        assertEquals(step1, ctx.path[0])
        assertEquals(A::class, ctx.sourceEntity)
        assertEquals(B::class, ctx.currentEntity)
    }

    // ---- EntError.QueryRejected ↔ EntQueryRejectedException ----

    @Test
    fun `EntError QueryRejected toException produces EntQueryRejectedException carrying the rejection`() {
        val rejected = EntError.QueryRejected(
            entity = "Post",
            operation = EntOperation.QUERY,
            reason = "missing tenant scope",
            code = "missing_tenant_scope",
            interceptor = "tenant-scope",
        )
        val ex = rejected.toException()
        assertTrue(ex is EntQueryRejectedException)
        assertSame(rejected, (ex as EntQueryRejectedException).queryRejected)
        assertEquals("missing tenant scope", ex.message)
    }

    @Test
    fun `EntError QueryRejected message defaults to reason`() {
        val rejected = EntError.QueryRejected(
            entity = "Post",
            operation = EntOperation.QUERY,
            reason = "broad rawCount not allowed",
            interceptor = "broad-aggregate-guard",
        )
        assertEquals("broad rawCount not allowed", rejected.message)
    }

    @Test
    fun `EntResult getOrThrow on Err(QueryRejected) throws EntQueryRejectedException`() {
        val rejected = EntError.QueryRejected(
            entity = "Post",
            operation = EntOperation.QUERY,
            reason = "max limit",
            code = "max_limit_exceeded",
            interceptor = "global-max-limit",
        )
        val result: EntResult<List<String>> = EntResult.Err(rejected)
        val ex = assertFailsWith<EntQueryRejectedException> { result.getOrThrow() }
        assertEquals("max_limit_exceeded", ex.queryRejected.code)
        assertEquals("global-max-limit", ex.queryRejected.interceptor)
    }

    // ---- AbortQueryRejected marker ----

    @Test
    fun `AbortQueryRejected wraps the QueryRejected payload and uses a descriptive message`() {
        val rejected = EntError.QueryRejected(
            entity = "Post",
            operation = EntOperation.QUERY,
            reason = "nope",
            interceptor = "test",
        )
        val abort = AbortQueryRejected(rejected)
        assertSame(rejected, abort.rejected)
        assertTrue(abort.message!!.contains("test"))
        assertTrue(abort.message!!.contains("nope"))
    }

    // ---- ReadOperation / QueryFlag enums ----

    @Test
    fun `ReadOperation enumerates every documented read shape`() {
        val expected = setOf(
            ReadOperation.BY_ID,
            ReadOperation.FIRST,
            ReadOperation.ALL,
            ReadOperation.RAW_COUNT,
            ReadOperation.VISIBLE_COUNT,
            ReadOperation.RAW_EXISTS,
            ReadOperation.VISIBLE_EXISTS,
            ReadOperation.RAW_AGGREGATE,
            ReadOperation.EDGE_TRAVERSAL,
            ReadOperation.EDGE_PREDICATE,
            ReadOperation.EAGER_LOAD,
            ReadOperation.DELETE_CANDIDATES,
        )
        assertEquals(expected, ReadOperation.entries.toSet())
    }

    @Test
    fun `QueryFlag carries withDeleted and onlyDeleted only in V1`() {
        assertEquals(
            setOf(QueryFlag.withDeleted, QueryFlag.onlyDeleted),
            QueryFlag.entries.toSet(),
        )
    }

    // Sanity that OrderField is the schema-side type the interceptor scope expects.
    @Test
    fun `QueryShape orderBy threads through OrderField from the schema module`() {
        val shape = QueryShape<A>(
            table = "as",
            predicates = emptyList(),
            orderBy = listOf(OrderField("name", entkt.query.OrderDirection.ASC)),
            limit = null,
            callerLimit = null,
            offset = null,
            flags = emptySet(),
            annotations = emptyMap(),
            callerPredicateCount = 0,
            structuralPredicateCount = 0,
            interceptorPredicateCount = 0,
        )
        assertEquals(1, shape.orderBy.size)
        assertEquals("name", shape.orderBy[0].field)
    }
}
