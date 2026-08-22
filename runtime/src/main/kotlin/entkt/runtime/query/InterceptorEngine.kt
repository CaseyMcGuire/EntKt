@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query
import entkt.runtime.result.EntQueryRejectedException

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.query.QueryFlag
import entkt.query.TraversalSourceShape
import java.lang.reflect.Array as ReflectArray
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Mutable per-terminal-call query spec the interceptor engine
 * builds up. Tracks predicate attribution (caller / structural /
 * interceptor) in tagged buckets so the spec can re-derive
 * [QueryShape] / [UntypedQueryShape] views on demand.
 *
 * Generated wrapper code seeds the builder with caller-authored
 * state from the query builder (`predicates` / `orderBy` /
 * `queryLimit` / `queryOffset` / `flags`) and any structural
 * predicates the operation requires (`id = ?` on by-id, etc.),
 * then runs the interceptor chain. After the chain completes,
 * the spec is frozen and handed to the driver.
 *
 * Not exposed to application interceptors directly — they see
 * [InterceptScope] / [GlobalInterceptScope] views. Framework-
 * owned interceptors may operate on this type via a future
 * package-private extension; that path isn't part of V1.
 */
public class QuerySpecBuilder<E : Any> public constructor(
    public val table: String,
    public val entity: kotlin.reflect.KClass<*>,
    callerPredicates: List<Predicate<E>>,
    structuralPredicates: List<Predicate<E>>,
    /**
     * The *effective* ordering storage will execute. On eager-load
     * subqueries generated code appends the framework's primary-key
     * ascending term before any interceptor runs; everywhere else
     * this equals [callerOrderBy].
     */
    orderBy: List<OrderField<E>>,
    callerLimit: Int?,
    public val offset: Int?,
    flags: Set<QueryFlag>,
    /**
     * Annotations to seed the builder with before any interceptor
     * runs. Generated code populates this from the source step's
     * `FrozenQuerySpec.annotations` on traversal terminals, so a
     * source-step `scope.addAnnotation(...)` is visible to interceptors
     * on the final step. Interceptors on this step can overwrite these
     * via `scope.addAnnotation` (last-writer-wins).
     * Empty on root reads.
     */
    initialAnnotations: Map<String, String> = emptyMap(),
    /**
     * The caller-authored ordering terms only, for authored-order
     * attribution on the shape views ([QueryShape.callerOrderBy] /
     * [UntypedQueryShape.hasCallerOrderBy]). Defaults to [orderBy]
     * for the non-eager call sites where the two are identical.
     */
    callerOrderBy: List<OrderField<E>> = orderBy,
    /**
     * The driver's bind-capacity gate — generated code passes
     * `{ driver.requireBindCapacity(it, Entity.TABLE) }`. Invoked
     * with the running conservative minimum bind count (summed O(1)
     * `IN`-operand sizes via
     * [entkt.runtime.driver.minimumBindParameters]) immediately
     * BEFORE every semantic snapshot: once at construction for the
     * caller and structural predicates, and again for each
     * [addInterceptorPredicate]. An over-budget operand — whoever
     * contributed it — is therefore rejected without ever being
     * iterated or copied. Defaults to a no-op for direct builder
     * users with no driver in scope.
     */
    private val requireBindCapacity: (Long) -> Unit = {},
    /**
     * True when the driver lowers this call's structural predicates
     * through a single-bind relationship transport (a typed-array /
     * table-valued parent-key parameter) instead of one scalar bind
     * per operand. The running bind minimum then charges one
     * parameter per structural predicate rather than its O(1)
     * `IN`-operand size, so a parent set larger than the driver's
     * scalar bind limit is not falsely rejected. Caller and
     * interceptor predicates always charge their scalar sizes —
     * they bind normally regardless of the relationship transport.
     * Generated code passes the driver's direct to-many window
     * capability here for eager direct to-many steps only.
     */
    structuralSingleBindTransport: Boolean = false,
) {
    // Typed in E: every layer above the
    // driver call stays typed. Predicates enter the builder from
    // call sites that are typed to the matching E (caller
    // `where(Predicate<E>)`, structural builders constructed
    // against the same E, interceptor-added
    // `scope.addPredicate(Predicate<E>)`). Erasure happens only
    // when `freeze()` produces the `FrozenQuerySpec<E>` and the
    // generated code passes its lists via covariance to the
    // driver's `List<Predicate<*>>` parameters.
    //
    // Everything is stored as a SEMANTIC SNAPSHOT taken at chain
    // entry (and at [addInterceptorPredicate] time for interceptor
    // contributions): a mutable operand — an IN list, a ByteArray —
    // that the caller or an interceptor retains a reference to
    // cannot change what this spec means after it entered. The
    // shape views hand out detached snapshots too, so the only
    // sanctioned mutations are the scope's own reduce-or-reject
    // operations.
    // Running conservative minimum of the bind parameters the stored
    // predicates will need. Checked through [requireBindCapacity]
    // before the snapshots below run (initializers execute in
    // declaration order), and re-checked before each interceptor
    // addition's snapshot.
    private var minimumBoundParameters: Long =
        entkt.runtime.driver.minimumBindParameters(callerPredicates) +
            if (structuralSingleBindTransport) {
                structuralPredicates.size.toLong()
            } else {
                entkt.runtime.driver.minimumBindParameters(structuralPredicates)
            }

    init {
        requireBindCapacity(minimumBoundParameters)
    }

    private val predicates: MutableList<Tagged<E>> = mutableListOf<Tagged<E>>().apply {
        addAll(callerPredicates.map { Tagged(it.semanticSnapshot(), Source.CALLER) })
        addAll(structuralPredicates.map { Tagged(it.semanticSnapshot(), Source.STRUCTURAL) })
    }
    private val orderByList: MutableList<OrderField<E>> = orderBy.mapTo(mutableListOf()) { it.semanticSnapshot() }
    private val callerOrderByList: List<OrderField<E>> = callerOrderBy.map { it.semanticSnapshot() }
    private var currentLimit: Int? = callerLimit
    public val callerLimit: Int? = callerLimit

    /** Entry snapshot, like every other constructor input. */
    public val flags: Set<QueryFlag> = flags.toSet()
    private val annotationsMap: MutableMap<String, String> = LinkedHashMap<String, String>().apply {
        putAll(initialAnnotations)
    }

    /**
     * Appends an interceptor-tagged predicate. The running bind
     * budget is enforced BEFORE the snapshot, so an interceptor
     * contributing an over-budget `IN` operand fails fast without the
     * operand ever being iterated or copied. Snapshotted at add time:
     * an interceptor that retains its predicate's mutable operand
     * cannot change the spec after contributing it.
     */
    internal fun addInterceptorPredicate(predicate: Predicate<E>) {
        val candidate = minimumBoundParameters +
            entkt.runtime.driver.minimumBindParameters(listOf(predicate))
        requireBindCapacity(candidate)
        minimumBoundParameters = candidate
        predicates.add(Tagged(predicate.semanticSnapshot(), Source.INTERCEPTOR))
    }

    /** Clamps the effective limit to at most [max]; never raises. */
    internal fun clampLimit(max: Int) {
        require(max >= 0) { "limit clamp must be non-negative; was $max" }
        val cur = currentLimit
        currentLimit = if (cur == null) max else minOf(cur, max)
    }

    /** Sets the limit only when no limit is in place. */
    internal fun setDefaultLimit(default: Int) {
        require(default >= 0) { "default limit must be non-negative; was $default" }
        if (currentLimit == null) currentLimit = default
    }

    /** Writes (or overwrites) an annotation. */
    internal fun putAnnotation(key: String, value: String) {
        annotationsMap[key] = value
    }

    internal val effectiveLimit: Int? get() = currentLimit

    // ---- Shape projections (recomputed on each call — live, not snapshot) ----

    internal fun typedShape(): QueryShape<E> {
        // No cast needed — storage is typed in E already, the builder
        // and its predicates / orderBy lists all carry the same E.
        var caller = 0; var structural = 0; var interceptor = 0
        for (t in predicates) when (t.source) {
            Source.CALLER -> caller++
            Source.STRUCTURAL -> structural++
            Source.INTERCEPTOR -> interceptor++
        }
        // Detached snapshots per shape, for every operand-bearing
        // field: nothing the stored spec owns may be reachable
        // through a returned shape, or one consumer's mutation
        // (possible from Java, or via an unchecked cast) would
        // corrupt what later interceptors and the driver observe.
        return QueryShape(
            table = table,
            predicates = predicates.map { it.predicate.semanticSnapshot() },
            orderBy = orderByList.map { it.semanticSnapshot() },
            callerOrderBy = callerOrderByList.map { it.semanticSnapshot() },
            limit = currentLimit,
            callerLimit = callerLimit,
            offset = offset,
            flags = flags.toSet(),
            annotations = annotationsMap.toMap(),
            callerPredicateCount = caller,
            structuralPredicateCount = structural,
            interceptorPredicateCount = interceptor,
        )
    }

    internal fun untypedShape(): UntypedQueryShape {
        var caller = 0; var structural = 0; var interceptor = 0
        for (t in predicates) when (t.source) {
            Source.CALLER -> caller++
            Source.STRUCTURAL -> structural++
            Source.INTERCEPTOR -> interceptor++
        }
        return UntypedQueryShape(
            table = table,
            entity = entity,
            predicateCount = predicates.size,
            callerPredicateCount = caller,
            structuralPredicateCount = structural,
            interceptorPredicateCount = interceptor,
            limit = currentLimit,
            callerLimit = callerLimit,
            offset = offset,
            hasOrderBy = orderByList.isNotEmpty(),
            hasCallerOrderBy = callerOrderByList.isNotEmpty(),
            annotations = annotationsMap.toMap(),
        )
    }

    /**
     * Semantic snapshot used by drivers and later lifecycle steps.
     *
     * Copying only the outer lists is insufficient: supported predicate
     * operands such as ByteArray are mutable. The stored spec was already
     * detached at chain entry, but a lifecycle callback can run after one
     * driver call and before another reuses the same frozen spec
     * (deleteMany candidate selection is the important example), so the
     * frozen value recursively snapshots predicate trees, shaped traversal
     * sources, distance operands, arrays, and standard collection carriers
     * again — the FrozenQuerySpec is independent of the builder and of
     * anything any callback can still reach.
     */
    internal fun freeze(): FrozenQuerySpec<E> {
        // The stored list is [caller..., structural..., interceptor...]
        // by construction (entry seeds caller then structural;
        // interceptor additions append), which is the positional
        // contract FrozenQuerySpec's attribution slices rely on.
        var caller = 0
        var structural = 0
        for (t in predicates) when (t.source) {
            Source.CALLER -> caller++
            Source.STRUCTURAL -> structural++
            Source.INTERCEPTOR -> {}
        }
        return FrozenQuerySpec(
            table = table,
            predicates = predicates.map { it.predicate.semanticSnapshot() },
            orderBy = orderByList.map { it.semanticSnapshot() },
            limit = currentLimit,
            offset = offset,
            flags = flags.toSet(),
            annotations = annotationsMap.toMap(),
            callerPredicateCount = caller,
            structuralPredicateCount = structural,
        )
    }

    private enum class Source { CALLER, STRUCTURAL, INTERCEPTOR }
    private data class Tagged<E : Any>(val predicate: Predicate<E>, val source: Source)
}

