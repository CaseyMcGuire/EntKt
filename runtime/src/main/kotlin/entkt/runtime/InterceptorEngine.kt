package entkt.runtime

import entkt.query.OrderField
import entkt.query.Predicate

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
public class QuerySpecBuilder public constructor(
    public val table: String,
    public val entity: kotlin.reflect.KClass<*>,
    callerPredicates: List<Predicate>,
    structuralPredicates: List<Predicate>,
    orderBy: List<OrderField>,
    callerLimit: Int?,
    public val offset: Int?,
    public val flags: Set<QueryFlag>,
) {
    private val predicates: MutableList<Tagged> = mutableListOf<Tagged>().apply {
        addAll(callerPredicates.map { Tagged(it, Source.CALLER) })
        addAll(structuralPredicates.map { Tagged(it, Source.STRUCTURAL) })
    }
    private val orderByList: MutableList<OrderField> = orderBy.toMutableList()
    private var currentLimit: Int? = callerLimit
    public val callerLimit: Int? = callerLimit
    private val annotationsMap: MutableMap<String, String> = LinkedHashMap()

    /** Appends an interceptor-tagged predicate. */
    internal fun addInterceptorPredicate(predicate: Predicate) {
        predicates.add(Tagged(predicate, Source.INTERCEPTOR))
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

    internal fun <E : Any> typedShape(): QueryShape<E> {
        val allPredicates = predicates.map { it.predicate }
        var caller = 0; var structural = 0; var interceptor = 0
        for (t in predicates) when (t.source) {
            Source.CALLER -> caller++
            Source.STRUCTURAL -> structural++
            Source.INTERCEPTOR -> interceptor++
        }
        return QueryShape(
            table = table,
            predicates = allPredicates,
            orderBy = orderByList.toList(),
            limit = currentLimit,
            callerLimit = callerLimit,
            offset = offset,
            flags = flags,
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
            annotations = annotationsMap.toMap(),
        )
    }

    /** Snapshot used by drivers / explain. */
    internal fun freeze(): FrozenQuerySpec = FrozenQuerySpec(
        table = table,
        predicates = predicates.map { it.predicate },
        orderBy = orderByList.toList(),
        limit = currentLimit,
        offset = offset,
        flags = flags,
        annotations = annotationsMap.toMap(),
    )

    private enum class Source { CALLER, STRUCTURAL, INTERCEPTOR }
    private data class Tagged(val predicate: Predicate, val source: Source)
}

/**
 * The final immutable spec the driver receives. Distinct from the
 * mutable [QuerySpecBuilder] so the framework can hand it across a
 * module boundary without exposing the builder API.
 */
public data class FrozenQuerySpec public constructor(
    val table: String,
    val predicates: List<Predicate>,
    val orderBy: List<OrderField>,
    val limit: Int?,
    val offset: Int?,
    val flags: Set<QueryFlag>,
    val annotations: Map<String, String>,
)

/**
 * Whether limit operations have meaningful effect on the given
 * [ReadOperation]. V1 honors limit operations only for `ALL` —
 * every other shape silent-no-ops them.
 *
 * Rationale per shape:
 * - `BY_ID`, `FIRST`: intrinsic single-row result shape; clamping
 *   has no observable effect (limit is hard-coded to 1 / pk-lookup).
 * - `RAW_COUNT`, `VISIBLE_COUNT`, `RAW_EXISTS`, `VISIBLE_EXISTS`:
 *   aggregates / existence checks; the row limit either has no
 *   meaning (aggregates that don't materialize rows) or would
 *   silently corrupt the answer (counting "first N scanned rows"
 *   rather than all matching rows).
 * - `EAGER_LOAD`: per-parent-vs-batched semantics are ambiguous;
 *   the runtime fetches with null limit and paginates per-group
 *   in Kotlin.
 * - `EDGE_PREDICATE`: compiles to EXISTS; row count has no
 *   meaning inside an EXISTS subquery.
 * - `EDGE_TRAVERSAL`: the source-step predicates fold into a
 *   bridging `HasEdgeWith` / `HasM2MEdgeFrom` / `HasEdge`
 *   predicate, which itself compiles to EXISTS — there is no
 *   row-limit slot on the bridge predicate. Honoring limit
 *   operations on EDGE_TRAVERSAL would require lowering source-
 *   with-limit into a CTE or IN-from-subquery, which is a
 *   structural change beyond V1. **V1 deferral:** limit operations
 *   are silent no-op on EDGE_TRAVERSAL until that lowering lands.
 *
 * Used by [InterceptScopeImpl] / [GlobalInterceptScopeImpl] to
 * gate `requireLimitAtMost` / `setDefaultLimitIfAbsent` /
 * `rejectIfLimitGreaterThan` — so a `global { requireLimitAtMost(100) }`
 * doesn't corrupt `visibleCount` (which would otherwise count the
 * first 100 scanned rows rather than all visible rows), and a
 * `rejectIfLimitGreaterThan(10)` doesn't reject every `byIdOrNull` /
 * `rawCount` / `rawExists` / traversal call just because they have
 * null effective limits.
 */
internal fun limitOpsApply(operation: ReadOperation): Boolean = when (operation) {
    ReadOperation.ALL -> true
    ReadOperation.BY_ID,
    ReadOperation.FIRST,
    ReadOperation.RAW_COUNT,
    ReadOperation.VISIBLE_COUNT,
    ReadOperation.RAW_EXISTS,
    ReadOperation.VISIBLE_EXISTS,
    ReadOperation.EAGER_LOAD,
    ReadOperation.EDGE_PREDICATE,
    ReadOperation.EDGE_TRAVERSAL -> false
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
 * [limitOpsApply] for the per-operation table. This honors the RFC's
 * "silent no-op on shapes where row limits have no meaning" contract
 * documented on the [InterceptScope] mutator KDocs.
 */
internal class InterceptScopeImpl<E : Any>(
    private val builder: QuerySpecBuilder,
    private val rejectingInterceptor: String,
    private val entity: String,
    private val entOperation: EntOperation,
    private val readOperation: ReadOperation,
) : InterceptScope<E> {
    override val shape: QueryShape<E> get() = builder.typedShape()

    override fun addPredicate(predicate: Predicate) {
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
            EntError.QueryRejected(
                entity = entity,
                operation = entOperation,
                reason = reason,
                code = code,
                interceptor = rejectingInterceptor,
            )
        )
    }
}

/** Concrete [GlobalInterceptScope] mirror of [InterceptScopeImpl]. */
internal class GlobalInterceptScopeImpl(
    private val builder: QuerySpecBuilder,
    private val rejectingInterceptor: String,
    private val entity: String,
    private val entOperation: EntOperation,
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
            EntError.QueryRejected(
                entity = entity,
                operation = entOperation,
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
        builder: QuerySpecBuilder,
        context: QueryContext,
        entity: String,
        entOperation: EntOperation,
        entityInterceptors: List<RegisteredInterceptor<E>>,
        globalInterceptors: List<RegisteredGlobalInterceptor>,
    ): FrozenQuerySpec {
        for (registered in entityInterceptors) {
            val scope = InterceptScopeImpl<E>(builder, registered.name, entity, entOperation, context.operation)
            registered.interceptor.intercept(scope, context)
        }
        for (registered in globalInterceptors) {
            val scope = GlobalInterceptScopeImpl(builder, registered.name, entity, entOperation, context.operation)
            registered.interceptor.intercept(scope, context)
        }
        return builder.freeze()
    }
}
