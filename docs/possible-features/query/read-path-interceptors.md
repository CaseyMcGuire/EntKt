# RFC: Read-Path Interceptors

## Status

Possible future feature. This is not implemented.

## Summary

Add generated query interception hooks that can inspect, constrain, annotate,
or reject read queries before they hit the driver.

The first version should focus on predictable read-path policies:

- tenant scoping
- soft-delete filters
- max-limit guards
- query logging / tracing annotations
- defensive rejection of unsafe broad reads

## Motivation

Some read behavior should be applied consistently across every query for an
entity. Today those concerns either need to be repeated at each call site or
encoded inside privacy rules after rows are loaded.

That is surprising for concerns that are really query-shape concerns:

```kotlin
client.posts.query().allOrThrow()
```

In a multi-tenant application, this should probably include the current
tenant predicate automatically. For a soft-deleted entity, the default query
should probably exclude deleted rows. For a public endpoint, an unbounded query
may need to fail before it can scan a large table.

Read-path interceptors make these policies explicit and centralized without
making every application read go through a custom repository method.

## Non-Goals

- Do not replace LOAD privacy.
- Do not let interceptors grant access to rows privacy would deny.
- Do not make interceptors mutate returned entities.
- Do not require every application to install interceptors.
- Do not support arbitrary SQL rewriting in the first version.
- Do not hide interceptor-added predicates from explain/debug output.

## Proposed API

Add an interceptor interface at the runtime/query layer:

```kotlin
interface QueryInterceptor<E : Any> {
    fun intercept(scope: InterceptScope<E>, context: QueryContext)
}
```

`InterceptScope<E>` exposes only the operations that preserve the "reduce or
reject" safety property (see Privacy Semantics below). Interceptors cannot
remove caller predicates, change ordering, raise the limit, or swap the table
through the public API:

```kotlin
interface InterceptScope<E : Any> {
    /** Read-only view of the typed effective query state — full
     *  caller + interceptor predicates / orderBy, current limit /
     *  offset, current annotations, plus the public flag set.
     *  Useful for branching ("skip my predicate if a previous
     *  interceptor already added one for tenant scoping") and for
     *  defensive policies that want to inspect predicate counts
     *  before deciding whether to add or reject.
     *
     *  **Property access is recomputed; captured values are
     *  snapshots.** The `shape` *property* is recomputed on each
     *  access — every read re-derives from the underlying mutable
     *  spec. So:
     *
     *  ```kotlin
     *  scope.addPredicate(X)
     *  scope.shape.predicates  // contains X — read-after-write works
     *  ```
     *
     *  But because `QueryShape<E>` is a data value, capturing the
     *  result into a local freezes a snapshot at capture time:
     *
     *  ```kotlin
     *  val snap = scope.shape
     *  scope.addPredicate(Y)
     *  snap.predicates         // does NOT contain Y — local is frozen
     *  scope.shape.predicates  // contains Y — fresh re-derive
     *  ```
     *
     *  The same rule applies to `limit` / `annotations` /
     *  `predicates` and to the attribution counts. The framework
     *  does not invalidate or update captured `QueryShape` values
     *  — application code that wants the latest state must re-read
     *  through the `scope.shape` accessor.
     *
     *  See [QueryShape]. */
    val shape: QueryShape<E>

    /** Adds a predicate that is AND-ed with caller and prior-interceptor
     *  predicates. Cannot remove existing predicates. */
    fun addPredicate(predicate: Predicate<E>)

    /** Clamps the effective limit to at most [max] on read shapes where
     *  limit operations apply. If no limit is present, sets [max]. If a
     *  smaller limit is already in place (caller or prior interceptor),
     *  keeps it. [max] must be `>= 0`; `0` is allowed (caller asks for
     *  zero rows). Negative values fail with `IllegalArgumentException`. */
    fun requireLimitAtMost(max: Int)

    /** Sets a default limit on read shapes where limit operations
     *  apply (`ALL`, `EDGE_TRAVERSAL`). No-op if a limit is already
     *  in place, AND a silent no-op on read shapes where row limits
     *  have no meaning (`BY_ID`, `FIRST`, `RAW_COUNT`,
     *  `VISIBLE_COUNT`, `RAW_EXISTS`, `VISIBLE_EXISTS`,
     *  `EAGER_LOAD`, `EDGE_PREDICATE`). See
     *  "Limit semantics by read shape" for the full table. [default]
     *  must be `>= 0` (same rules as [requireLimitAtMost]). */
    fun setDefaultLimitIfAbsent(default: Int)

    /** Rejects the query (on read shapes where limit operations apply
     *  — `ALL`, `EDGE_TRAVERSAL`) if the effective limit (after any
     *  prior interceptor's [setDefaultLimitIfAbsent] /
     *  [requireLimitAtMost]) is null (unbounded) or exceeds [max].
     *  Use this for strict-reject max-limit policies instead of
     *  [requireLimitAtMost]'s silent clamp. **Silent no-op** on read
     *  shapes where row limits have no meaning (same list as
     *  [setDefaultLimitIfAbsent]); on those shapes, an unbounded
     *  read won't trigger a rejection here even though `queryLimit`
     *  is null at the call site. See "Limit semantics by read
     *  shape" for the full table.
     *
     *  [max] must be `>= 0` (same rules as [requireLimitAtMost]).
     *
     *  The framework sets `QueryRejected.code = "max_limit_exceeded"`
     *  on rejections triggered by this method, so callers and tests can
     *  branch on the code without parsing [reason]. */
    fun rejectIfLimitGreaterThan(max: Int, reason: () -> String)

    /** Attaches a key/value annotation for diagnostics; surfaces in
     *  explain/observability output. Does not affect the query plan.
     *  Duplicate keys across interceptors use last-writer-wins per the
     *  framework → entity → global ordering. */
    fun addAnnotation(key: String, value: String)

    // No `setOffset` / `requireOffsetAtMost` — interceptors cannot
    // shape `offset`. The caller's offset is preserved unchanged.
    // (Offset is a paging knob, not a safety knob; capping it at
    // an interceptor layer would silently corrupt pagination.) Use
    // a [reject] policy with an explicit code if huge offsets are
    // a concern for query-plan cost.

    /** Rejects the query with the given reason. The framework converts
     *  this into the per-API outcome described in "Rejection Semantics".
     *
     *  [code] is an optional stable machine-readable identifier (e.g.
     *  `"max_limit_exceeded"`, `"missing_tenant_scope"`) that callers
     *  and tests can branch on independently of [reason] message text.
     *  The framework records the rejecting interceptor's identity on
     *  `QueryRejected.interceptor`. For application registrations the
     *  identity is the mandatory `name` argument passed at registration
     *  (see Generated Registration); for framework-owned interceptors
     *  installed via schema mixins (`softDelete()` etc.) the identity
     *  is the framework's pre-assigned stable name (e.g.
     *  `"framework:soft-delete"`). There is no simpleName /
     *  AnonymousInterceptor fallback — every interceptor in the chain
     *  has a name by construction, because both registration paths
     *  require one. */
    fun reject(reason: String, code: String? = null): Nothing
}
```

`QuerySpec` is the immutable, internal description of the generated query that
the framework hands to drivers after interceptors have run:

```kotlin
data class QuerySpec<E : Any>(
    val table: String,
    val predicates: List<Predicate<E>>,
    val orderBy: List<OrderField<E>>,
    val limit: Int?,
    val offset: Int?,
    val flags: Set<QueryFlag>,
    val annotations: Map<String, String> = emptyMap(),
)
```

`QuerySpec` is not exposed to application interceptors in V1 — they only see
`InterceptScope<E>` (and the read-only `shape: QueryShape<E>` projection).
Framework-owned interceptors (soft-delete, generated
schema features) may operate on a private `QuerySpec` mutator path marked
with an internal annotation; that path is not part of the public API and is
reserved for generated code. Framework mutators must preserve the same
reduce-or-reject invariant the public `InterceptScope` enforces — adding
predicates, clamping/setting/rejecting limits, attaching annotations, or
rejecting the query — unless the specific schema feature documents and
justifies a wider operation (e.g. a future RFC for write-through
interceptors). A framework interceptor that silently removes caller
predicates or raises a caller-set limit is a bug.

**Per-terminal-call spec — interceptors don't accumulate on the
builder.** Interceptors operate on a fresh `QuerySpec` *copy*
built from the query builder's caller-authored state at each
terminal call. They never mutate the builder itself.