@Suppress("UNCHECKED_CAST")
private fun <E : Any> Predicate<E>.semanticSnapshot(): Predicate<E> = when (this) {
    is Predicate.Leaf -> copy(value = value.semanticSnapshot())
    is Predicate.And -> Predicate.And(left.semanticSnapshot(), right.semanticSnapshot())
    is Predicate.Or -> Predicate.Or(left.semanticSnapshot(), right.semanticSnapshot())
    is Predicate.HasEdge -> this
    is Predicate.HasEdgeWith<*, *> -> {
        val typed = this as Predicate.HasEdgeWith<E, Any>
        Predicate.HasEdgeWith(typed.edge, typed.inner.semanticSnapshot())
    }
    is Predicate.HasM2MEdgeFrom<*, *> -> {
        val typed = this as Predicate.HasM2MEdgeFrom<E, Any>
        Predicate.HasM2MEdgeFrom(
            typed.sourceTable,
            typed.edgeName,
            typed.sourceFilter?.semanticSnapshot(),
        )
    }
    is Predicate.HasEdgeFromShape<*, *> -> {
        val typed = this as Predicate.HasEdgeFromShape<E, Any>
        Predicate.HasEdgeFromShape(typed.edge, typed.source.semanticSnapshot())
    }
    is Predicate.HasM2MEdgeFromShape<*, *> -> {
        val typed = this as Predicate.HasM2MEdgeFromShape<E, Any>
        Predicate.HasM2MEdgeFromShape(typed.edgeName, typed.source.semanticSnapshot())
    }
}

