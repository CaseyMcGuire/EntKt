package entkt.runtime

import entkt.query.OrderField
import entkt.query.Predicate
import kotlin.reflect.KClass

/**
 * Read-path interception extension point.
 *
 * Each registered `QueryInterceptor<E>` runs once per terminal read
 * on the entity it's registered for, with a chance to add predicates,
 * clamp limits, attach annotations, or reject the query before the
 * driver call.
 *
 * Per the read-path interceptors RFC, interceptors operate under a
 * "reduce or reject" safety property: they can NARROW a query
 * (additional ANDed predicates, stricter limits, rejection) but
 * cannot remove caller predicates, raise caller-set limits, change
 * ordering, or swap the table. The narrow public API on
 * [InterceptScope] enforces this at compile time.
 */
fun interface QueryInterceptor<E : Any> {
    fun intercept(scope: InterceptScope<E>, context: QueryContext)
}

/**
 * Global (entity-agnostic) interceptor that runs on every read
 * regardless of root entity. Useful for cross-cutting concerns like
 * enforce-max-limit guards and query tracing.
 *
 * Globals run AFTER per-entity interceptors in registration order
 * (see Ordering in the RFC). They can't add predicates because typed
 * `Predicate<E>` would be unsafe for varying `E`; they operate on
 * the entity-agnostic mutators (limit / annotate / reject) and can
 * inspect the current shape via [GlobalInterceptScope.shape].
 */
fun interface GlobalQueryInterceptor {
    fun intercept(scope: GlobalInterceptScope, context: QueryContext)
}

/**
 * Per-entity interceptor scope. Exposes only the operations that
 * preserve the reduce-or-reject safety property.
 *
 * The [shape] property is **recomputed on each access** — every
 * read re-derives from the underlying mutable spec. Calling
 * `scope.addPredicate(X)` then reading `scope.shape.predicates`
 * shows X immediately (and increments
 * `interceptorPredicateCount`). However, capturing the result into
 * a local (`val snap = scope.shape`) freezes a [QueryShape] data
 * value at capture time — that local does NOT see subsequent
 * mutations. Application code that wants the latest state must
 * re-read through the `scope.shape` accessor.
 */
interface InterceptScope<E : Any> {
    /** Read-only view of the typed effective query state. */
    val shape: QueryShape<E>

    /** Adds a predicate AND-ed with caller / prior-interceptor / structural predicates. */
    fun addPredicate(predicate: Predicate)

    /**
     * Clamps the effective limit to at most [max] on read shapes
     * where limit operations apply. If no limit is present, sets
     * [max]. If a smaller limit is already in place (caller or
     * prior interceptor), keeps it. Silent no-op on shapes where
     * row limits have no meaning (BY_ID / FIRST / aggregates /
     * eager / edge-predicate). [max] must be `>= 0`; zero is
     * allowed (caller asks for zero rows).
     */
    fun requireLimitAtMost(max: Int)

    /**
     * Sets a default limit when none is in place. No-op if a limit
     * is already in place, AND a silent no-op on shapes where row
     * limits have no meaning. [default] must be `>= 0`.
     */
    fun setDefaultLimitIfAbsent(default: Int)

    /**
     * Rejects the query (on shapes where limit operations apply)
     * if the effective limit is null (unbounded) or exceeds [max].
     * Silent no-op on shapes where row limits have no meaning.
     * Framework sets `QueryRejected.code = "max_limit_exceeded"`
     * on rejections triggered here.
     */
    fun rejectIfLimitGreaterThan(max: Int, reason: () -> String)

    /**
     * Attaches a key/value annotation for diagnostics; surfaces in
     * explain output. Does not affect the query plan. Duplicate
     * keys across interceptors use last-writer-wins.
     */
    fun addAnnotation(key: String, value: String)

    /**
     * Rejects the query with the given reason. The framework
     * converts this into the per-API outcome described in the
     * RFC's Rejection Semantics section.
     */
    fun reject(reason: String, code: String? = null): Nothing
}

/**
 * Global interceptor scope. Mirrors [InterceptScope] minus
 * `addPredicate` (entity-typing isn't available globally) and
 * uses the erased [UntypedQueryShape] view.
 *
 * Live-vs-snapshot rule matches [InterceptScope.shape] — the
 * property accessor re-derives on each call; captured values are
 * frozen.
 */
interface GlobalInterceptScope {
    /** Read-only erased view of the current query state. */
    val shape: UntypedQueryShape

    fun requireLimitAtMost(max: Int)
    fun setDefaultLimitIfAbsent(default: Int)
    fun rejectIfLimitGreaterThan(max: Int, reason: () -> String)
    fun addAnnotation(key: String, value: String)
    fun reject(reason: String, code: String? = null): Nothing
}

