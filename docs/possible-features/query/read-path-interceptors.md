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
    /** Adds a predicate that is AND-ed with caller and prior-interceptor
     *  predicates. Cannot remove existing predicates. */
    fun addPredicate(predicate: Predicate<E>)

    /** Clamps the effective limit to at most [max] on read shapes where
     *  limit operations apply. If no limit is present, sets [max]. If a
     *  smaller limit is already in place (caller or prior interceptor),
     *  keeps it. */
    fun requireLimitAtMost(max: Int)

    /** Sets a default limit on read shapes where limit operations
     *  apply (`ALL`, `EDGE_TRAVERSAL`). No-op if a limit is already
     *  in place, AND a silent no-op on read shapes where row limits
     *  have no meaning (`BY_ID`, `FIRST`, `RAW_COUNT`,
     *  `VISIBLE_COUNT`, `RAW_EXISTS`, `VISIBLE_EXISTS`,
     *  `EAGER_LOAD`, `EDGE_PREDICATE`). See
     *  "Limit semantics by read shape" for the full table. */
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
     *  The framework sets `QueryRejected.code = "max_limit_exceeded"`
     *  on rejections triggered by this method, so callers and tests can
     *  branch on the code without parsing [reason]. */
    fun rejectIfLimitGreaterThan(max: Int, reason: () -> String)

    /** Attaches a key/value annotation for diagnostics; surfaces in
     *  explain/observability output. Does not affect the query plan.
     *  Duplicate keys across interceptors use last-writer-wins per the
     *  framework → entity → global ordering. */
    fun addAnnotation(key: String, value: String)

    /** Rejects the query with the given reason. The framework converts
     *  this into the per-API outcome described in "Rejection Semantics".
     *
     *  [code] is an optional stable machine-readable identifier (e.g.
     *  `"max_limit_exceeded"`, `"missing_tenant_scope"`) that callers
     *  and tests can branch on independently of [reason] message text.
     *  The framework records the rejecting interceptor's identity on
     *  `QueryRejected.interceptor` automatically: the stable registration
     *  name passed at registration time (preferred), the interceptor
     *  class's `simpleName` if no name was registered, or
     *  `"AnonymousInterceptor"` if neither is available (anonymous-class
     *  interceptors with no registered name). Callers that branch on
     *  `interceptor` in tests should register interceptors with explicit
     *  names. */
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
`InterceptScope<E>`. Framework-owned interceptors (soft-delete, generated
schema features) may operate on a private `QuerySpec` mutator path marked
with an internal annotation; that path is not part of the public API and is
reserved for generated code. Framework mutators must preserve the same
reduce-or-reject invariant the public `InterceptScope` enforces — adding
predicates, clamping/setting/rejecting limits, attaching annotations, or
rejecting the query — unless the specific schema feature documents and
justifies a wider operation (e.g. a future RFC for write-through
interceptors). A framework interceptor that silently removes caller
predicates or raises a caller-set limit is a bug.

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
    fun requireLimitAtMost(max: Int)
    fun setDefaultLimitIfAbsent(default: Int)
    fun rejectIfLimitGreaterThan(max: Int, reason: () -> String)
    fun addAnnotation(key: String, value: String)
    fun reject(reason: String, code: String? = null): Nothing
    // No addPredicate — entity-typing not available globally.
}
```

`GlobalInterceptScope` omits `addPredicate` because predicates require a
typed entity context; a single global interceptor can't safely add
`Predicate<E>` when `E` varies per registered entity. Global interceptors
operate on operations that are entity-agnostic (limit, annotate, reject).
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
  interceptors.
- edge predicates: `has`, `hasWhere` on the target entity

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

`rawCountOrError` preserves `rawCount` semantics: it is a driver-side aggregate
that skips LOAD privacy and returns `Err(QueryRejected)` only if an interceptor
rejects the read (or another structured driver/query failure occurs).
`visibleCountOrError` preserves `visibleCount` semantics: it materializes rows,
applies LOAD privacy, and returns the visible count as `Ok(count)` unless a
structured failure occurs. There are no `*OrNull` count variants — zero is the
natural expected-absence result.

### Rejection Semantics

`scope.reject(reason)` aborts the query before driver execution. The framework
converts this into a per-API outcome:

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
    val code: String? = null,           // stable machine-readable code, e.g. "max_limit_exceeded"
    val interceptor: String? = null,    // stable registration name (preferred) or simpleName fallback; set by the framework
    override val message: String = reason,
) : EntError

class EntQueryRejectedException(
    override val error: EntError.QueryRejected,
) : EntException(error)
```

Add these alongside the existing variants in the
[Result Variants RFC](../tooling/entkt-result-variants-rfc.md).

## Generated Registration

Interceptors can be registered per entity or globally (across all
entities) via the same DSL block. `global(...)` runs the interceptor
on every read regardless of root entity:

```kotlin
val client = EntClient(
    driver = driver,
    interceptors = EntInterceptors {
        // Per-entity: `TenantReadInterceptor` shapes Post queries only.
        posts(TenantReadInterceptor(Post.tenantId), name = "tenant-scope")
        users(MaxLimitInterceptor<User>(defaultLimit = 100, maxLimit = 500), name = "user-max-limit")

        // Global: runs on every entity's reads. The interceptor
        // is a `GlobalQueryInterceptor` (entity-agnostic interface,
        // see Global Interceptors section above).
        global(EnforceMaxLimit(maxLimit = 1000), name = "global-max-limit")
        global(QueryTracer(), name = "tracer")
    },
)
```

The `name` parameter is mandatory and must be unique within its
scope (per-entity names are scoped to the entity; `global(...)`
names share a single namespace across all globals). The name
surfaces verbatim on `QueryRejected.interceptor`, so a registration
of `global(EnforceMaxLimit(...), name = "global-max-limit")` that
rejects a query produces `Err(QueryRejected(... interceptor =
"global-max-limit" ...))`. Stable names matter for telemetry,
debugging, and test assertions.

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
  read regardless of this flag. In particular, eager-loaded subqueries do
  **not** bypass soft-delete: a visible `Post` eager-loading `Author` is a
  parent-target relationship across different entities, and the parent's
  soft-delete status says nothing about the target's `Author.deletedAt`.
  Soft-delete runs unconditionally on the target. The flag exists so
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
  `"max_limit_exceeded"`); `QueryRejected.interceptor` follows a fallback
  chain — the stable registration name passed at registration time
  (preferred), the interceptor class's `simpleName` if no name was
  registered, or `"AnonymousInterceptor"` if neither is available. Callers
  and tests can branch on these fields independently of `reason` message
  text; tests that branch on `interceptor` should register interceptors
  with explicit `name = "..."` to avoid coupling to refactor-fragile
  `simpleName` values
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