private fun <E : Any> TraversalSourceShape<E>.semanticSnapshot(): TraversalSourceShape<E> = copy(
    predicates = predicates.map { it.semanticSnapshot() },
    orderBy = orderBy.map { it.semanticSnapshot() },
    flags = flags.toSet(),
)

private fun <E : Any> OrderField<E>.semanticSnapshot(): OrderField<E> {
    val currentDistance = distance
    return copy(
        distance = currentDistance?.copy(
            operand = currentDistance.operand.semanticSnapshot() ?: currentDistance.operand,
        ),
    )
}

/** Copy the mutable value carriers accepted by the query DSL. */
private fun Any?.semanticSnapshot(): Any? = when (this) {
    null -> null
    is ByteArray -> copyOf()
    is ShortArray -> copyOf()
    is IntArray -> copyOf()
    is LongArray -> copyOf()
    is FloatArray -> copyOf()
    is DoubleArray -> copyOf()
    is CharArray -> copyOf()
    is BooleanArray -> copyOf()
    is Array<*> -> {
        val snapshot = ReflectArray.newInstance(javaClass.componentType, size)
        for (index in indices) ReflectArray.set(snapshot, index, this[index].semanticSnapshot())
        snapshot
    }
    is List<*> -> map { it.semanticSnapshot() }
    is Set<*> -> mapTo(linkedSetOf()) { it.semanticSnapshot() }
    is Collection<*> -> map { it.semanticSnapshot() }
    is Map<*, *> -> entries.associateTo(linkedMapOf()) {
        it.key.semanticSnapshot() to it.value.semanticSnapshot()
    }
    is Pair<*, *> -> first.semanticSnapshot() to second.semanticSnapshot()
    is Triple<*, *, *> -> Triple(first.semanticSnapshot(), second.semanticSnapshot(), third.semanticSnapshot())
    // The primitive wrappers plus BigInteger/BigDecimal are immutable. Raw
    // low-level predicates may also carry mutable Number implementations
    // (AtomicInteger/AtomicLong or an application subtype); freeze their
    // current numeric text into an immutable BigDecimal rather than retaining
    // an alias that can change between candidate selection and a later write.
    is Byte, is Short, is Int, is Long, is Float, is Double, is BigInteger, is BigDecimal -> this
    is Number -> try {
        BigDecimal(toString())
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException(
            "Cannot freeze mutable predicate Number ${this::class.qualifiedName}: '$this' is not a stable numeric value",
            e,
        )
    }
    else -> this
}