```kotlin
val q = client.posts.query { where(Post.published eq true) }
q.allOrThrow()  // interceptors run on a fresh spec, append predicates X, Y
q.allOrThrow()  // interceptors run on a fresh spec, append predicates X, Y again
q.rawCount()    // interceptors run on a fresh spec — no X, Y accumulated
```

Reusing the same query object and invoking multiple terminal
operations re-runs the interceptor chain from the original caller-
authored state each time. Without this guarantee, interceptor-
added predicates / limits / annotations would compound across
terminal calls — a tenant-scope predicate would land twice on
the second `allOrThrow()`, a clamped limit would clamp further on
each call, etc. The per-call copy makes the chain idempotent
across terminal reuse.

Generated repositories apply configured interceptors before driver calls:

```kotlin
val intercepted = client.interceptors.posts.apply(spec, context)
driver.query(intercepted.toDriverQuery())
```

### Global Interceptors

Application code can also register **global** interceptors that run across
all entities — useful for entity-agnostic concerns like enforce-max-limit
or query-tracing. Global interceptors use a distinct, more restricted
interface:

```kotlin
interface GlobalQueryInterceptor {
    fun intercept(scope: GlobalInterceptScope, context: QueryContext)
}

interface GlobalInterceptScope {
    /** Read-only erased view of the current query as seen by this
     *  interceptor — includes mutations from framework
     *  interceptors, per-entity interceptors, AND any prior global
     *  interceptors in registration order (per the Ordering
     *  section: framework → per-entity → globals, and within
     *  globals, registration order is the apply order). So global
     *  interceptor #2 sees global #1's `addAnnotation` entries and
     *  any limit clamps #1 applied. Live-not-snapshot per the same
     *  rule as `InterceptScope<E>.shape` — each property access
     *  re-derives from the underlying spec.
     *  See [UntypedQueryShape]. */
    val shape: UntypedQueryShape

    fun requireLimitAtMost(max: Int)
    fun setDefaultLimitIfAbsent(default: Int)
    fun rejectIfLimitGreaterThan(max: Int, reason: () -> String)
    fun addAnnotation(key: String, value: String)
    fun reject(reason: String, code: String? = null): Nothing
    // No addPredicate — entity-typing not available globally.
}

/**
 * Read-only erased view of a query's current effective state, as
 * seen by a global interceptor running after framework and per-
 * entity interceptors. The predicate / order lists aren't typed
 * (`Predicate<*>` can't be safely projected through a typeless
 * surface), so the shape exposes shape *metadata* — counts and
 * presence flags — sufficient for defensive policies like
 * "reject unscoped broad rawCount" without leaking typed
 * references through the global API.
 */
data class UntypedQueryShape(
    val table: String,
    val entity: KClass<*>,
    /** Total number of effective predicates (caller + structural +
     *  interceptor — see [QueryShape] for the three-bucket
     *  definition). */
    val predicateCount: Int,
    /** Number of caller-authored predicates added via the public
     *  DSL (`query { where(...) }`). */
    val callerPredicateCount: Int,
    /** Number of generated structural predicates (`id = ?` on
     *  by-id, source-id on edge traversal, junction constraints,
     *  eager-load parent-id `IN`, etc.). */
    val structuralPredicateCount: Int,
    /** Number of predicates added by prior interceptors (framework /
     *  per-entity) via `scope.addPredicate(...)`. Global interceptors
     *  do NOT contribute here — `GlobalInterceptScope` omits
     *  `addPredicate` (entity-typing isn't available globally), so
     *  globals can only affect limits / annotations / rejections. */
    val interceptorPredicateCount: Int,
    val limit: Int?,
    /** The caller-authored limit (`query { limit(N) }`) BEFORE any
     *  interceptor's `setDefaultLimitIfAbsent` / `requireLimitAtMost`
     *  applied. `null` if the caller didn't set a limit, regardless
     *  of whether a prior interceptor filled one in. Enables
     *  attribution-aware policies like "public callers must
     *  explicitly set a limit" that would otherwise be defeated by
     *  a prior interceptor's default-filling. */
    val callerLimit: Int?,
    val offset: Int?,
    val hasOrderBy: Boolean,
    /** Effective annotation map written by framework + per-entity +
     *  prior global interceptors AND the current interceptor's own
     *  `scope.addAnnotation` calls so far. Each `shape.annotations`
     *  read re-derives from the underlying spec per the live-shape
     *  rule — calling `scope.addAnnotation("k", "v")` then reading
     *  `scope.shape.annotations` shows `"k" → "v"` immediately,
     *  not after the current interceptor returns. Same rule
     *  governs the entity scope. */
    val annotations: Map<String, String>,
) {
    val hasCallerPredicates: Boolean get() = callerPredicateCount > 0
    val hasInterceptorPredicates: Boolean get() = interceptorPredicateCount > 0
}
```

`GlobalInterceptScope` omits `addPredicate` because predicates require a
typed entity context; a single global interceptor can't safely add
`Predicate<E>` when `E` varies per registered entity. Global interceptors
operate on operations that are entity-agnostic (limit, annotate, reject)
and can use `shape` for read-only inspection of the post-pipeline
query state.

**Per-entity scope also exposes shape.** `InterceptScope<E>` carries a
parallel `shape: QueryShape<E>` read-only view of the typed effective
query (full typed `Predicate<E>` / `OrderField<E>` lists plus the same
metadata). Per-entity interceptors don't strictly need it for
predicate-shaping (they just `addPredicate(...)` whatever they want),
but it's useful for branching: "skip my predicate if a previous
interceptor already added one for tenant scoping," "reject if the
query has no caller predicates," etc.

```kotlin
interface InterceptScope<E : Any> {
    val shape: QueryShape<E>     // typed read-only view
    fun addPredicate(predicate: Predicate<E>)
    fun requireLimitAtMost(max: Int)
    // ... etc.
}

data class QueryShape<E : Any>(
    val table: String,
    /** Full typed list of effective predicates (caller + structural
     *  + framework + per-entity interceptor + prior interceptors in
     *  this entity's chain), in apply order. */
    val predicates: List<Predicate<E>>,
    val orderBy: List<OrderField<E>>,
    val limit: Int?,
    /** The caller-authored limit (`query { limit(N) }`) BEFORE any
     *  interceptor's `setDefaultLimitIfAbsent` / `requireLimitAtMost`
     *  applied. `null` if the caller didn't set a limit, regardless
     *  of whether a prior interceptor filled one in. Same attribution
     *  story as [callerPredicateCount] — enables policies like
     *  "public callers must explicitly set a limit" that would
     *  otherwise be defeated by a prior interceptor's default. */
    val callerLimit: Int?,
    val offset: Int?,
    val flags: Set<QueryFlag>,
    val annotations: Map<String, String>,
    /** Predicate attribution metadata. The framework tracks each
     *  predicate's source in one of three buckets:
     *
     *   - **caller** — added via the public DSL (`query { where(...) }`).
     *   - **structural** — added by generated query code to express
     *     the operation's intrinsic shape: the `id = ?` predicate on
     *     a by-id read, the `source_id = ?` predicate on an edge
     *     traversal, junction-table constraints on an M2M traversal,
     *     the parent-id `IN` predicate on an eager-load subquery,
     *     etc. Structural predicates are neither caller-authored nor
     *     interceptor-added — they're what makes the read this
     *     specific operation rather than a bare table scan.
     *   - **interceptor** — added by framework / per-entity
     *     interceptors via `scope.addPredicate(...)` earlier in the
     *     chain. (Globals omit `addPredicate` — entity-typing isn't
     *     available globally — so they don't contribute here.)
     *
     *  Exposed here so an entity interceptor can branch on
     *  "reject if no caller predicates" / "skip my predicate if a
     *  previous interceptor already added one" without inspecting
     *  typed Predicate references against an attribution map.
     *  Identity: `callerPredicateCount + structuralPredicateCount +
     *  interceptorPredicateCount == predicates.size`. */
    val callerPredicateCount: Int,
    val structuralPredicateCount: Int,
    val interceptorPredicateCount: Int,
) {
    val hasCallerPredicates: Boolean get() = callerPredicateCount > 0
    val hasInterceptorPredicates: Boolean get() = interceptorPredicateCount > 0
}
```

**Example: defensive rejection of unscoped broad reads.** The shape
view enables policies that the limit operations alone can't express
on count/exists shapes (where limit operations are silent no-ops):

