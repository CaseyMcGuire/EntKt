package entkt.runtime

import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for [ExcludeDeleted]. Exercises the interceptor
 * directly through [InterceptorEngine.apply] — the soft-delete
 * convention's end-to-end behavior on real reads is covered by the
 * Postgres-backed integration test elsewhere.
 */
class ExcludeDeletedTest {

    private class Post

    private fun builder(): QuerySpecBuilder<Post> = QuerySpecBuilder(
        table = "posts",
        entity = Post::class,
        callerPredicates = emptyList(),
        structuralPredicates = emptyList(),
        orderBy = emptyList<OrderField<Post>>(),
        callerLimit = null,
        offset = null,
        flags = emptySet(),
    )

    private fun rootContext(): QueryContext = QueryContext(
        privacy = PrivacyContext(Viewer.PrivacyBypass("test")),
        operation = ReadOperation.ALL,
        rootEntity = Post::class,
        currentEntity = Post::class,
        sourceEntity = null,
        edgeName = null,
        path = emptyList(),
        flags = emptySet(),
    )

    private fun runWith(interceptor: ExcludeDeleted<Post>): FrozenQuerySpec<Post> =
        InterceptorEngine.apply(
            builder = builder(),
            context = rootContext(),
            entity = "Post",
            entOperation = EntOperation.QUERY,
            entityInterceptors = listOf(RegisteredInterceptor("soft-delete", interceptor)),
            globalInterceptors = emptyList(),
        )

    @Test
    fun `default ExcludeDeleted adds IS_NULL predicate on deleted_at`() {
        val frozen = runWith(ExcludeDeleted())

        // Caller and structural lists were empty, so the only
        // predicate in the spec is the one the interceptor added.
        assertEquals(1, frozen.predicates.size)
        val added = frozen.predicates.single() as Predicate.Leaf<Post>
        assertEquals("deleted_at", added.field)
        assertEquals(Op.IS_NULL, added.op)
        assertEquals(null, added.value)
    }

    @Test
    fun `custom column name flows through to the predicate`() {
        val frozen = runWith(ExcludeDeleted(column = "archived_at"))

        val added = frozen.predicates.single() as Predicate.Leaf<Post>
        assertEquals("archived_at", added.field)
        assertEquals(Op.IS_NULL, added.op)
    }

    @Test
    fun `the interceptor's predicate is ANDed with existing caller predicates`() {
        // Seed the builder with a caller predicate; the interceptor
        // should add its own without replacing or removing it. The
        // The "narrow-only" safety property is enforced by the
        // engine, but we double-check that ExcludeDeleted doesn't
        // accidentally violate it by inspecting the final predicate
        // set after interception.
        val callerPred = Predicate.Leaf<Post>("title", Op.EQ, "hello")
        val frozen = InterceptorEngine.apply(
            builder = QuerySpecBuilder(
                table = "posts",
                entity = Post::class,
                callerPredicates = listOf(callerPred),
                structuralPredicates = emptyList(),
                orderBy = emptyList<OrderField<Post>>(),
                callerLimit = null,
                offset = null,
                flags = emptySet(),
            ),
            context = rootContext(),
            entity = "Post",
            entOperation = EntOperation.QUERY,
            entityInterceptors = listOf(RegisteredInterceptor("soft-delete", ExcludeDeleted())),
            globalInterceptors = emptyList(),
        )

        val preds = frozen.predicates
        assertEquals(2, preds.size, "caller predicate must survive; interceptor adds one")
        assertTrue(preds.contains(callerPred), "caller's title=hello must be preserved")
        assertTrue(
            preds.any { it is Predicate.Leaf && it.field == "deleted_at" && it.op == Op.IS_NULL },
            "interceptor's deleted_at IS NULL must be added",
        )
    }
}