/**
 * Read-only typed view of a query's current effective state.
 *
 * Predicate attribution is tracked in three buckets:
 *  - **caller** — added via `query { where(...) }` at the call site
 *  - **structural** — added by generated query code to express the
 *    operation's intrinsic shape: `id = ?` on a by-id read,
 *    `source_id = ?` on an edge traversal, junction-table
 *    constraints on M2M, parent-id `IN` on an eager-load subquery
 *  - **interceptor** — added by framework / per-entity
 *    interceptors via `scope.addPredicate(...)` earlier in the
 *    chain. Globals don't contribute (no addPredicate).
 *
 * Identity: `callerPredicateCount + structuralPredicateCount +
 * interceptorPredicateCount == predicates.size`.
 *
 * `callerLimit` carries the caller-authored limit before any
 * interceptor mutation, enabling attribution-aware policies like
 * "public callers must set a limit" that would otherwise be
 * defeated by a prior `setDefaultLimitIfAbsent`.
 */
data class QueryShape<E : Any>(
    val table: String,
    val predicates: List<Predicate>,
    val orderBy: List<OrderField>,
    val limit: Int?,
    val callerLimit: Int?,
    val offset: Int?,
    val flags: Set<QueryFlag>,
    val annotations: Map<String, String>,
    val callerPredicateCount: Int,
    val structuralPredicateCount: Int,
    val interceptorPredicateCount: Int,
) {
    val hasCallerPredicates: Boolean get() = callerPredicateCount > 0
    val hasInterceptorPredicates: Boolean get() = interceptorPredicateCount > 0

    init {
        val total = callerPredicateCount + structuralPredicateCount + interceptorPredicateCount
        check(total == predicates.size) {
            "QueryShape attribution identity violated: caller=$callerPredicateCount + " +
                "structural=$structuralPredicateCount + interceptor=$interceptorPredicateCount = " +
                "$total but predicates.size=${predicates.size}"
        }
    }
}

/**
 * Read-only erased view for global interceptors. Exposes
 * attribution-count metadata but no typed `Predicate` references.
 */
data class UntypedQueryShape(
    val table: String,
    val entity: KClass<*>,
    val predicateCount: Int,
    val callerPredicateCount: Int,
    val structuralPredicateCount: Int,
    val interceptorPredicateCount: Int,
    val limit: Int?,
    val callerLimit: Int?,
    val offset: Int?,
    val hasOrderBy: Boolean,
    val annotations: Map<String, String>,
) {
    val hasCallerPredicates: Boolean get() = callerPredicateCount > 0
    val hasInterceptorPredicates: Boolean get() = interceptorPredicateCount > 0

    init {
        val total = callerPredicateCount + structuralPredicateCount + interceptorPredicateCount
        check(total == predicateCount) {
            "UntypedQueryShape attribution identity violated: caller=$callerPredicateCount + " +
                "structural=$structuralPredicateCount + interceptor=$interceptorPredicateCount = " +
                "$total but predicateCount=$predicateCount"
        }
    }
}

/**
 * Per-call context passed to an interceptor's `intercept(...)`.
 * Root reads have `currentEntity == rootEntity`, `sourceEntity` /
 * `edgeName` null, and `path` empty. Edge-traversal / eager-load /
 * edge-predicate subqueries carry the traversal context so an
 * interceptor can branch on what step it's running for.
 */
data class QueryContext(
    val privacy: PrivacyContext,
    val operation: ReadOperation,
    val rootEntity: KClass<*>,
    val currentEntity: KClass<*>,
    val sourceEntity: KClass<*>?,
    val edgeName: String?,
    val path: List<EdgeStep>,
    val flags: Set<QueryFlag>,
) {
    val isEagerSubquery: Boolean get() = operation == ReadOperation.EAGER_LOAD
}

data class EdgeStep(
    val source: KClass<*>,
    val edgeName: String,
    val target: KClass<*>,
)

/**
 * Which generated read terminal is firing the interceptor. The
 * framework maps these into [EntOperation] for error reporting via
 * the table in the RFC's Rejection Semantics section: BY_ID → LOAD;
 * everything else → QUERY.
 */
enum class ReadOperation {
    BY_ID, FIRST, ALL,
    RAW_COUNT, VISIBLE_COUNT,
    RAW_EXISTS, VISIBLE_EXISTS,
    EDGE_TRAVERSAL, EDGE_PREDICATE, EAGER_LOAD,
}