```kotlin
class RejectUnscopedAggregates : GlobalQueryInterceptor {
    override fun intercept(scope: GlobalInterceptScope, context: QueryContext) {
        when (context.operation) {
            ReadOperation.RAW_COUNT,
            ReadOperation.RAW_EXISTS -> {
                // "Unscoped" = no caller-authored where(...). We
                // check hasCallerPredicates (not predicateCount) so
                // soft-delete and other structural / interceptor
                // predicates don't satisfy the "caller scoped this
                // query" requirement.
                if (!scope.shape.hasCallerPredicates) {
                    scope.reject(
                        "broad ${context.operation} requires a caller-authored predicate",
                        code = "broad_aggregate",
                    )
                }
            }
            else -> Unit
        }
    }
}
```

Predicate-shaping concerns (tenant scoping, soft-delete) belong in
per-entity `QueryInterceptor<E>` registrations.

The limit and reject methods behave identically to their entity-scope
counterparts on `InterceptScope<E>`: `rejectIfLimitGreaterThan`
automatically sets `QueryRejected.code = "max_limit_exceeded"`,
`setDefaultLimitIfAbsent` no-ops when a limit is already in place,
`requireLimitAtMost` only clamps down (cannot raise), `addAnnotation`
uses last-writer-wins per the framework → entity → global ordering, and
`reject(reason, code)` populates `QueryRejected.code` with the supplied
value. The "Limit semantics by read shape" rules below apply uniformly
to both scope variants.

### QueryContext

```kotlin
data class QueryContext(
    val privacy: PrivacyContext,
    val operation: ReadOperation,
    val rootEntity: KClass<*>,
    val currentEntity: KClass<*>,
    val sourceEntity: KClass<*>?,    // null for the root read
    val edgeName: String?,           // null for non-traversal reads
    val path: List<EdgeStep>,        // empty for the root read
    // Public flags only (withDeleted / onlyDeleted in V1). Internal
    // flags like `internalSystemQuery` are stripped before reaching
    // interceptors — see "Flag visibility to interceptors" below.
    val flags: Set<QueryFlag>,
) {
    val isEagerSubquery: Boolean get() = operation == ReadOperation.EAGER_LOAD
}

data class EdgeStep(
    val source: KClass<*>,
    val edgeName: String,
    val target: KClass<*>,
)

enum class ReadOperation {
    BY_ID, FIRST, ALL,
    RAW_COUNT, VISIBLE_COUNT,
    RAW_EXISTS, VISIBLE_EXISTS,
    EDGE_TRAVERSAL, EDGE_PREDICATE, EAGER_LOAD,
}

/**
 * Flags carried on a [QueryContext]. V1 has two public members
 * (`withDeleted`, `onlyDeleted`) consumed exclusively by the
 * generated soft-delete interceptor. The framework also tracks an
 * `internalSystemQuery` flag, but that lives in a separate
 * package-private field on the internal query spec — it never
 * appears in this enum and is never visible to interceptors. See
 * "Flag visibility to interceptors" below.
 *
 * Future flags can be added here when a generated framework
 * interceptor needs a call-site opt-in / opt-out signal; the
 * declaration mechanism for application interceptors to honor
 * specific flags is deferred (see Flags And Capabilities).
 */
enum class QueryFlag {
    withDeleted,
    onlyDeleted,
}
```

For root reads, `currentEntity == rootEntity`, `sourceEntity` and `edgeName`
are null, and `path` is empty. For edge-traversal and eager-load subqueries,
`currentEntity` is the target entity (the one being queried), `sourceEntity`
is the parent, `edgeName` is the edge being traversed, and `path` records
the full traversal — e.g., for `Article.author.organization` evaluated
against the parent `Article` context, `path` is
`[Article→author→User, User→organization→Organization]` and `currentEntity`
is `Organization`. Explain output renders the path string; interceptors can
branch on `sourceEntity` / `edgeName` / `path.size` for traversal-specific
predicates and nested-eager policies.

V1 `QueryContext` is **read-only and pure**: no `client`, no driver access,
no transaction handle. By construction, the framework provides no read
capability to interceptors; the contract is "no framework-provided read
access in V1." An interceptor can still capture an `EntClient` in its
constructor — the framework cannot prevent that — but doing so is
documented as unsafe (it bypasses the no-recursion invariant, and a future
runtime recursion guard may detect and reject it). The viewer is reached
through `context.privacy.viewer`.

### Read Surfaces

Every generated read entry point routes through `apply(...)` before any driver
call:

- by-id: `byIdOrThrow`, `byIdOrNull`, `visibleByIdOrNull`, `byIdOrError`
- first row: `firstOrThrow`, `firstOrNull`, `firstVisibleOrNull`, `firstOrError`
- many rows: `allOrThrow`, `allOrError`, `visibleAll`, `visibleAllOrError`
- driver-side aggregates: `rawCount`, `rawCountOrError`, `rawExists`,
  `rawExistsOrError`
- materializing aggregates: `visibleCount`, `visibleCountOrError`,
  `visibleExists`, `visibleExistsOrError`
- edge traversal: generated query methods on entity references
  (`queryAuthor()`, `queryPosts()`, etc.), plus eager-load subqueries
  (`.with { ... }`). Loaded-entity field access (`post.author` on an
  already-loaded entity) does not hit storage and does not fire
  interceptors. See "Multi-step traversal chains" below for which
  interceptors fire on each step of a chained traversal.
- edge predicates: `has`, `hasWhere` on the target entity

