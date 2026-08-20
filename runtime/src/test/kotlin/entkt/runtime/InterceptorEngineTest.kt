package entkt.runtime
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.QueryContext
import entkt.runtime.query.RegisteredGlobalInterceptor
import entkt.runtime.query.GlobalQueryInterceptor
import entkt.runtime.query.QuerySpecBuilder
import entkt.runtime.query.AbortQueryRejected
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.QueryShape
import entkt.runtime.query.RegisteredInterceptor
import entkt.runtime.query.InterceptorEngine
import entkt.runtime.query.QueryInterceptor

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * coverage: the engine that runs the interceptor chain
 * against a [QuerySpecBuilder], plus the live-vs-snapshot behavior
 * of `scope.shape`, the attribution buckets, and the
 * `scope.reject(...) → AbortQueryRejected` short-circuit.
 *
 * No generated wiring yet — these tests construct builders /
 * contexts manually and exercise the engine directly. * integration tests will pin end-to-end behavior on real generated
 * read terminals.
 */
class InterceptorEngineTest {

    private class Post

    private fun builder(
        caller: List<Predicate<Post>> = emptyList(),
        structural: List<Predicate<Post>> = emptyList(),
        callerLimit: Int? = null,
    ): QuerySpecBuilder<Post> = QuerySpecBuilder(
        table = "posts",
        entity = Post::class,
        callerPredicates = caller,
        structuralPredicates = structural,
        orderBy = emptyList<OrderField<Post>>(),
        callerLimit = callerLimit,
        offset = null,
        flags = emptySet(),
    )

    private fun rootContext(operation: ReadOperation = ReadOperation.ALL): QueryContext =
        QueryContext(
            privacy = PrivacyContext(Viewer.PrivacyBypass("test")),
            operation = operation,
            rootEntity = Post::class,
            currentEntity = Post::class,
            sourceEntity = null,
            edgeName = null,
            path = emptyList(),
            flags = emptySet(),
        )

    // ---- Shape live-vs-snapshot ----