/**
 * Public flags carried on a [QueryContext]. V1 has two members,
 * both consumed exclusively by the generated soft-delete
 * interceptor. Public flags do NOT propagate across query steps —
 * each step's `query { ... }` (or eager-load configuration block)
 * sets its own flags.
 *
 * `internalSystemQuery` lives in a separate package-private slot
 * that never appears in this enum and is never visible to
 * interceptors.
 */
@Suppress("EnumEntryName")
enum class QueryFlag {
    withDeleted,
    onlyDeleted,
}

/**
 * Configuration knobs for the read-path interceptor framework.
 * Currently a marker — V1 has no per-config-knob switches beyond
 * the registration DSL itself — but reserved for future
 * cross-cutting opt-ins.
 */
public class InterceptorConfig internal constructor()

/**
 * Internal marker thrown by [InterceptScope.reject] /
 * [GlobalInterceptScope.reject] to short-circuit the interceptor
 * chain. The framework catches this at the wrapper boundary and
 * converts it to the per-API outcome (`Err(QueryRejected)` for
 * *OrError, `EntQueryRejectedException` for *OrThrow / non-result
 * visible*).
 *
 * Public so generated wrapper code in a different module can catch
 * it; application code should never throw or catch this directly
 * (use `scope.reject(...)` to produce one).
 */
public class AbortQueryRejected public constructor(
    public val rejected: EntError.QueryRejected,
) : RuntimeException("query rejected by interceptor '${rejected.interceptor}': ${rejected.reason}")

/**
 * Reserved-prefix string for framework-owned interceptor names.
 * Application interceptors must NOT register with a name starting
 * with this prefix.
 */
public const val FRAMEWORK_INTERCEPTOR_PREFIX: String = "framework:"

/**
 * DSL-side mutable holder generated `EntClientConfig` exposes
 * through an `interceptors { ... }` block. Application code calls
 * `posts(...)` / `users(...)` (one per entity) and `global(...)`
 * to register; the generated init block then transfers these into
 * the runtime EntClient's per-repo + global lists.
 *
 * Name validation runs at registration time:
 *  - `name` is mandatory (the DSL methods require it as a
 *    parameter, so this is enforced at compile time).
 *  - Application names must not start with `framework:`
 *    (validated here at construction; throws
 *    IllegalArgumentException).
 *  - Names must be unique within their scope (per-entity scopes
 *    are independent; globals share one namespace) — validated
 *    when the entry is added.
 */
public class EntInterceptorsConfig public constructor() {
    private val perEntity: MutableMap<String, MutableList<RegisteredInterceptor<*>>> = linkedMapOf()
    private val globalsList: MutableList<RegisteredGlobalInterceptor> = mutableListOf()

    /**
     * Add a per-entity interceptor scoped to [scopeKey] (the
     * generated DSL passes the entity's repo property name like
     * `"posts"`). Generated DSL calls this from per-entity helper
     * methods.
     */
    public fun <E : Any> addEntity(
        scopeKey: String,
        name: String,
        interceptor: QueryInterceptor<E>,
    ) {
        validateApplicationName(name, scopeKey)
        val list = perEntity.getOrPut(scopeKey) { mutableListOf() }
        require(list.none { it.name == name }) {
            "Duplicate interceptor name '$name' for entity scope '$scopeKey'"
        }
        list.add(RegisteredInterceptor(name, interceptor))
    }

    public fun addGlobal(name: String, interceptor: GlobalQueryInterceptor) {
        validateApplicationName(name, "global")
        require(globalsList.none { it.name == name }) {
            "Duplicate global interceptor name '$name'"
        }
        globalsList.add(RegisteredGlobalInterceptor(name, interceptor))
    }

    /** Used by the generated EntClient init block to populate per-repo lists. */
    @Suppress("UNCHECKED_CAST")
    public fun <E : Any> entityInterceptorsFor(scopeKey: String): List<RegisteredInterceptor<E>> =
        (perEntity[scopeKey] ?: emptyList()) as List<RegisteredInterceptor<E>>

    /** Used by the generated EntClient init block to populate the global list. */
    public fun globals(): List<RegisteredGlobalInterceptor> = globalsList.toList()

    private fun validateApplicationName(name: String, scope: String) {
        require(name.isNotBlank()) { "Interceptor name must not be blank (scope='$scope')" }
        require(!name.startsWith(FRAMEWORK_INTERCEPTOR_PREFIX)) {
            "Interceptor name '$name' uses the reserved 'framework:' prefix " +
                "(scope='$scope') — that prefix is reserved for framework-owned " +
                "interceptors installed via schema mixins. Pick a different name."
        }
    }
}