/**
 * Result of a deferred traversal-source step. Produced by the
 * lambda that generated `queryX()` methods stash on the target
 * query: at terminal time the target's `runReadInterceptors`
 * invokes the lambda, runs the source entity's interceptor chain
 * with operation = `EDGE_TRAVERSAL`, and embeds the post-
 * interceptor source shape — predicates, order, limit, offset —
 * into [bridge] (the `HasEdgeFromShape` / `HasM2MEdgeFromShape`
 * predicate that constrains the target). [annotations] carry
 * forward any
 * `scope.addAnnotation(...)` contributions made by source-step
 * interceptors so they remain visible to the final interceptor step.
 *
 * The deferred-invocation design is what lets canonical terminals
 * capture traversal-source rejections — if the source's
 * `runReadInterceptors` throws `EntQueryRejectedException`, it
 * propagates out of this lambda and into the terminal's capture
 * boundary, which stores it in `ReadResult.Failed`.
 */
public data class TraversalSourceResult<E : Any> public constructor(
    val bridge: Predicate<E>,
    val annotations: Map<String, String>,
)

/**
 * The final immutable spec the driver receives. Distinct from the
 * mutable [QuerySpecBuilder] so the framework can hand it across a
 * module boundary without exposing the builder API.
 *
 * Typed in `E`: every layer above the
 * driver call carries the entity scope through. Generated code's
 * `frozen.predicates: List<Predicate<E>>` flows into
 * `driver.query(table, frozen.predicates, ...)` through the
 * `List<out T>` variance — the driver method takes
 * `List<Predicate<*>>` and `List<Predicate<E>>` is assignable to
 * that, so erasure is implicit at the driver call (no `as` cast
 * needed in the caller).
 */