    @Test
    fun `scope-shape sees own addPredicate immediately (live property)`() {
        val pX = Predicate.Leaf<Post>("x", Op.EQ, 1)
        val captured = mutableListOf<Int>()  // counts after each read
        val interceptor = QueryInterceptor<Post> { scope, _ ->
            captured.add(scope.shape.predicates.size)  // 0
            scope.addPredicate(pX)
            captured.add(scope.shape.predicates.size)  // 1
        }
        InterceptorEngine.apply(
            builder = builder(),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("t", interceptor)),
            globalInterceptors = emptyList(),
        )
        assertEquals(listOf(0, 1), captured)
    }

    @Test
    fun `captured shape local does NOT reflect subsequent mutations (data-value snapshot)`() {
        var snapCount = -1
        var liveCount = -1
        val interceptor = QueryInterceptor<Post> { scope, _ ->
            val snap = scope.shape
            scope.addPredicate(Predicate.Leaf<Post>("y", Op.EQ, 2))
            snapCount = snap.predicates.size           // frozen
            liveCount = scope.shape.predicates.size    // fresh read
        }
        InterceptorEngine.apply(
            builder = builder(),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("t", interceptor)),
            globalInterceptors = emptyList(),
        )
        assertEquals(0, snapCount, "captured shape is frozen at capture time")
        assertEquals(1, liveCount, "fresh scope.shape read reflects the mutation")
    }

    @Test
    fun `frozen spec recursively snapshots mutable predicate operands`() {
        val equalityBytes = byteArrayOf(1, 2)
        val memberBytes = byteArrayOf(3, 4)
        val predicate = Predicate.Leaf<Post>("payload", Op.EQ, equalityBytes) and
            Predicate.Leaf("payload", Op.IN, listOf(memberBytes))

        val frozen = InterceptorEngine.apply(
            builder = builder(caller = listOf(predicate)),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = emptyList(),
            globalInterceptors = emptyList(),
        )
        equalityBytes[0] = 9
        memberBytes[0] = 8

        val frozenAnd = frozen.predicates.single() as Predicate.And
        val frozenEquality = (frozenAnd.left as Predicate.Leaf).value as ByteArray
        val frozenMembers = (frozenAnd.right as Predicate.Leaf).value as List<*>
        assertContentEquals(byteArrayOf(1, 2), frozenEquality)
        assertContentEquals(byteArrayOf(3, 4), frozenMembers.single() as ByteArray)
    }

    @Test
    fun `frozen spec detaches mutable Number operands`() {
        val operand = AtomicInteger(7)
        val frozen = InterceptorEngine.apply(
            builder = builder(caller = listOf(Predicate.Leaf<Post>("rank", Op.EQ, operand))),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = emptyList(),
            globalInterceptors = emptyList(),
        )

        operand.set(9)

        val frozenValue = (frozen.predicates.single() as Predicate.Leaf).value
        assertEquals(BigDecimal("7"), frozenValue)
    }

    @Test
    fun `interceptor sees previous interceptors mutations through shape`() {
        val pX = Predicate.Leaf<Post>("x", Op.EQ, 1)
        val pY = Predicate.Leaf<Post>("y", Op.EQ, 2)
        var bSawX = false
        var bSawY = false
        val a = QueryInterceptor<Post> { scope, _ -> scope.addPredicate(pX) }
        val b = QueryInterceptor<Post> { scope, _ ->
            bSawX = scope.shape.predicates.any { it == pX }
            scope.addPredicate(pY)
            bSawY = scope.shape.predicates.any { it == pY }
        }
        InterceptorEngine.apply(
            builder = builder(),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(
                RegisteredInterceptor("a", a),
                RegisteredInterceptor("b", b),
            ),
            globalInterceptors = emptyList(),
        )
        assertTrue(bSawX)
        assertTrue(bSawY)
    }

    // ---- Attribution ----

    @Test
    fun `attribution buckets count caller and interceptor predicates correctly`() {
        var observedCaller = -1
        var observedStructural = -1
        var observedInterceptor = -1
        val a = QueryInterceptor<Post> { scope, _ ->
            scope.addPredicate(Predicate.Leaf<Post>("ax", Op.EQ, 0))
        }
        val b = QueryInterceptor<Post> { scope, _ ->
            // Read attribution AFTER A's contribution is visible.
            observedCaller = scope.shape.callerPredicateCount
            observedStructural = scope.shape.structuralPredicateCount
            observedInterceptor = scope.shape.interceptorPredicateCount
        }
        InterceptorEngine.apply(
            builder = builder(
                caller = listOf(
                    Predicate.Leaf<Post>("c1", Op.EQ, 1),
                    Predicate.Leaf<Post>("c2", Op.EQ, 2),
                ),
                structural = emptyList(),
            ),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(
                RegisteredInterceptor("a", a),
                RegisteredInterceptor("b", b),
            ),
            globalInterceptors = emptyList(),
        )
        assertEquals(2, observedCaller)
        assertEquals(0, observedStructural)
        assertEquals(1, observedInterceptor)
    }

    @Test
    fun `structural predicates land in structural bucket and identity holds`() {
        var observed: QueryShape<Post>? = null
        val interceptor = QueryInterceptor<Post> { scope, _ ->
            observed = scope.shape
        }
        InterceptorEngine.apply(
            builder = builder(
                caller = emptyList(),
                structural = listOf(Predicate.Leaf<Post>("id", Op.EQ, 42)),
            ),
            context = rootContext(operation = ReadOperation.BY_ID),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("t", interceptor)),
            globalInterceptors = emptyList(),
        )
        val shape = observed!!
        assertEquals(0, shape.callerPredicateCount)
        assertEquals(1, shape.structuralPredicateCount)
        assertEquals(0, shape.interceptorPredicateCount)
        assertEquals(1, shape.predicates.size)
        assertFalse(shape.hasCallerPredicates)
    }

    // ---- callerLimit ----

    @Test
    fun `callerLimit preserves the caller-authored limit through interceptor mutations`() {
        var snapshot: QueryShape<Post>? = null
        val defaulter = QueryInterceptor<Post> { scope, _ ->
            scope.setDefaultLimitIfAbsent(100)
        }
        val inspector = QueryInterceptor<Post> { scope, _ ->
            snapshot = scope.shape
        }
        // Case 1: caller set a limit.
        InterceptorEngine.apply(
            builder = builder(callerLimit = 50),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(
                RegisteredInterceptor("d", defaulter),
                RegisteredInterceptor("i", inspector),
            ),
            globalInterceptors = emptyList(),
        )
        assertEquals(50, snapshot!!.callerLimit)
        // setDefaultLimitIfAbsent must NOT clobber a caller-set limit.
        assertEquals(50, snapshot!!.limit)

        // Case 2: caller did not set a limit; default fires.
        snapshot = null
        InterceptorEngine.apply(
            builder = builder(callerLimit = null),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(
                RegisteredInterceptor("d", defaulter),
                RegisteredInterceptor("i", inspector),
            ),
            globalInterceptors = emptyList(),
        )
        assertNull(snapshot!!.callerLimit, "callerLimit stays null even after default fills in")
        assertEquals(100, snapshot!!.limit)
    }

    // ---- Limit mutators ----

    @Test
    fun `requireLimitAtMost only narrows`() {
        var observed: Int? = null
        val cap = QueryInterceptor<Post> { scope, _ -> scope.requireLimitAtMost(30) }
        val tail = QueryInterceptor<Post> { scope, _ -> observed = scope.shape.limit }
        InterceptorEngine.apply(
            builder = builder(callerLimit = 50),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("cap", cap), RegisteredInterceptor("t", tail)),
            globalInterceptors = emptyList(),
        )
        assertEquals(30, observed)
    }

    @Test
    fun `requireLimitAtMost cannot raise a smaller existing limit`() {
        var observed: Int? = null
        val raiser = QueryInterceptor<Post> { scope, _ -> scope.requireLimitAtMost(100) }
        val tail = QueryInterceptor<Post> { scope, _ -> observed = scope.shape.limit }
        InterceptorEngine.apply(
            builder = builder(callerLimit = 10),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("r", raiser), RegisteredInterceptor("t", tail)),
            globalInterceptors = emptyList(),
        )
        assertEquals(10, observed, "10 caller limit must not be raised to 100")
    }

    @Test
    fun `setDefaultLimitIfAbsent is a no-op when a limit is already in place`() {
        var observed: Int? = null
        val def = QueryInterceptor<Post> { scope, _ -> scope.setDefaultLimitIfAbsent(100) }
        val tail = QueryInterceptor<Post> { scope, _ -> observed = scope.shape.limit }
        InterceptorEngine.apply(
            builder = builder(callerLimit = 25),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("d", def), RegisteredInterceptor("t", tail)),
            globalInterceptors = emptyList(),
        )
        assertEquals(25, observed)
    }

    @Test
    fun `limit mutators reject negative arguments`() {
        val cap = QueryInterceptor<Post> { scope, _ -> scope.requireLimitAtMost(-1) }
        val def = QueryInterceptor<Post> { scope, _ -> scope.setDefaultLimitIfAbsent(-1) }
        val rej = QueryInterceptor<Post> { scope, _ -> scope.rejectIfLimitGreaterThan(-1) { "x" } }
        for (interceptor in listOf(cap, def, rej)) {
            assertFailsWith<IllegalArgumentException> {
                InterceptorEngine.apply(
                    builder = builder(),
                    context = rootContext(),
                    entity = "Post",
                    entityInterceptors = listOf(RegisteredInterceptor("t", interceptor)),
                    globalInterceptors = emptyList(),
                )
            }
        }
    }

    @Test
    fun `rejectIfLimitGreaterThan rejects when unbounded and passes when within max`() {
        val rejecter = QueryInterceptor<Post> { scope, _ ->
            scope.rejectIfLimitGreaterThan(50) { "max 50" }
        }
        // Unbounded → reject with code max_limit_exceeded.
        val ex = assertFailsWith<AbortQueryRejected> {
            InterceptorEngine.apply(
                builder = builder(callerLimit = null),
                context = rootContext(),
                entity = "Post",
                entityInterceptors = listOf(RegisteredInterceptor("rej", rejecter)),
                globalInterceptors = emptyList(),
            )
        }
        assertEquals("max_limit_exceeded", ex.rejected.code)
        assertEquals("rej", ex.rejected.interceptor)
        assertEquals("max 50", ex.rejected.reason)

        // Within max → no rejection.
        InterceptorEngine.apply(
            builder = builder(callerLimit = 25),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("rej", rejecter)),
            globalInterceptors = emptyList(),
        )

        // Equal to max → not greater, no rejection.
        InterceptorEngine.apply(
            builder = builder(callerLimit = 50),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("rej", rejecter)),
            globalInterceptors = emptyList(),
        )
    }

    // ---- Annotations ----

    @Test
    fun `addAnnotation writes are visible to the same interceptor and to later ones`() {
        var aSawOwn = ""
        var bSawA = ""
        val a = QueryInterceptor<Post> { scope, _ ->
            scope.addAnnotation("k", "from-a")
            aSawOwn = scope.shape.annotations["k"] ?: "missing"
        }
        val b = QueryInterceptor<Post> { scope, _ ->
            bSawA = scope.shape.annotations["k"] ?: "missing"
        }
        InterceptorEngine.apply(
            builder = builder(),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("a", a), RegisteredInterceptor("b", b)),
            globalInterceptors = emptyList(),
        )
        assertEquals("from-a", aSawOwn)
        assertEquals("from-a", bSawA)
    }

    @Test
    fun `annotation last-writer-wins across interceptors`() {
        val frozen = mutableMapOf<String, String>()
        val a = QueryInterceptor<Post> { scope, _ -> scope.addAnnotation("k", "from-a") }
        val b = QueryInterceptor<Post> { scope, _ ->
            scope.addAnnotation("k", "from-b")
            frozen.putAll(scope.shape.annotations)
        }
        InterceptorEngine.apply(
            builder = builder(),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("a", a), RegisteredInterceptor("b", b)),
            globalInterceptors = emptyList(),
        )
        assertEquals("from-b", frozen["k"])
    }

    // ---- Rejection ----

    @Test
    fun `reject throws AbortQueryRejected with the rejecting interceptors name and provided code`() {
        val rejecter = QueryInterceptor<Post> { scope, _ ->
            scope.reject("nope", code = "broad_aggregate")
        }
        val ex = assertFailsWith<AbortQueryRejected> {
            InterceptorEngine.apply(
                builder = builder(),
                context = rootContext(),
                entity = "Post",
                entityInterceptors = listOf(RegisteredInterceptor("guard", rejecter)),
                globalInterceptors = emptyList(),
            )
        }
        val r = ex.rejected
        assertEquals("Post", r.entityType)
        assertEquals("nope", r.reason)
        assertEquals("broad_aggregate", r.code)
        assertEquals("guard", r.interceptor)
    }

    @Test
    fun `rejection short-circuits the remaining chain`() {
        var bRan = false
        var globalRan = false
        val rejecter = QueryInterceptor<Post> { scope, _ -> scope.reject("nope") }
        val b = QueryInterceptor<Post> { _, _ -> bRan = true }
        val g = GlobalQueryInterceptor { _, _ -> globalRan = true }
        assertFailsWith<AbortQueryRejected> {
            InterceptorEngine.apply(
                builder = builder(),
                context = rootContext(),
                entity = "Post",
                entityInterceptors = listOf(
                    RegisteredInterceptor("rej", rejecter),
                    RegisteredInterceptor("b", b),
                ),
                globalInterceptors = listOf(RegisteredGlobalInterceptor("g", g)),
            )
        }
        assertFalse(bRan, "B must not run after the rejecting interceptor")
        assertFalse(globalRan, "global must not run after the rejecting interceptor")
    }

    // ---- Globals see entity mutations + later globals see earlier globals ----

    @Test
    fun `global interceptors see all prior mutations including earlier globals`() {
        val pX = Predicate.Leaf<Post>("x", Op.EQ, 1)
        var g1ObservedPredicates = -1
        var g2ObservedAnnotations: Map<String, String> = emptyMap()
        val entityI = QueryInterceptor<Post> { scope, _ -> scope.addPredicate(pX) }
        val g1 = GlobalQueryInterceptor { scope, _ ->
            g1ObservedPredicates = scope.shape.predicateCount
            scope.addAnnotation("from-g1", "yes")
        }
        val g2 = GlobalQueryInterceptor { scope, _ ->
            g2ObservedAnnotations = scope.shape.annotations
        }
        InterceptorEngine.apply(
            builder = builder(),
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("e", entityI)),
            globalInterceptors = listOf(
                RegisteredGlobalInterceptor("g1", g1),
                RegisteredGlobalInterceptor("g2", g2),
            ),
        )
        assertEquals(1, g1ObservedPredicates, "g1 should see the entity interceptor's predicate")
        assertEquals("yes", g2ObservedAnnotations["from-g1"], "g2 should see g1's annotation")
    }

    // ---- Per-terminal-call spec isolation (the builder is freshly built each call) ----

    @Test
    fun `freeze returns the final snapshot with all mutations`() {
        val b = builder(
            caller = listOf(Predicate.Leaf<Post>("c", Op.EQ, 1)),
            structural = listOf(Predicate.Leaf<Post>("s", Op.EQ, 2)),
            callerLimit = 100,
        )
        val pX = Predicate.Leaf<Post>("x", Op.EQ, 3)
        val interceptor = QueryInterceptor<Post> { scope, _ ->
            scope.addPredicate(pX)
            scope.requireLimitAtMost(50)
            scope.addAnnotation("k", "v")
        }
        val frozen = InterceptorEngine.apply(
            builder = b,
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(RegisteredInterceptor("t", interceptor)),
            globalInterceptors = emptyList(),
        )
        assertEquals("posts", frozen.table)
        assertEquals(3, frozen.predicates.size)
        assertEquals(50, frozen.limit)
        assertEquals("v", frozen.annotations["k"])
    }

    @Test
    fun `non-reject interceptor exceptions propagate unchanged through engine`() {
        val bug = QueryInterceptor<Post> { _, _ -> error("hook bug") }
        val ex = assertFailsWith<IllegalStateException> {
            InterceptorEngine.apply(
                builder = builder(),
                context = rootContext(),
                entity = "Post",
                entityInterceptors = listOf(RegisteredInterceptor("bug", bug)),
                globalInterceptors = emptyList(),
            )
        }
        assertEquals("hook bug", ex.message)
    }

    @Test
    fun `mutating a returned callerOrderBy list cannot corrupt later interceptors' attribution`() {
        // Two terms matter: a multi-element list is backed by a
        // mutable ArrayList, so a hostile consumer (trivially from
        // Java) can mutate what the shape handed out. Each shape must
        // return a fresh copy, never the builder's stored list.
        val terms = listOf(
            OrderField<Post>("a", OrderDirection.ASC),
            OrderField<Post>("b", OrderDirection.DESC),
        )
        val b = QuerySpecBuilder(
            table = "posts",
            entity = Post::class,
            callerPredicates = emptyList(),
            structuralPredicates = emptyList(),
            orderBy = terms,
            callerLimit = null,
            offset = null,
            flags = emptySet(),
        )
        val hostile = QueryInterceptor<Post> { scope, _ ->
            (scope.shape.callerOrderBy as? ArrayList<*>)?.clear()
        }
        var observed: List<OrderField<Post>>? = null
        var observedHasCallerOrderBy = false
        val observer = QueryInterceptor<Post> { scope, _ ->
            observed = scope.shape.callerOrderBy
            observedHasCallerOrderBy = scope.shape.hasCallerOrderBy
        }
        InterceptorEngine.apply(
            builder = b,
            context = rootContext(),
            entity = "Post",
            entityInterceptors = listOf(
                RegisteredInterceptor("hostile", hostile),
                RegisteredInterceptor("observer", observer),
            ),
            globalInterceptors = emptyList(),
        )
        assertEquals(terms, observed, "authored attribution must survive a consumer's mutation")
        assertTrue(observedHasCallerOrderBy)
    }
}
