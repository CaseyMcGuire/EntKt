@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.query.QueryFlag
import entkt.runtime.result.EntQueryRejectedException

/**
 * Mutable query state used by the read compiler while it runs interceptors.
 *
 * Caller, framework-structural, and interceptor predicates remain separately
 * attributed so interceptor scopes can expose accurate [QueryShape] and
 * [UntypedQueryShape] views. After every interceptor has run, [build] returns
 * the immutable [StorageQuerySpec] consumed by the storage layer.
 *
 * Application interceptors mutate this state only through [InterceptScope] or
 * [GlobalInterceptScope].
 */
public class QuerySpecBuilder<E : Any> public constructor(
    public val table: String,
    public val entity: kotlin.reflect.KClass<*>,
    callerPredicates: List<Predicate<E>>,
    structuralPredicates: List<Predicate<E>>,
    /**
     * The effective ordering storage will execute. For selected relationships,
     * the read compiler appends primary-key ordering as a stable tie-breaker
     * before running interceptors. Otherwise this equals [callerOrderBy].
     */
    orderBy: List<OrderField<E>>,
    callerLimit: Int?,
    public val offset: Int?,
    flags: Set<QueryFlag>,
    initialAnnotations: Map<String, String> = emptyMap(),
    /**
     * The caller-authored ordering terms only, for authored-order
     * attribution on the shape views ([QueryShape.callerOrderBy] /
     * [UntypedQueryShape.hasCallerOrderBy]). Defaults to [orderBy]
     * for the non-eager call sites where the two are identical.
     */
    callerOrderBy: List<OrderField<E>> = orderBy,
    /**
     * The driver's bind-capacity gate supplied by the read compiler. Invoked
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
     * Database graph storage enables this for native direct-to-many relationship
     * windows, whose parent keys use one structural bind transport.
     */
    structuralSingleBindTransport: Boolean = false,
) {
    // Typed in E: every layer above the
    // driver call stays typed. Predicates enter the builder from
    // call sites that are typed to the matching E (caller
    // `where(Predicate<E>)`, structural builders constructed
    // against the same E, interceptor-added
    // `scope.addPredicate(Predicate<E>)`). Erasure happens only
    // when [build] produces the [StorageQuerySpec] and the
    // storage layer passes its lists via covariance to the
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

    /** Writes or overwrites diagnostic metadata for later interceptors. */
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
     * Semantic snapshot used by drivers.
     *
     * Copying only the outer lists is insufficient: supported predicate
     * operands such as ByteArray are mutable. The stored spec was already
     * detached at chain entry, but a lifecycle callback can run after one
     * driver call and before another reuses the same storage query
     * (for example, deleteMany candidate selection), so the
     * storage query recursively snapshots predicate trees, shaped traversal
     * sources, distance operands, arrays, and standard collection carriers
     * again — the [StorageQuerySpec] is independent of the builder and of
     * anything any callback can still reach.
     */
    internal fun build(): StorageQuerySpec<E> {
        // The stored list is [caller..., structural..., interceptor...]
        // by construction (entry seeds caller then structural;
        // interceptor additions append), which is the positional
        // contract [StorageQuerySpec]'s attribution slices rely on.
        var caller = 0
        var structural = 0
        for (t in predicates) when (t.source) {
            Source.CALLER -> caller++
            Source.STRUCTURAL -> structural++
            Source.INTERCEPTOR -> {}
        }
        return StorageQuerySpec(
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

/**
 * The final immutable description of one database read. Distinct from the
 * mutable [QuerySpecBuilder] so the storage layer receives query values without
 * gaining access to interceptor mutation APIs.
 *
 * Typed in `E`: every layer above the
 * storage call carries the entity scope through. The compiled query's
 * `query.predicates: List<Predicate<E>>` flows into
 * `driver.query(table, query.predicates, ...)` through the
 * `List<out T>` variance — the driver method takes
 * `List<Predicate<*>>` and `List<Predicate<E>>` is assignable to
 * that, so erasure is implicit at the driver call (no `as` cast
 * needed in the caller).
 */
public data class StorageQuerySpec<E : Any> public constructor(
    val table: String,
    /**
     * The complete immutable predicate list, positionally attributed:
     * `[caller..., structural..., interceptor...]`. The first
     * [callerPredicateCount] entries are caller-authored, the next
     * [structuralPredicateCount] are framework structural predicates
     * (by-id leaves, traversal bridges, eager relationship `IN`s),
     * and the remainder are interceptor contributions. [QuerySpecBuilder.build]
     * produces this order from the builder's tagged buckets. The read compiler
     * rewrites edge-predicate trees positionally, so
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
            "StorageQuerySpec attribution counts must be non-negative: " +
                "caller=$callerPredicateCount structural=$structuralPredicateCount"
        }
        require(callerPredicateCount + structuralPredicateCount <= predicates.size) {
            "StorageQuerySpec attribution overflow: caller=$callerPredicateCount + " +
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
 * doesn't corrupt framework-owned relationship queries, and a
 * `rejectIfLimitGreaterThan(10)` doesn't reject every `findById`
 * call just because it has a null effective limit.
 */
internal fun limitOpsApply(operation: ReadOperation): Boolean = when (operation) {
    ReadOperation.ALL,
    ReadOperation.EDGE_TRAVERSAL -> true
    ReadOperation.BY_ID,
    ReadOperation.FIRST,
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
 * re-derives [shape] on every access. A captured [QueryShape] remains the
 * snapshot observed at the time it was read.
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
 * A registered interceptor plus its stable name. The read compiler evaluates
 * registrations in the order supplied by [EntInterceptors].
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