public data class FrozenQuerySpec<E : Any> public constructor(
    val table: String,
    /**
     * The complete frozen predicate list, positionally attributed:
     * `[caller..., structural..., interceptor...]`. The first
     * [callerPredicateCount] entries are caller-authored, the next
     * [structuralPredicateCount] are framework structural predicates
     * (by-id leaves, traversal bridges, eager relationship `IN`s),
     * and the remainder are interceptor contributions. [freeze]
     * produces this order from the builder's tagged buckets, and the
     * generated edge-predicate walker rewrites entries in place, so
     * the slices stay valid on a positionally-mapped `copy`.
     */
    val predicates: List<Predicate<E>>,
    val orderBy: List<OrderField<E>>,
    val limit: Int?,
    val offset: Int?,
    val flags: Set<QueryFlag>,
    val annotations: Map<String, String>,
    /** Count of caller-authored predicates at the head of [predicates]. */
    val callerPredicateCount: Int = predicates.size,
    /** Count of framework structural predicates after the caller slice. */
    val structuralPredicateCount: Int = 0,
) {
    init {
        require(callerPredicateCount >= 0 && structuralPredicateCount >= 0) {
            "FrozenQuerySpec attribution counts must be non-negative: " +
                "caller=$callerPredicateCount structural=$structuralPredicateCount"
        }
        require(callerPredicateCount + structuralPredicateCount <= predicates.size) {
            "FrozenQuerySpec attribution overflow: caller=$callerPredicateCount + " +
                "structural=$structuralPredicateCount > predicates.size=${predicates.size}"
        }
    }

    /** The framework structural slice of [predicates]. */
    val structuralPredicates: List<Predicate<E>>
        get() = predicates.subList(
            callerPredicateCount,
            callerPredicateCount + structuralPredicateCount,
        )

    /**
     * [predicates] minus the structural slice — the caller and
     * interceptor predicates, in stored order. The native direct
     * to-many path hands these to the driver as the remaining target
     * predicates while the driver lowers the separately-attributed
     * relationship constraint itself.
     */
    val nonStructuralPredicates: List<Predicate<E>>
        get() = predicates.subList(0, callerPredicateCount) +
            predicates.subList(callerPredicateCount + structuralPredicateCount, predicates.size)
}

/**
 * Whether limit operations have meaningful effect on the given
 * [ReadOperation]. Honored for `ALL` and `EDGE_TRAVERSAL`; every
 * other shape silent-no-ops them.
 *
 * Rationale per shape:
 * - `EDGE_TRAVERSAL`: the source step's post-interceptor shape —
 *   predicates, order, limit, offset — embeds in a shaped bridge
 *   (`HasEdgeFromShape` / `HasM2MEdgeFromShape`) that drivers lower
 *   into a source-id subquery, so an interceptor-set or -clamped
 *   limit narrows which source rows are traversed exactly as it
 *   narrows an `ALL` read.
 * - `BY_ID`, `FIRST`: intrinsic single-row result shape; clamping
 *   has no observable effect (limit is hard-coded to 1 / pk-lookup).
 * - `RAW_COUNT`, `RAW_EXISTS`: aggregates / existence checks; the
 *   row limit either has no meaning (aggregates that don't
 *   materialize rows) or would silently corrupt the answer (counting
 *   "first N scanned rows" rather than all matching rows).
 * - `EAGER_LOAD`: per-parent-vs-batched semantics are ambiguous;
 *   the runtime fetches with null limit and paginates per-group
 *   in Kotlin.
 * - `EAGER_JUNCTION`: relationship discovery must be complete — a
 *   limit would silently drop associations.
 * - `EDGE_PREDICATE`: compiles to EXISTS; row count has no
 *   meaning inside an EXISTS subquery.
 *
 * Used by [InterceptScopeImpl] / [GlobalInterceptScopeImpl] to
 * gate `requireLimitAtMost` / `setDefaultLimitIfAbsent` /
 * `rejectIfLimitGreaterThan` — so a `global { requireLimitAtMost(100) }`
 * doesn't corrupt `rawCount` (which would otherwise count the
 * first 100 scanned rows rather than all matching rows), and a
 * `rejectIfLimitGreaterThan(10)` doesn't reject every `findById` /
 * `rawCount` / `rawExists` call just because they have null
 * effective limits.
 */