**Edge-predicate existence semantics.** `has` / `hasWhere` compile
to `EXISTS` subqueries against the target table. Target-entity
interceptors (e.g. soft-delete, tenant scoping) apply inside that
subquery — so `Post.author.has()` after `softDelete()` is installed
on `User` means "has a *non-soft-deleted* author," not "has any
author row that physically exists." This is the intended behavior
(an entity scoped out by a target interceptor isn't really
"there" from the application's perspective), but it changes
existence semantics in a way that's surprising if a reader assumes
`has(edge)` is a pure foreign-key existence check. LOAD privacy
does NOT run for edge predicates because no target entity is
materialized — only interceptor-added predicates apply.

Any generated read not on this list is a bug. **V1 is absolute: no
generated read bypasses interceptors.** Tenant scoping, max limits,
and soft-delete filters are leaky guarantees if any read can opt
out silently, so the framework offers no opt-out switch at all in
V1. The `internalSystemQuery` flag (see Flags And Capabilities) is
*reserved* for a possible future framework opt-out path; today it
has no effect anywhere and no generated code sets it.

**By-id reads** are lowered to a single-row query with `id = ? AND
<interceptor predicates>` so interceptor-added predicates (tenant scoping,
soft-delete filters, etc.) apply uniformly. If the row exists in the table
but is filtered out by interceptor predicates, the result is the same as
"not found": `null` for `*OrNull`, `Err(NotFound)` for `*OrError`,
`EntNotFoundException` for `*OrThrow`. No new driver method is required —
by-id is a degenerate `first()` with an id predicate.

**Multi-step traversal chains.** Given:

```kotlin
client.users.query()
    .queryGroups()
    .queryPosts()
    .allOrThrow()
```

Each step in the chain is a separate **logical intercepted query
shape** against a separate target entity — each fires the
**target entity's** interceptors with its own `QueryContext`. The
framework may lower multiple logical steps into a single storage
round-trip (a join, an EXISTS subquery, a `WHERE source_id IN
(...)` predicate from a prior step's result) when the lowering is
semantically equivalent and the interceptor contributions
compose correctly — that's a driver-implementation choice, not
part of the public contract. From an interceptor's perspective,
each step is its own intercepted query with its own attribution
and rejection surface; the storage shape is opaque.

1. `client.users.query()` — root read on `User`. User interceptors
   run (`currentEntity = User`, `path = []`).
2. `.queryGroups()` — bridging read on `Group` constrained to
   groups reachable from the prior User result. Group interceptors
   run, with `sourceEntity = User`, `edgeName = "groups"`,
   `path = [User→groups→Group]`.
3. `.queryPosts().allOrThrow()` — terminal read on `Post`
   constrained to posts reachable from the prior Group result.
   Post interceptors run, with `sourceEntity = Group`,
   `edgeName = "posts"`, `path = [User→groups→Group,
   Group→posts→Post]`.

So User's interceptors do constrain the initial users *as the source
of the chain*, Group's interceptors constrain the intermediate
bridging query, and Post's interceptors constrain the terminal query
— all three layers of tenant-scoping / soft-delete / max-limit
guards apply uniformly. `QueryContext.path.size` lets a traversal-
specific interceptor branch on its position in the chain
(`path.isEmpty()` → root step; non-empty → bridging or terminal).

V1 does NOT add a separate `QueryTraverser` middleware concept
(Entgo's name for the equivalent surface). Instead, generated
traversal code reuses the existing `QueryInterceptor<E>` registered
for each entity, applied at each traversal step. This preserves
the Entgo-style "every hop is constrained by its target's
authorization" guarantee without introducing a second public
middleware layer — one interceptor API, applied per-entity, fires
at every relevant step. The trade-off: an interceptor that only
wants to fire on "root reads of E" or "bridging reads from E to
X" must inspect `context.path` itself rather than registering at a
narrower surface; this keeps the registration model simple at the
cost of slightly more conditional logic inside narrowly-scoped
interceptors.

**Limit semantics by read shape.** Limit operations
(`setDefaultLimitIfAbsent`, `requireLimitAtMost`, `rejectIfLimitGreaterThan`)
classify into one of two buckets — **silent no-op** or **apply normally** —
based on the operation's `ReadOperation`:

- **Silent no-op** (limit operations have no effect):
  - `RAW_COUNT`, `RAW_EXISTS` — driver-side aggregates; no application
    rows materialize.
  - `BY_ID`, `FIRST` — intrinsic single-row result shape; a 100-row
    default limit is meaningless and a 50-row max is trivially satisfied.
    The `visibleOverfetchLimit` scan budget for `firstVisibleOrNull` is
    **client config only** (`EntClientConfig.visibleOverfetchLimit`),
    not interceptor-shaped.
  - `EAGER_LOAD` — per-parent-vs-batched semantics are ambiguous in V1
    ("limit 10" could mean 10 targets per parent or 10 total across the
    eager batch, and the answer depends on the implementation strategy).
  - `EDGE_PREDICATE` — `has` / `hasWhere` compile to `EXISTS` subqueries
    where a row limit has no meaning.
  - `VISIBLE_COUNT`, `VISIBLE_EXISTS` — these materialize rows to apply
    LOAD privacy, but applying a row limit silently corrupts the answer
    (`setDefaultLimitIfAbsent(100)` on `visibleCount()` would mean
    "count the first 100 scanned rows," not "count all visible rows";
    `visibleExists` could return `false` before scanning far enough). The
    scan budget here is bound by `EntClientConfig.visibleOverfetchLimit`
    for `visibleExists` and unbounded for `visibleCount`. Interceptors
    that need to gate either use `addPredicate` to narrow scope or
    `reject(...)` to refuse the read.

**Caller-authored `limit(n)` / `offset(n)` are untouched by this
carve-out.** The silent-no-op rule applies only to *interceptor*
limit operations (`setDefaultLimitIfAbsent` /
`requireLimitAtMost` / `rejectIfLimitGreaterThan`). Caller-
authored `query { limit(50) }` keeps the terminal API's normal
semantics on every shape it documents — `visibleCount()` /
`visibleExists()` honor caller `limit` / `offset` per
[`docs/04-queries.md`](../../04-queries.md), and so does the
underlying `driver.query(...)` call. The distinction matters
because an interceptor adding `setDefaultLimitIfAbsent(100)` to a
`visibleCount()` call would silently corrupt the count (cap the
scan, count what's left); a caller writing `query { limit(100) }
.visibleCount()` is explicitly asking for that bounded count and
gets it.

- **Apply normally** (limit operations shape the result set):
  - `ALL`, `EDGE_TRAVERSAL` — `allOrThrow` / `allOrError` / `visibleAll` /
    `visibleAllOrError` / `queryAuthor()` / `queryPosts()` and friends.
    Max-limit guards constrain the underlying row scan, which is
    exactly the surface they're meant to protect.

    **`visibleAll` / `visibleAllOrError` — limit caps storage
    scan, not visible result count (V1).** When an effective
    `limit(N)` reaches the visible-row path (set by the caller
    OR by an interceptor's `setDefaultLimitIfAbsent` /
    `requireLimitAtMost`), the limit constrains the storage rows
    scanned before LOAD privacy filtering, NOT the number of
    visible rows returned. So `query.limit(50).visibleAll()`
    fetches at most 50 storage rows and returns the visible
    subset — possibly fewer than 50 visible rows even when more
    visible rows exist beyond denied ones in storage. This
    matches what the codegen actually does today and what the
    `EntClientConfig.visibleOverfetchLimit` cap is for. A future
    paged-visible-scan API can introduce a separate
    "visible-result limit" operation distinct from the
    storage-scan budget; until then, callers that want exactly
    N visible rows must paginate via `queryOffset` and accept
    that the scan-budget shape is the V1 contract.

    **Cardinality note for `EDGE_TRAVERSAL`.** V1 treats every
    traversal — to-one (`queryAuthor()` from `belongsTo`) and
    to-many (`queryPosts()` from `hasMany` / M2M) — as potentially
    multi-row for limit purposes, because the traversal returns a
    query handle on the target entity, not the resolved row.
    `setDefaultLimitIfAbsent(100)` on a to-one traversal is
    redundant (the result is bounded to 1 anyway) and
    `requireLimitAtMost(50)` is trivially satisfied — neither
    affects the answer, but neither rejects either, so this is
    safe-by-default. Splitting `EDGE_TRAVERSAL` into
    `EDGE_TRAVERSAL_TO_ONE` (silent no-op like `BY_ID`) vs
    `EDGE_TRAVERSAL_TO_MANY` (apply normally) is plausible but
    deferred until a use case shows up that wants the distinction.

A future RFC can add explicit scan-budget operations (semantically
distinct from row-limit operations) for the no-op cases where bounding
the scan is useful — `firstVisibleOrNull`'s `visibleOverfetchLimit`,
eager-load per-parent scan caps, `visibleCount` scan limits — if
concrete use cases emerge.

`addPredicate`, `addAnnotation`, and `reject` apply uniformly to every
read shape. Interceptors that want to gate broad reads use `addPredicate`
to narrow scope or `reject(...)` to refuse the read.

**`exists` API shape.** The current generator emits two privacy-explicit
variants — `rawExists(): Boolean` (skips LOAD privacy; fast driver
existence probe) and `visibleExists(): Boolean` (privacy-aware bounded
scan capped by `EntClientConfig.visibleOverfetchLimit`). The legacy
`exists()` was deliberately removed for surprising semantics; this RFC
does not re-introduce it. Add the structured variants
`rawExistsOrError(): EntResult<Boolean>` and
`visibleExistsOrError(): EntResult<Boolean>` to the Result Variants RFC
alongside the other `*OrError` variants. There are no `*OrNull` variants —
neither outcome of a boolean predicate is "expected absence."

**`count` API shape.** Add structured-result count variants alongside the
existing count APIs:

```kotlin
rawCountOrError(): EntResult<Long>
visibleCountOrError(): EntResult<Long>
```

`rawCountOrError` preserves `rawCount` semantics: it is a driver-side
aggregate that skips LOAD privacy. The failure surface is:

- interceptor `reject(...)` → `Err(EntError.QueryRejected)`
- driver failure → routes through `classifyDriverError` to its
  normal structured shape (`Err(ConstraintViolation)` for SQLSTATE
  23xxx, `Err(DriverFailure)` for other SQLException-family failures,
  re-throw for programming bugs — see the Result Variants RFC's
  `classifyDriverError` rules)

`visibleCountOrError` preserves `visibleCount` semantics: it
materializes rows, applies LOAD privacy, and returns the visible
count as `Ok(count)`. Same failure surface as `rawCountOrError` —
interceptor `reject` maps to `Err(QueryRejected)`; driver failures
map to their own structured `EntError` variants per
`classifyDriverError`. Privacy denial does NOT surface as a failure
(that's the point of `visible*`: denied rows drop silently from
the count).

There are no `*OrNull` count variants — zero is the natural
expected-absence result, so collapsing into `null` adds no signal.

### Rejection Semantics

`scope.reject(reason)` aborts the query before driver execution and
**short-circuits the remaining interceptor chain**: no subsequent
framework / entity / global interceptor's `intercept(...)` runs after
the first `reject(...)`. The explain / observability output records
interceptors that did run up to and including the rejecting one (with
its rejection metadata — reason, code, interceptor name); interceptors
that would have run after the reject point are not invoked and do not
appear.

Short-circuit is the natural choice because (a) running later
interceptors can't change the outcome — once one interceptor has
decided "no," no later annotation / predicate / limit operation
matters — and (b) running them anyway opens an ordering footgun
where a later interceptor's `addAnnotation` would silently overwrite
the rejecting interceptor's metadata via last-writer-wins.

The framework converts the rejection into a per-API outcome:

- `*OrThrow` → throws `EntQueryRejectedException(EntError.QueryRejected(reason))`.
- `*OrError` → returns `Err(EntError.QueryRejected(reason))`. This includes
  the `visible*OrError` family (`visibleAllOrError`, `visibleCountOrError`,
  `visibleExistsOrError`) — **suffix wins over prefix**: the `OrError`
  suffix is the structured-result opt-in regardless of `visible*` prefix.
- `*OrNull` and non-result `visible*` APIs (`visibleAll`, `visibleByIdOrNull`,
  `firstVisibleOrNull`, `visibleCount`, `visibleExists`) → **throws**
  `EntQueryRejectedException`. Rejection is neither absence nor invisibility,
  so silently collapsing it to null would hide tenant-scope/auth-shape policy
  decisions and create the same information-disclosure footgun nullable APIs
  avoid for privacy denial.
- `rawExists()` / `visibleExists()` → throw `EntQueryRejectedException`;
  `rawExistsOrError()` / `visibleExistsOrError()` return `Err(QueryRejected)`.
  The boolean return must not silently become `false`.

**Interceptor exceptions other than `scope.reject(...)`.** Only
`scope.reject(...)` produces the `QueryRejected` / `Err(QueryRejected)`
contract above. **Any other exception thrown from interceptor code
— `IllegalStateException` from an invariant violation,
`NullPointerException` from a bug, vanilla `RuntimeException` from
application logic, etc. — propagates unchanged through the read
path.** *OrThrow APIs throw the original exception; *OrError APIs
also let it escape (NOT wrapped as `Err(DriverFailure)`,
`Err(QueryRejected)`, or any other structured error), matching the
"fail loud on bugs" stance documented for `classifyDriverError`.
The only structured failures the read path surfaces are the ones
`scope.reject` explicitly produces. Application bugs in interceptor
code must be visible to the application, not hidden inside
`EntResult.Err`.

**`ReadOperation` → `EntOperation` mapping.** When constructing
`EntError.QueryRejected.operation` from `QueryContext.operation`, the
framework applies a closed mapping:

| `ReadOperation` | `EntOperation` |
|---|---|
| `BY_ID` | `LOAD` |
| `FIRST`, `ALL`, `EDGE_TRAVERSAL`, `EDGE_PREDICATE`, `EAGER_LOAD`, `RAW_COUNT`, `VISIBLE_COUNT`, `RAW_EXISTS`, `VISIBLE_EXISTS` | `QUERY` |

The runtime `EntOperation` enum
([`EntError.kt`](../../../runtime/src/main/kotlin/entkt/runtime/EntError.kt))
has six variants: `LOAD` / `QUERY` / `CREATE` / `UPDATE` / `DELETE`
/ `EDGE_MUTATION`. Count / exists aggregates collapse into `QUERY`
rather than carving out a separate `AGGREGATE` variant — they're
predicate-driven reads with a different return shape, not a distinct
operation kind from a privacy / classifier perspective. (Earlier
drafts proposed an `AGGREGATE` variant for these; the Result
Variants RFC dropped it.) The only **new** addition this RFC makes
to `EntError` is the `QueryRejected` variant defined below. The
mapping is exhaustive — every `ReadOperation` value lands in
exactly one `EntOperation` bucket — so `when (readOp)` covers all
cases at the call site that constructs `QueryRejected`.

A new `EntError` variant and matching exception subclass are required:

```kotlin
data class QueryRejected(
    override val entity: String,
    override val operation: EntOperation,
    val reason: String,
    val code: String? = null,        // stable machine-readable code, e.g. "max_limit_exceeded"
    val interceptor: String,         // mandatory registration name for application interceptors, or framework-assigned stable name (e.g. "framework:soft-delete"); set by the framework. Non-null by construction — every interceptor in the chain has a name (no simpleName / AnonymousInterceptor fallback, see the reject() KDoc).
    override val message: String = reason,
) : EntError

class EntQueryRejectedException(
    override val error: EntError.QueryRejected,
) : EntException(error)
```

Add these alongside the existing variants in the
[Result Variants RFC](../tooling/entkt-result-variants-rfc.md).

## Generated Registration

Interceptors register inside the existing `EntClient(driver) { ... }`
block, alongside hooks / policies / privacyContext /
transactionRequirement / visibleOverfetchLimit — consistent with the
rest of the client config surface. The `interceptors { ... }` sub-
block scopes per-entity registrations and `global(...)` registrations
in one place:

```kotlin
val client = EntClient(driver) {
    interceptors {
        // Per-entity: `TenantReadInterceptor` shapes Post queries only.
        posts(TenantReadInterceptor(Post.tenantId), name = "tenant-scope")
        users(MaxLimitInterceptor<User>(defaultLimit = 100, maxLimit = 500), name = "user-max-limit")

        // Global: runs on every entity's reads. The interceptor
        // is a `GlobalQueryInterceptor` (entity-agnostic interface,
        // see Global Interceptors section above).
        global(EnforceMaxLimit(maxLimit = 1000), name = "global-max-limit")
        global(QueryTracer(), name = "tracer")
    }
}
```

The `name` parameter is mandatory and must be unique within its
scope (per-entity names are scoped to the entity; `global(...)`
names share a single namespace across all globals). The name
surfaces verbatim on `QueryRejected.interceptor`, so a registration
of `global(EnforceMaxLimit(...), name = "global-max-limit")` that
rejects a query produces `Err(QueryRejected(... interceptor =
"global-max-limit" ...))`. Stable names matter for telemetry,
debugging, and test assertions.

**The `framework:` prefix is reserved.** Application interceptor
names must not start with `"framework:"` — that prefix is reserved
for framework-owned interceptors installed via schema mixins
(`framework:soft-delete`, future `framework:audit`, etc.).
Registering an application interceptor with a `framework:`-prefixed
name fails at `EntClient` construction time. Application names
must also not collide with framework-owned interceptors on the
same entity (e.g. naming an application interceptor
`"soft-delete"` while the soft-delete mixin is installed is
allowed because the framework version is `framework:soft-delete` —
no collision after the prefix rule applies).

A schema-level default can install framework-owned interceptors:

```kotlin
override fun mixins() = listOf(softDelete())
```

The soft-delete mixin could register the same internal filter that backs:

```kotlin
client.posts.query().allOrThrow()
client.posts.query { withDeleted() }.allOrThrow()
client.posts.query { onlyDeleted() }.allOrThrow()
```

**By-id and soft-deleted rows.** `byIdOrNull(id)` / `byIdOrError(id)` etc.
run through the soft-delete interceptor like every other read, so a
soft-deleted row returns "not found" by default. V1 has no by-id surface
that accepts `withDeleted` / `onlyDeleted` flags — callers who need to
look up a soft-deleted row by id express it as a query:
`client.posts.query { withDeleted(); where(id eq deletedPostId) }.firstOrNull()`.
A typed `byIdOrNull(id) { withDeleted() }` shape is a possible follow-up
if the workaround proves common.

See [Soft Delete](../schema/soft-delete.md) for the entity-level behavior.

## Common Interceptors

### Tenant Scoping

```kotlin
class TenantReadInterceptor<E : Any>(
    private val tenantColumn: Column<E, TenantId>,
) : QueryInterceptor<E> {
    override fun intercept(scope: InterceptScope<E>, context: QueryContext) {
        scope.addPredicate(tenantColumn eq context.privacy.viewer.tenantId)
    }
}
```

Tenant scoping is not a substitute for privacy. It prevents accidental broad
queries and helps the database use tenant indexes, while privacy still makes
the final authorization decision.

### Soft Delete

Soft-delete filtering can be modeled as a generated interceptor:

```kotlin
deletedAt.isNull()
```

The query flags `withDeleted` and `onlyDeleted` let the generated query builder
alter that default behavior without making soft delete a special case in every
driver.

### Max Limit

```kotlin
class MaxLimitInterceptor<E : Any>(
    private val defaultLimit: Int,
    private val maxLimit: Int,
    private val clampInsteadOfReject: Boolean = false,
) : QueryInterceptor<E> {
    override fun intercept(scope: InterceptScope<E>, context: QueryContext) {
        scope.setDefaultLimitIfAbsent(defaultLimit)
        if (clampInsteadOfReject) {
            scope.requireLimitAtMost(maxLimit)
        } else {
            scope.rejectIfLimitGreaterThan(maxLimit) {
                "query limit exceeds maxLimit=$maxLimit"
            }
        }
    }
}

val users = MaxLimitInterceptor<User>(defaultLimit = 100, maxLimit = 500)
```

Behavior:

- on read shapes where limit operations apply normally, if no limit is present,
  the interceptor applies `defaultLimit` via `setDefaultLimitIfAbsent` (no-op
  when the caller or a prior interceptor already set one)
- on those same read shapes, `requireLimitAtMost` sets `maxLimit` when the read
  is unbounded, clamps any effective limit above `maxLimit` down to `maxLimit`,
  and cannot raise a smaller limit
- strict-reject mode uses `rejectIfLimitGreaterThan(...)`, which surfaces as
  `EntQueryRejectedException` / `Err(QueryRejected)` per Rejection Semantics
- no V1 interceptor (framework or application) honors `internalSystemQuery`
  — there is no bypass for max-limit. A future bypass would require
  explicit opt-in by this interceptor and an audit-trail entry per Flags
  And Capabilities.

The first version should prefer rejection over silent clamping unless the
interceptor is explicitly configured with `clampInsteadOfReject = true`.

### Logging And Tracing

Interceptors can attach key/value annotations for query diagnostics:

```kotlin
override fun intercept(scope: InterceptScope<E>, context: QueryContext) {
    scope.addAnnotation("operation", "feed.load")
}
```

Annotations land on `QuerySpec.annotations` and surface in explain /
observability output. They do **not** affect the query plan — they are
diagnostic metadata only, consumed by
[Query Observability Diagnostics](query-observability-diagnostics.md).

## Flags And Capabilities

Query flags split by visibility:

**Public flags** (caller-set via `query { ... }`):

- `withDeleted` — soft-delete only; honored exclusively by the generated
  soft-delete interceptor.
- `onlyDeleted` — soft-delete only; honored exclusively by the generated
  soft-delete interceptor.

Public flags are part of the call-site API and may be set freely by
application code.

**Internal flags** (framework-set, package-private; not accessible to
application code):

- `internalSystemQuery` — a reserved internal capability for future
  framework paths that need to bypass specific interceptors. V1 has **no
  current framework opt-ins**: soft-delete, tenant scoping, max-limit, and
  every other framework- or application-defined interceptor runs on every
  read regardless of this flag.

  **Public flags do not propagate across query steps.**
  `withDeleted` / `onlyDeleted` (and any future public flag) attach
  to the specific `query { ... }` block that set them; they do NOT
  carry over into eager-load subqueries (`.with { ... }`), edge
  traversals (`queryAuthor()`, `queryPosts()`), or edge-predicate
  subqueries (`has`, `hasWhere`) on target entities. A target
  step's `QueryContext.flags` is empty unless that step's own
  `query { ... }` (or eager-load configuration block) explicitly
  set a flag. Worked example: a visible `Post` eager-loading
  `Author` is a parent-target relationship across different
  entities — the parent `Post.query { withDeleted() }` enabling
  withDeleted on the post read says nothing about whether the
  caller wanted withDeleted on the target author read; the eager
  `Author` step's flags are empty, so soft-delete runs
  unconditionally there. The same rule applies to traversal
  bridging queries and edge-predicate EXISTS subqueries — the flag
  set is per-step, not inherited.

  **Setting flags on a target step.** Eager-load methods accept
  the same configuration block the root `query { ... }` takes, so
  any flag the public DSL exposes works inside an eager-load:

  ```kotlin
  // Root post query: withDeleted only on Post.
  client.posts.query {
      withDeleted()                    // → root step's flags = {withDeleted}
      withAuthor()                     // → Author step's flags = {} (no propagation)
  }.allOrThrow()

  // Same root, this time also withDeleted on the eager Author target.
  client.posts.query {
      withDeleted()                    // root step's flags = {withDeleted}
      withAuthor {
          withDeleted()                // Author step's flags = {withDeleted}
      }
  }.allOrThrow()
  ```

  Traversal steps work the same way — `client.users.query()
  .queryPosts { withDeleted() }` enables the flag on the bridging
  `Post` step only. Each step's `QueryContext.flags` reflects its
  own configuration block, never the parent's.

  The flag exists so
  future internal paths have a documented escape hatch; introducing a new
  bypass requires explicit opt-in by the affected interceptor and an
  audit-trail entry in the explain output.

**Flag visibility to interceptors.** `QueryContext.flags` exposes
only the **public** flag set (`withDeleted`, `onlyDeleted` in V1).
Internal flags (`internalSystemQuery`) are stripped before the
context reaches application or global interceptors — the
package-private storage backs a separate `internalFlags` field that
the framework reads directly and never exposes through the
`QueryContext` data class. Application interceptors physically
cannot observe `internalSystemQuery`, so they cannot react to it
even by accident.

Application interceptors *can* inspect public flags (the set is
visible by design — it's the call-site API), but the framework
makes no guarantee that any flag actually does anything outside the
specific interceptor that documents honoring it. `withDeleted` /
`onlyDeleted` are *only* honored by the generated soft-delete
interceptor; an application interceptor that reads
`context.flags.contains(QueryFlag.withDeleted)` and changes its
behavior is technically allowed but is not a supported extension
point — the soft-delete contract is "soft-delete interceptor honors
it; everyone else ignores it."

A future RFC can add a real per-interceptor flag-declaration
mechanism (e.g. an `honors: Set<QueryFlag>` declared at
registration) if we need application-level flag honoring with a
clear contract; deferred until a concrete use case lands.

## Ordering

Interceptor order is deterministic and runs in this order:

1. generated framework interceptors from schema features (e.g. soft-delete)
2. application `QueryInterceptor<E>` (entity-scoped) interceptors, in
   registration order
3. application `GlobalQueryInterceptor` interceptors, in registration order

Application global interceptors run **last** so a hard outer policy can
inspect the final query — including framework-added soft-delete predicates,
entity-interceptor tenant scoping, etc. — and reject or clamp based on the
whole picture. This is the inverse of the "global runs first" alternative;
choosing "global last" makes outer policies strictly more powerful (they see
everything every other interceptor produced).

The applied order must be visible in explain output, including the
interceptor name and the operations it performed on the scope (added
predicates, limit clamps, annotations, rejections).

## Privacy Semantics

Interceptors run before storage reads. LOAD privacy still runs after rows are
materialized.

This means interceptors can only reduce or reject the candidate query set.
They must not be described as authorization. A row that passes tenant and
soft-delete interceptors can still be denied by privacy.

## Dependency On Typed Query Scopes

This RFC **depends on**
[Phantom-Typed Query Scopes](phantom-typed-query-scopes.md). V1 ships once
typed predicates land — the `Predicate<E>` references in `QuerySpec` and
`InterceptScope` are the phantom-typed surface. A `QueryInterceptor<User>`
can only add `Predicate<User>` values to a `User` query; the compiler
rejects predicates against other entities. That keeps interceptor code
aligned with the same query-scope safety as normal call-site queries.

There is no interim untyped shape — building an untyped interceptor API
first and migrating later would create churn for early adopters and break
the safety claim above.

## Implementation Notes

**Interceptor execution and the result-variant catch chain.** The
generated read `*OrError` paths wrap their body in
`try { ... } catch (Exception) { Err(classifyDriverError(...)) }`
(plus the dedicated `PrivacyDeniedException` arm). Per the Result
Variants RFC, `classifyDriverError` re-throws unrecognized non-
`SQLException` throwables — so an `IllegalStateException` /
`NullPointerException` / vanilla `RuntimeException` from an
interceptor's `intercept(...)` correctly escapes the `*OrError`
catch and propagates to the caller. The "non-reject interceptor
exceptions propagate unchanged" contract holds *because of* the
`classifyDriverError` narrowing, not in spite of it.

Implementation must NOT short-circuit `classifyDriverError` for
interceptor exceptions (e.g. by catching `Exception` and wrapping
directly to `Err(DriverFailure)`); a naive `catch (Exception)
{ Err(...) }` would silently break the contract and bury
application bugs as `DriverFailure`. The canonical shape is:

```kotlin
return try {
    val spec = applyInterceptors(builder, context)  // may throw or reject
    driver.query(spec)  // SQLException family lands here
} catch (e: EntQueryRejectedException) {
    Err(e.error)  // reject(...) reached here as a typed throw
} catch (e: PrivacyDeniedException) {
    Err(EntError.PrivacyDenied(...))
} catch (e: Exception) {
    Err(classifyDriverError(driver, e, entity, operation))  // narrowed
}
```

`scope.reject(...)` lowers to `throw EntQueryRejectedException(...)`
which the dedicated arm catches. Application interceptor bugs
(non-EntException, non-reject) hit the final `Exception` arm and
re-throw via `classifyDriverError` because they're not in the
`SQLException` family. Two separate channels, one catch chain.

## Explain Interaction

Explain methods run the interceptor chain in **dry-run mode**
against the same `QuerySpec` the terminal they model would have
built. The returned `QueryPlan` reflects every interceptor
mutation (added predicates, clamped limits, annotations) in apply
order so the caller can see what the driver *would* have received.

The exact explain surface, one method per *terminal-API name* —
the explain mirror keeps the source name verbatim
(`explainFirstVisibleOrNull`, not `explainFirstVisible`) so
discovery via "type `explain` + the terminal name I'd call" is
trivial:

```kotlin
// Row-shaped reads (one explain per source-side variant).
query.explainAllOrThrow(): QueryPlan
query.explainAllOrError(): QueryPlan
query.explainVisibleAll(): QueryPlan
query.explainVisibleAllOrError(): QueryPlan

query.explainFirstOrThrow(): QueryPlan
query.explainFirstOrNull(): QueryPlan
query.explainFirstOrError(): QueryPlan
query.explainFirstVisibleOrNull(): QueryPlan

// Aggregate reads.
query.explainRawCount(): QueryPlan
query.explainVisibleCount(): QueryPlan
query.explainRawExists(): QueryPlan
query.explainVisibleExists(): QueryPlan

// Repo-level explains for by-id reads (one per source variant).
client.users.explainByIdOrThrow(id): QueryPlan
client.users.explainByIdOrNull(id): QueryPlan
client.users.explainVisibleByIdOrNull(id): QueryPlan
client.users.explainByIdOrError(id): QueryPlan
```

All explain variants for a given operation produce the same
underlying `QueryPlan` — the differences between *OrThrow /
*OrNull / *OrError live in the *result wrap* the terminal would
apply, and explain dry-runs without that wrap. Keeping per-
variant names makes the call-site mirroring intuitive at the cost
of mild API surface duplication.

**Explain never returns `EntResult` and never wraps execution
errors.** The contract is uniform across the family regardless of
which terminal variant the name mirrors:

- Every `explain*` method returns `QueryPlan` directly. There is
  no `EntResult<QueryPlan>` shape and no `explain*OrError` /
  `explain*OrThrow` *wrap* — the result-shape suffix in the name
  is purely for call-site discoverability.
- Execution errors that the terminal would surface (driver
  failures, classifier-recognized constraint violations,
  `Err(DriverFailure)` / `Err(ConstraintViolation)` / etc.) do
  NOT appear on explain — explain doesn't call the driver. Only
  *interceptor* outcomes (rejection metadata, added predicates,
  limit clamps, annotations) land on the plan.
- Non-reject interceptor exceptions still propagate from explain
  unchanged per the "Non-reject interceptor exceptions" rule —
  explain is not a swallow-all wrapper.

Rejection metadata appears on the plan when applicable (see
below). Callers branching on rejection use `plan.rejected` /
`plan.requireNotRejected()`.

**Reject behavior.** If an interceptor calls `scope.reject(...)`
during an explain, the returned `QueryPlan` carries rejection
metadata (`rejected = true`, `reason`, `code`, `interceptor`)
and **no driver subplan** — explain does NOT throw. The rationale:
explain is the obvious debugging tool for "why did this query
reject?", and throwing from explain would force a try/catch around
every debug call. Callers that want exception-style explain can
chain `plan.requireNotRejected()` or branch on
`plan.rejected`.

**Non-reject interceptor exception behavior.** Application bugs
in interceptor code (anything other than `scope.reject(...)`)
propagate from explain unchanged, same as in the terminal path
— `IllegalStateException` from a hook bug, `NullPointerException`
from misuse, etc. all escape `explain()` directly. Explain is not
a "swallow exceptions" wrapper; only `reject(...)` produces
structured rejection metadata.

**Operation mapping.** Explain methods use the same
`QueryContext.operation` as the terminal they model — e.g.
`query.explainAll()` runs interceptors with `operation = ALL`,
`query.explainVisibleCount()` with `operation = VISIBLE_COUNT`,
etc. The `ReadOperation → EntOperation` mapping in
"Rejection Semantics" applies identically.

**Short-circuit.** The chain-short-circuit rule from
"Rejection Semantics" applies in explain too: once an interceptor
rejects, no subsequent interceptor runs (in dry-run mode or
otherwise). The explain output records interceptors up to and
including the rejecting one.

## Test Requirements

Before implementation, add tests for:

- entity interceptor adds a predicate before driver execution
- interceptor-added predicates are combined with caller predicates (AND-ed)
- interceptors cannot remove caller predicates, change ordering, raise a
  limit, or change the table through `InterceptScope` (compile-time enforced;
  the public API has no such operations)
- interceptor order is deterministic: framework → entity → global, with global
  last
- soft-delete flags (`withDeleted`, `onlyDeleted`) override only the
  soft-delete interceptor behavior
- max-limit interceptor rejects or clamps according to configuration; default
  is reject
- `setDefaultLimitIfAbsent` is a no-op when a caller or prior interceptor has
  set a limit; `requireLimitAtMost` clamps down but cannot raise
- all three limit operations (`setDefaultLimitIfAbsent`, `requireLimitAtMost`,
  `rejectIfLimitGreaterThan`) are silent no-ops for `BY_ID`, `FIRST`,
  `RAW_COUNT`, `VISIBLE_COUNT`, `RAW_EXISTS`, `VISIBLE_EXISTS`, `EAGER_LOAD`,
  and `EDGE_PREDICATE` operations — regardless of whether a prior limit
  is in place — per the Limit-semantics-by-read-shape rules
- limit operations apply normally for `ALL` and `EDGE_TRAVERSAL`
  operations: `setDefaultLimitIfAbsent` sets a limit when absent,
  `requireLimitAtMost` clamps down, `rejectIfLimitGreaterThan` rejects
  with `QueryRejected.code == "max_limit_exceeded"`
- `addAnnotation` appears in explain / observability output but does not
  affect the query plan
- LOAD privacy still runs after intercepted reads
- explain output shows applied interceptors, added predicates, limit clamps,
  annotations, and rejections in apply order

- **`QueryContext.flags` exposes only public flags.** An
  application interceptor cannot observe the internal
  `internalSystemQuery` flag through the context (it lives in a
  separate package-private field). Test by constructing a query
  internally with the flag set and asserting that an inspecting
  application interceptor sees an empty / public-only `flags` set.

- **Non-reject interceptor exceptions propagate unchanged.** If an
  interceptor throws `IllegalStateException` / `NullPointerException`
  / vanilla `RuntimeException` from `intercept(...)` (i.e. NOT via
  `scope.reject(...)`), the exception escapes through both *OrThrow
  and *OrError unchanged — NOT wrapped as `Err(DriverFailure)`,
  `Err(QueryRejected)`, or any other structured error. Same
  "fail loud on bugs" stance as `classifyDriverError`.

- **Mandatory unique interceptor names.** Registering two
  application interceptors with the same name (either two
  per-entity registrations on the same entity, or two `global(...)`
  registrations) fails at `EntClient` construction time with a
  clear error. Anonymous-class interceptors registered without a
  `name` also fail at construction (no simpleName /
  AnonymousInterceptor fallback for application registrations).
  Framework-owned interceptors (installed via schema mixins) are
  exempt — their names are pre-assigned by the framework.

- **Rejection short-circuits the chain.** Given three interceptors
  A → B → C where B calls `scope.reject(...)`, only A and B run
  (B sees the partial state from A; C never runs). The explain
  output records A and B with their respective contributions and
  B as the rejecting interceptor; C does not appear at all.

- **Caller-set `limit` / `offset` preserved on
  `visibleCount` / `visibleExists` (and `*OrError` variants).**
  Interceptor limit operations
  (`setDefaultLimitIfAbsent` / `requireLimitAtMost` /
  `rejectIfLimitGreaterThan`) are silent no-ops on these shapes,
  but a caller-authored `query { limit(N) }.visibleCount()` still
  honors `N` per the terminal API's normal semantics — pin both
  directions in the same test.

- **Multi-step traversal chains apply per-entity interceptors at
  each step.** Given a chain like
  `client.users.query().queryGroups().queryPosts().allOrThrow()`,
  assert that User's interceptors fire on the root user query,
  Group's interceptors fire on the bridging group query (with
  `sourceEntity = User`, `edgeName = "groups"`,
  `path = [User→groups→Group]`), and Post's interceptors fire on
  the terminal post query (with `sourceEntity = Group`,
  `edgeName = "posts"`, `path.size == 2`). Tenant-scoping or
  soft-delete added on each entity must constrain each respective
  step — a row filtered out at step 2 (groups) must not appear in
  step 3's `sourceEntity` set.

- **InterceptScope.shape exposes the effective query state.**
  An interceptor can read `scope.shape.predicates`,
  `scope.shape.limit`, `scope.shape.annotations`, etc. and branch
  on them. Mutations from earlier interceptors in the chain are
  reflected — predicates added by interceptor #1 appear in
  interceptor #2's `shape.predicates`. The view is read-only;
  attempts to mutate the returned list / map have no effect on
  the underlying spec.

- **Same-interceptor read-after-write sees own mutations.**
  Within a single interceptor's `intercept(...)` call, calling
  `scope.addPredicate(X)` and then reading `scope.shape.predicates`
  must show X — the property accessor re-derives. Similarly
  `scope.addAnnotation("k", "v")` then `scope.shape.annotations`
  must include `"k" → "v"`, and `scope.requireLimitAtMost(50)`
  then `scope.shape.limit` must reflect the clamp. But capturing
  the result into a local before the mutation freezes a snapshot:
  `val snap = scope.shape; scope.addPredicate(X); snap.predicates`
  must NOT contain X (the local was captured pre-mutation). Pin
  both sides — `scope.shape.predicates` post-mutation contains X;
  the captured local does not — in the same test.

- **Predicate attribution metadata is correct across the chain.**
  Pin that `shape.callerPredicateCount` reflects only
  caller-authored predicates (`query { where(...) }`) and
  `shape.interceptorPredicateCount` increases by one for each
  prior `scope.addPredicate(...)` call. Given:
  caller adds 2 predicates → framework soft-delete adds 1 → entity
  interceptor A adds 1 → entity interceptor B is now running and
  sees `callerPredicateCount = 2`, `structuralPredicateCount = 0`,
  `interceptorPredicateCount = 2`, `predicates.size = 4`,
  `hasCallerPredicates = true`. After B adds its own predicate,
  the next interceptor in the chain sees
  `interceptorPredicateCount = 3`. The same accounting applies to
  `UntypedQueryShape` for global interceptors.

- **Structural predicates are counted in the structural bucket,
  not folded into caller or interceptor.** Pin a `byIdOrError(42)`
  read where the generated code adds a structural `id = 42`
  predicate before any interceptor runs. With no caller
  predicates and one structural predicate, the first interceptor
  in the chain sees `callerPredicateCount = 0`,
  `structuralPredicateCount = 1`, `interceptorPredicateCount = 0`,
  `predicates.size = 1`, `hasCallerPredicates = false`. Same pin
  for an edge traversal like
  `client.users.query().queryPosts().allOrThrow()` — the bridging
  read on `Post` carries a structural source-id constraint that
  must appear in `structuralPredicateCount` (not caller / not
  interceptor). The identity
  `caller + structural + interceptor == predicates.size` must
  hold on every read shape.

- **`callerLimit` reflects caller-authored limit only.** Pin a
  case where the caller writes `query { limit(50) }`: the first
  interceptor sees `shape.callerLimit = 50` AND `shape.limit = 50`.
  Then pin a case where the caller writes `query { ... }` (no
  limit) and a framework interceptor calls
  `setDefaultLimitIfAbsent(100)`: the next interceptor sees
  `shape.callerLimit = null` AND `shape.limit = 100`. The
  "public callers must set a limit" policy branches on
  `callerLimit == null`, not on `limit == null` (which would be
  defeated by the default).

- **GlobalInterceptScope.shape uses the erased UntypedQueryShape.**
  A global interceptor sees `predicateCount`, `hasCallerPredicates`,
  `hasInterceptorPredicates`, `limit`, `offset`, `hasOrderBy`,
  `annotations`, plus `entity: KClass<*>` and `table: String`.
  Pin the example `RejectUnscopedAggregates` from the RFC: a
  `rawCount()` with zero **caller** predicates rejects as
  `Err(QueryRejected(code = "broad_aggregate"))`. Soft-delete
  installed on the entity adds a non-caller predicate to
  `predicateCount`, so the example must branch on
  `!hasCallerPredicates`, not `predicateCount == 0`. Pin both
  directions in the same test (rejects when caller adds no
  `where(...)`; passes when caller adds at least one).

- **Explain runs interceptors in dry-run mode and surfaces
  rejection metadata without throwing.** `query.explainAll()` on a
  query that an interceptor would reject returns a `QueryPlan` with
  `rejected = true`, `reason`, `code`, and `interceptor` populated;
  the explain call does NOT throw. Non-reject interceptor
  exceptions DO propagate from explain unchanged.

- **`framework:` prefix is reserved.** Registering an application
  interceptor with `name = "framework:foo"` fails at `EntClient`
  construction with a clear error.

- **Per-terminal-call spec isolation.** `val q = client.posts.query
  { ... }; q.allOrThrow(); q.allOrThrow()` — interceptor-added
  predicates / clamped limits / annotations do NOT compound across
  the two terminal calls. Each call sees the same fresh starting
  spec.

- **Edge-predicate existence semantics under soft-delete.** With
  `softDelete()` installed on `User`, `client.posts.query
  { where(Post.author.has()) }.allOrThrow()` returns only posts
  whose author is NOT soft-deleted. Without the soft-delete
  installation, the same query returns posts whose author row
  physically exists regardless of `deletedAt`. Pin both
  directions in one test pair.

- **Interceptor limit argument validation.** Negative arguments to
  `requireLimitAtMost(-1)` / `setDefaultLimitIfAbsent(-1)` /
  `rejectIfLimitGreaterThan(-1, ...)` fail with
  `IllegalArgumentException`. Zero is allowed.

- **Interceptors cannot shape `offset`.** `InterceptScope` has no
  `setOffset` / `requireOffsetAtMost` method; the caller's offset
  is preserved unchanged through the interceptor chain. Pin by
  calling `query { offset(50) }.allOrThrow()` with an interceptor
  that wants to clamp offset; the offset must still reach the
  driver as 50.
- every documented read surface invokes interceptors: `byIdOrThrow`,
  `byIdOrNull`, `visibleByIdOrNull`, `byIdOrError`, `firstOrThrow`,
  `firstOrNull`, `firstVisibleOrNull`, `firstOrError`, `allOrThrow`,
  `allOrError`, `visibleAll`, `visibleAllOrError`, `rawCount`,
  `rawCountOrError`, `visibleCount`, `visibleCountOrError`, `rawExists`,
  `visibleExists`, `rawExistsOrError`, `visibleExistsOrError`, generated
  edge-traversal `query*()` methods, and edge predicates `has` / `hasWhere`
- eager-load subqueries (`.with { ... }`) invoke interceptors on the target
  entity with `context.isEagerSubquery == true`
- `has` / `hasWhere` edge predicates invoke interceptors on the target entity
- rejection mapping: `*OrThrow` throws `EntQueryRejectedException`,
  `*OrError` returns `Err(EntError.QueryRejected)`, `*OrNull` and `visible*`
  throw (they do not collapse rejection to `null`)
- `QueryRejected.code` carries the optional machine-readable code (e.g.
  `"max_limit_exceeded"`); `QueryRejected.interceptor` is the mandatory
  registration name for application interceptors or the framework-
  assigned stable name (e.g. `"framework:soft-delete"`) for
  framework-owned interceptors. Both registration paths require a
  name, so there is no simpleName / AnonymousInterceptor fallback.
  Callers and tests can branch on these fields independently of
  `reason` message text
- `rejectIfLimitGreaterThan(...)` produces rejections with
  `code == "max_limit_exceeded"` automatically
- `internalSystemQuery` has no effect in V1 — it is a reserved capability
  with no framework or application interceptor opt-ins. Soft-delete, tenant
  scoping, max-limit, and every other registered interceptor runs on every
  read regardless of the flag. A future framework interceptor opting in
  must be tested separately.
- application code cannot set `internalSystemQuery` (compile-time / API-level
  restriction)
- `QueryContext` is read-only — interceptors have no framework-provided
  `client` / driver / transaction access in V1. Reads through a
  constructor-captured client are documented as unsafe (the framework
  cannot statically prevent them; a future runtime recursion guard may
  detect and reject them)