internal fun limitOpsApply(operation: ReadOperation): Boolean = when (operation) {
    ReadOperation.ALL,
    ReadOperation.EDGE_TRAVERSAL -> true
    ReadOperation.BY_ID,
    ReadOperation.FIRST,
    ReadOperation.RAW_COUNT,
    ReadOperation.RAW_EXISTS,
    // RAW_AGGREGATE: aggregates ignore limit/offset, so a MaxLimitInterceptor
    // must never silently cap them (same reasoning as RAW_COUNT above).
    ReadOperation.RAW_AGGREGATE,
    ReadOperation.EAGER_LOAD,
    ReadOperation.EAGER_JUNCTION,
    ReadOperation.EDGE_PREDICATE,
    // DELETE_CANDIDATES: silent no-op so a MaxLimitInterceptor
    // doesn't accidentally turn deleteMany(...) into "delete the
    // first N matching rows" without the caller knowing — that's
    // a footgun a limit clamp shouldn't be allowed to set up.
    // reject() still fires, so a max-limit interceptor that wants
    // to gate broad deletes uses rejectIfLimitGreaterThan in the
    // normal way.
    ReadOperation.DELETE_CANDIDATES -> false
}

/**
 * Concrete [InterceptScope] implementation backed by a
 * [QuerySpecBuilder]. Forwards mutator calls to the builder and
 * re-derives [shape] on every access (live, not snapshot — captured
 * locals freeze a [QueryShape] data value at capture time).
 *
 * Limit mutators (`requireLimitAtMost` / `setDefaultLimitIfAbsent` /
 * `rejectIfLimitGreaterThan`) silently no-op when [readOperation]
 * is a shape on which limit operations have no meaning — see
 * [limitOpsApply] for the per-operation table. Those unsupported
 * shapes are silent no-ops, as documented on the [InterceptScope]
 * mutator KDocs.
 */
internal class InterceptScopeImpl<E : Any>(
    private val builder: QuerySpecBuilder<E>,
    private val rejectingInterceptor: String,
    private val entity: String,
    private val readOperation: ReadOperation,
) : InterceptScope<E> {
    override val shape: QueryShape<E> get() = builder.typedShape()

    override fun addPredicate(predicate: Predicate<E>) {
        builder.addInterceptorPredicate(predicate)
    }

    override fun requireLimitAtMost(max: Int) {
        require(max >= 0) { "requireLimitAtMost: max must be non-negative; was $max" }
        if (!limitOpsApply(readOperation)) return
        builder.clampLimit(max)
    }

    override fun setDefaultLimitIfAbsent(default: Int) {
        require(default >= 0) { "setDefaultLimitIfAbsent: default must be non-negative; was $default" }
        if (!limitOpsApply(readOperation)) return
        builder.setDefaultLimit(default)
    }

    override fun rejectIfLimitGreaterThan(max: Int, reason: () -> String) {
        require(max >= 0) { "rejectIfLimitGreaterThan: max must be non-negative; was $max" }
        if (!limitOpsApply(readOperation)) return
        val effective = builder.effectiveLimit
        if (effective == null || effective > max) {
            reject(reason(), code = "max_limit_exceeded")
        }
    }

    override fun addAnnotation(key: String, value: String) {
        builder.putAnnotation(key, value)
    }

    override fun reject(reason: String, code: String?): Nothing {
        throw AbortQueryRejected(
            EntQueryRejectedException(
                entityType = entity,
                reason = reason,
                code = code,
                interceptor = rejectingInterceptor,
            )
        )
    }
}

/**
 * Concrete [GlobalInterceptScope] mirror of [InterceptScopeImpl].
 *
 * Holds the builder as `QuerySpecBuilder<*>` — globals are entity-
 * agnostic, so the underlying `E` is irrelevant here. The shape
 * surfaced to global interceptors is the erased [UntypedQueryShape]
 * via `builder.untypedShape()`. Limit / annotate / reject mutations
 * don't reference E either, so the wildcard is sound.
 */
internal class GlobalInterceptScopeImpl(
    private val builder: QuerySpecBuilder<*>,
    private val rejectingInterceptor: String,
    private val entity: String,
    private val readOperation: ReadOperation,
) : GlobalInterceptScope {
    override val shape: UntypedQueryShape get() = builder.untypedShape()

    override fun requireLimitAtMost(max: Int) {
        require(max >= 0) { "requireLimitAtMost: max must be non-negative; was $max" }
        if (!limitOpsApply(readOperation)) return
        builder.clampLimit(max)
    }

    override fun setDefaultLimitIfAbsent(default: Int) {
        require(default >= 0) { "setDefaultLimitIfAbsent: default must be non-negative; was $default" }
        if (!limitOpsApply(readOperation)) return
        builder.setDefaultLimit(default)
    }

    override fun rejectIfLimitGreaterThan(max: Int, reason: () -> String) {
        require(max >= 0) { "rejectIfLimitGreaterThan: max must be non-negative; was $max" }
        if (!limitOpsApply(readOperation)) return
        val effective = builder.effectiveLimit
        if (effective == null || effective > max) {
            reject(reason(), code = "max_limit_exceeded")
        }
    }

    override fun addAnnotation(key: String, value: String) {
        builder.putAnnotation(key, value)
    }

    override fun reject(reason: String, code: String?): Nothing {
        throw AbortQueryRejected(
            EntQueryRejectedException(
                entityType = entity,
                reason = reason,
                code = code,
                interceptor = rejectingInterceptor,
            )
        )
    }
}

/**
 * A registered interceptor plus its stable name. The engine
 * collects these in registration order from [EntInterceptors]
 * config.
 */
@ConsistentCopyVisibility
public data class RegisteredInterceptor<E : Any> internal constructor(
    val name: String,
    val interceptor: QueryInterceptor<E>,
)

@ConsistentCopyVisibility
public data class RegisteredGlobalInterceptor internal constructor(
    val name: String,
    val interceptor: GlobalQueryInterceptor,
)

/**
 * The engine that runs the interceptor chain for a single
 * terminal-call. Generated wrapper code instantiates one per
 * terminal, feeds the caller-authored + structural state, then
 * calls [apply] with the per-entity + global interceptors for the
 * root entity.
 *
 * Returns the [FrozenQuerySpec] the driver should execute, or
 * throws [AbortQueryRejected] if any interceptor in the chain
 * rejects (caught by the wrapper).
 */
public object InterceptorEngine {
    /**
     * Cap on edge-predicate walker recursion. A pathological cycle
     * in target interceptors (e.g. Post adds Post.author.has() and
     * User adds User.posts.has() — each interceptor's addPredicate
     * triggers the walker on the other entity, recursing
     * indefinitely) trips this limit and surfaces as a clear
     * IllegalStateException rather than a StackOverflowError.
     *
     * Each walker level appends one [EdgeStep] to `parentPath`, so
     * this is effectively the maximum chained edge-predicate depth
     * in a single terminal call. 32 is generous for legitimate
     * traversal trees (which rarely go beyond 3-4 hops) while
     * tripping immediately on cycles. Internally referenced by
     * generated `runEdgePredicateInterceptors`.
     */
    public const val EDGE_PREDICATE_MAX_DEPTH: Int = 32

    /**
     * Run the interceptor chain on [builder] with [context] for the
     * given per-entity ([entityInterceptors]) and global
     * ([globalInterceptors]) lists. Returns the frozen spec; throws
     * [AbortQueryRejected] on `scope.reject`. Non-reject interceptor
     * exceptions propagate unchanged.
     *
     * Order: per-entity interceptors first (in registration order),
     * then globals (in registration order). Each interceptor sees a
     * live shape reflecting all prior interceptors' mutations.
     */
    public fun <E : Any> apply(
        builder: QuerySpecBuilder<E>,
        context: QueryContext,
        entity: String,
        entityInterceptors: List<RegisteredInterceptor<E>>,
        globalInterceptors: List<RegisteredGlobalInterceptor>,
    ): FrozenQuerySpec<E> {
        for (registered in entityInterceptors) {
            val scope = InterceptScopeImpl<E>(builder, registered.name, entity, context.operation)
            registered.interceptor.intercept(scope, context)
        }
        for (registered in globalInterceptors) {
            val scope = GlobalInterceptScopeImpl(builder, registered.name, entity, context.operation)
            registered.interceptor.intercept(scope, context)
        }
        return builder.freeze()
    }
}
