# Queries

The generated `{Entity}Query` builder provides a type-safe API for
filtering, ordering, paginating, traversing edges, and eager loading
related entities.

## Basic Usage

```kotlin
val users = client.users.query {
    where(User.active eq true)
    orderBy(User.name.asc())
    limit(10)
    offset(20)
}.allOrThrow()
```

`.allOrThrow()` returns a `List<User>`. Use `.firstOrNull()` for single results,
`.visibleCount()` for a privacy-aware count, `.rawCount()` for a fast
aggregate count, or `.rawExists()` / `.visibleExists()` to check whether
a match exists (raw skips LOAD privacy; visible applies it).

## Indexed Query Helpers

When an entity declares indexes, the generated repo exposes an `indexes`
namespace of staged, type-safe builders that make the index-friendly read
paths discoverable from IDE completion. Each helper just seeds the indexed
predicate and hands back the **normal** query builder, so privacy, read
interceptors, eager loading, ordering, and the terminal choices are all
unchanged.

Given:

```kotlin
class Post : EntSchema("posts") {
    override fun id() = EntId.long()
    val authorId = long("author_id")
    val createdAt = time("created_at")
    val byAuthorCreated = index("idx_posts_author_created", authorId, createdAt)
}
```

a composite index generates one method per **valid left prefix** — each
stage offers only the next indexed column, which keeps you on the index:

```kotlin
// equivalent to query { where(Post.authorId eq userId) }
client.posts.indexes
    .authorId(userId)
    .query()
    .allOrThrow()

// equivalent to query { where(Post.authorId eq userId); where(Post.createdAt eq t) }
client.posts.indexes
    .authorId(userId)
    .createdAt(t)
    .query()
```

`query { ... }` accepts the same DSL as `client.posts.query { ... }`;
extra `where(...)` predicates are AND'd with the seeded indexed prefix,
and ordering / pagination / eager loading / terminals all stay available:

```kotlin
client.posts.indexes
    .authorId(userId)
    .query {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
        limit(20)
    }
    .allOrError()
```

### Range blocks

The next comparable indexed column after an equality prefix
(string/text, numeric, or time) also gets a range-block overload. A range
block adds at least one bound and at most one lower (`gt`/`gte`) and one
upper (`lt`/`lte`); a range stage ends the chain (it exposes only
`query()`):

```kotlin
client.posts.indexes
    .authorId(userId)
    .createdAt { gte(since); lt(until) }
    .query { orderBy(Post.createdAt.desc()) }
    .allOrError()
```

### Unique terminals

When a bound prefix exactly matches a non-nullable **unique** index, the
stage exposes single-row terminals instead of (well, alongside) `query()`:

```kotlin
client.users.indexes.email("a@example.com").orNull()        // User? — miss is null, denial throws
client.users.indexes.email("a@example.com").visibleOrNull() // miss OR denial → null
client.users.indexes.email("a@example.com").orError()       // EntResult<User> — miss is Err(NotFound, QUERY)
client.users.indexes.email("a@example.com").orThrow()       // User, or a structured exception
```

A composite unique index exposes the terminals only at its full stage —
a partial prefix exposes `query()` but not `orNull()` and friends.

### What generates a helper

Helpers come from explicit `index(...)` declarations and the unique
indexes synthesized by `field.unique()` / `belongsTo(...).unique()`.
Method names are the generated column-ref property names (e.g.
`author_id` → `authorId`), never the storage column name. V1 helper
parameters are non-null, so to query a `NULL` indexed value use the
normal `query { where(col.isNull()) }`. The generator skips
raw-SQL partial indexes, native / non-btree indexes (e.g. pgvector
HNSW/IVFFlat), and indexes over btree-incompatible columns (JSON,
pgvector, bytes). See the design note:
[Indexed Query Helpers](implemented-features/query/indexed-query-helpers.md).

## Predicates

Predicates are built from the typed column references on the entity's
companion object. Each column type exposes operators appropriate for its
type.

### Comparison Operators

Available on all column types:

```kotlin
User.name eq "Alice"        // equal
User.name neq "Bob"         // not equal
User.name `in` listOf("Alice", "Bob")   // IN
User.name notIn listOf("Charlie")       // NOT IN
```

Available on typed enum columns:

```kotlin
Ticket.priority eq Priority.HIGH          // passes the enum's .name to the driver
Ticket.priority `in` listOf(Priority.LOW, Priority.MEDIUM)
```

Available on comparable columns (`Int`, `Long`, `Float`, `Double`, `Instant`):

```kotlin
User.age gt 18              // greater than
User.age gte 18             // greater than or equal
User.age lt 65              // less than
User.age lte 65             // less than or equal
```

Available on string columns:

```kotlin
User.email contains "example"       // LIKE '%example%'
User.email hasPrefix "alice"        // LIKE 'alice%'
User.email hasSuffix "@test.com"    // LIKE '%@test.com'
```

Available on nullable columns:

```kotlin
User.age.isNull()           // IS NULL
User.age.isNotNull()        // IS NOT NULL
```

### Compound Predicates

Combine predicates with `and` / `or`:

```kotlin
client.users.query {
    where(
        (User.active eq true) and (User.age gte 18)
    )
}

client.users.query {
    where(
        (User.age gte 65) or (User.email hasSuffix "@admin.example.com")
    )
}
```

Parentheses control precedence naturally since `and` / `or` are infix
functions that return `Predicate` values.

### Chaining `where()`

Multiple `where()` calls accumulate predicates -- they are AND'd together
at query time:

```kotlin
client.users.query {
    where(User.active eq true)
    where(User.age gte 18)
    // Equivalent to: active = true AND age >= 18
}
```

## Ordering

Use the `.asc()` and `.desc()` methods on column references:

```kotlin
client.users.query {
    orderBy(User.age.desc())
    orderBy(User.name.asc())
}
```

Multiple `orderBy()` calls add successive sort keys.

### Vector distance ordering (pgvector)

For `pgvector` columns (see
[Schema -> Native Column Types](02-schema.md#native-column-types-postgres-pgvector)),
order by distance to a query vector for nearest-neighbor search. Import
`entkt.postgres.vector.*` for the distance helpers:

```kotlin
import entkt.postgres.vector.*

val q = PgVector.of(embeddingModel.embed("kotlin orm"))

client.articles.query {
    orderBy(Article.embedding.cosineDistance(q).asc())   // nearest first
    limit(20)
}
```

- `cosineDistance(q)` / `l2Distance(q)` / `innerProduct(q)` lower to pgvector's
  `<=>` / `<->` / `<#>` operators. The query vector is bound as a parameter, never
  inlined into the SQL.
- Use `.asc()` for most-similar-first -- this holds for `innerProduct` too, since
  pgvector's `<#>` is the *negative* inner product. `.desc()` is farthest-first.
- Rows with a null embedding sort last in both directions.
- The query vector's dimensions must match the column or you get a field-named
  error; a non-Postgres driver rejects distance ordering with a capability error.

Vector columns also support exact-match `eq` / `neq` / `in` / `notIn`, though
similarity ordering is the typical access path.

## Pagination

```kotlin
client.users.query {
    limit(10)
    offset(20)
}
```

Both reject negative values at the call. `limit(0)` is legal and means
what it says — no rows — on every terminal that reads rows, including
the single-result ones:

```kotlin
client.users.query { limit(0) }.firstOrNull()   // → null
client.users.query { limit(0) }.rawExists()     // → false
client.users.query { limit(0) }.allOrThrow()    // → []
client.users.query { limit(0) }.visibleCount()  // → 0
```

Note that `limit(n)` above 1 doesn't change what a first-row terminal
returns — it already fetches a single row.

The exceptions are the terminals that never materialize rows in the
first place: `rawCount()` and the raw aggregates ignore `orderBy`,
`limit`, and `offset` entirely, so `limit(0)` doesn't apply to them
either. `visibleCount()` is not an exception — it materializes rows to
evaluate privacy, so it respects the bound like any other row read.

## Count and Exists

### `visibleCount()` -- privacy-aware count

Materializes matching rows, evaluates LOAD privacy on each, and returns
the count of allowed entities. Denied entities are silently excluded.
Respects `limit` and `offset`.

```kotlin
val visibleActiveUsers = client.users.query {
    where(User.active eq true)
}.visibleCount()  // → Long
```

### `rawCount()` -- fast aggregate count

Uses `SELECT COUNT(*)` without materializing rows. Does **not** evaluate
LOAD privacy, so it may count rows the viewer cannot read. Ignores
`orderBy`, `limit`, and `offset`.

Because they skip LOAD privacy, `rawCount` / `rawExists` and the raw
aggregates are unavailable inside **privacy rules**: rule reads are
viewer-scoped, and a privacy-bypassing probe could leak invisible rows
into an authorization decision. Calling one there throws
`IllegalStateException` — use a LOAD-checked terminal (`firstOrNull`,
`allOrThrow`, the `visible*` family) instead. Validation rules keep
them: validation reads run under `PrivacyBypass`, where raw and
visible coincide. See
[Privacy → Operation Contexts](06-privacy.md#operation-contexts).

```kotlin
val totalActiveUsers = client.users.query {
    where(User.active eq true)
}.rawCount()  // → Long
```

### `rawExists()` -- fast existence check, skips privacy

Returns `true` iff at least one storage row matches the predicate. Skips
LOAD privacy entirely — useful for "does this row exist at all?" checks
(uniqueness checks before insert, idempotency keys, etc.) where privacy
of the caller is not the relevant question.

```kotlin
val emailTaken = client.users.query {
    where(User.email eq "alice@example.com")
}.rawExists()  // → Boolean
```

### `visibleExists()` -- privacy-aware existence check

Returns `true` iff at least one storage row matches the predicate **and**
the current viewer can LOAD it. Scans storage order, bounded by
`EntClientConfig.visibleOverfetchLimit`, and returns `true` on the first
visible row. Cap-exhausted-with-no-visible silently returns `false`.

```kotlin
val hasAdmins = client.users.query {
    where(User.role eq "admin")
}.visibleExists()  // → Boolean
```

The legacy `exists()` that fetched one row and threw
`PrivacyDeniedException` when it was denied has been removed — neither
"any row exists?" nor "row I can see exists?" was the answer you got,
which surprised callers. Pick `rawExists` for the privacy-skipping
existence probe or `visibleExists` for the privacy-aware variant.

### `*OrError` variants

Each aggregate has a structured-result counterpart that maps each
failure surface (interceptor rejection, driver failure) into an
`EntError` variant instead of throwing:

| Throwing | Result variant |
|---|---|
| `rawCount(): Long` | `rawCountOrError(): EntResult<Long>` |
| `visibleCount(): Long` | `visibleCountOrError(): EntResult<Long>` |
| `rawExists(): Boolean` | `rawExistsOrError(): EntResult<Boolean>` |
| `visibleExists(): Boolean` | `visibleExistsOrError(): EntResult<Boolean>` |

```kotlin
when (val result = client.posts.query().rawCountOrError()) {
    is EntResult.Ok -> println("count: ${result.value}")
    is EntResult.Err -> when (val err = result.error) {
        is EntError.QueryRejected -> println("rejected by ${err.interceptor}")
        is EntError.DriverFailure -> println("driver failure: ${err.message}")
        else -> {}
    }
}
```

`visibleCountOrError` additionally surfaces eager-edge LOAD denial
as `Err(PrivacyDenied)` (same shape as `allOrError`). The raw
variants intentionally bypass LOAD privacy and have no PrivacyDenied
surface.

## Aggregations

Beyond count, each query exposes single-metric **raw** aggregate terminals —
`min`, `max`, `sum`, `avg` — computed in the database over the query's
predicates. Like `rawCount`, they bypass LOAD privacy (hence the `raw` prefix)
and ignore `orderBy` / `limit` / `offset`. Each terminal computes one metric,
optionally grouped by one column.

### Ungrouped — a typed scalar

The return type follows the column you pass:

```kotlin
val orders = client.orders.query { where(Order.status eq Status.SHIPPED) }

val latest:  Instant? = orders.rawMax(Order.placedAt)   // min/max → the column's type
val units:   Long?    = orders.rawSum(Order.quantity)   // sum of an integer column → Long?
val revenue: Double?  = orders.rawSum(Order.price)      // sum of a floating column → Double?
val avgLine: Double?  = orders.rawAvg(Order.price)      // avg is always Double?
```

`min`/`max` accept any comparable column; `sum`/`avg` accept only numeric
columns — both are enforced at compile time. An ungrouped metric over an empty
match is `null` (only `rawCount()` returns `0`).

### Grouped — a list of buckets

`raw…By(groupColumn[, metricColumn])` returns `List<AggregateBucket<K, V>>`, one
bucket per distinct key:

```kotlin
val perStatus: List<AggregateBucket<Status, Long>> =
    client.orders.query().rawCountBy(Order.status)        // key is the decoded enum

val unitsByStatus: List<AggregateBucket<Status, Long?>> =
    client.orders.query().rawSumBy(Order.status, Order.quantity)

for (b in perStatus) println("${b.key}: ${b.value}")
```

The group key is typed by the column handle (an enum column yields the decoded
enum, not its stored string). Grouping by a **nullable** column types the key as
`K?` and folds its NULLs into a single `key == null` bucket. You can group by
string/text, numeric, time, bool, UUID, or enum columns; bytes, JSON, and
pgvector columns are rejected at compile time. Bucket order is unspecified —
sort in Kotlin if you need a stable order.

### `*OrError` variants

Every terminal has an `…OrError` twin returning `EntResult<…>`, mapping
interceptor rejection and driver failure to `EntError` exactly like
`rawCountOrError` (raw aggregates never surface `PrivacyDenied`):

```kotlin
when (val r = client.orders.query().rawSumOrError(Order.quantity)) {
    is EntResult.Ok  -> println("units: ${r.value}")
    is EntResult.Err -> println("failed: ${r.error}")
}
```

V1 is Postgres-only and raw-only; privacy-aware (`visible…`) aggregates,
multi-metric selection, and multi-column / expression grouping are deferred —
see [Aggregations](implemented-features/query/aggregations.md).

## Edge Traversal

Query builders expose methods for traversing edges. Given a `User` with
a `hasMany<Post>("posts")` edge, the generated query builder has:

### `queryPosts()` -- follow an edge

Returns a new `PostQuery` scoped to the posts belonging to the matched
users:

```kotlin
val postsOfActiveUsers = client.users
    .query { where(User.active eq true) }
    .queryPosts()
    .allOrThrow()  // List<Post>
```

Traversal methods take the same defaulted receiver block as
`client.posts.query { ... }` and the indexed query helpers, so the
target query's shape can live inside the call:

```kotlin
val recentPosts = client.users
    .query { where(User.active eq true) }
    .queryPosts {
        where(Post.published eq true)
        orderBy(Post.id.desc())
        limit(10)
    }
    .allOrThrow()
```

The block configures the *target* query only — it is exactly
equivalent to chaining `.where(...)`, `.orderBy(...)`, `.limit(...)`
on the query `queryPosts()` returns. Source-query state is
snapshotted before the block runs, so nothing in the block can
change which source rows the traversal bridges from.

> **V1 traversal limitation: source `limit` / `offset` / `orderBy`
> are dropped at the bridge.** The bridging predicate becomes
> an `EXISTS` subquery, which has no row-count slot. So
>
> ```kotlin
> client.users.query {
>     orderBy(User.createdAt.desc())
>     limit(10)
> }.queryPosts().allOrThrow()
> ```
>
> does **not** mean "posts of the 10 most-recently-created users."
> It means "posts of users matching the source `where` clauses,"
> with the `limit(10)` and `orderBy(...)` silently ignored.
> Callers that need "posts of the first N users" must materialize
> the source query first, then re-query: `val users = client.users
> .query { orderBy(...); limit(10) }.allOrThrow(); client.posts
> .query { where(Post.authorId inList users.map { it.id }) }
> .allOrThrow()`. A future RFC may add a richer bridging lowering
> (CTE or IN-from-subquery) that honors source limit/order/offset.

### `has` / `hasWhere` -- edge predicates

Filter entities based on whether related entities exist:

```kotlin
// Users who have at least one post
client.users.query {
    where(User.posts.has())
}

// Users who have a published post
client.users.query {
    where(User.posts.has { where(Post.published eq true) })
}
```

Under the hood, edge predicates become `EXISTS (SELECT 1 FROM ...)` SQL
subqueries. For M2M edges, the subquery includes the junction table join.

## Eager Loading

By default, edge data is not loaded. Use `with{Edge}()` to batch-load
related entities alongside the main query:

```kotlin
val users = client.users.query {
    where(User.active eq true)
    withPosts {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
    }
}.allOrThrow()

// Access loaded edges
users.forEach { user ->
    val posts: List<Post>? = user.edges?.posts
    // null  = withPosts() was not called
    // []    = loaded, but no matching posts
    // [...]  = loaded with data
}
```

Eager loading avoids N+1 queries by collecting all parent IDs from the
main query result, then batch-loading the related entities with a single
`IN (id1, id2, ...)` query.

`limit` and `offset` inside a `with...()` block apply **per parent**, not
to that batched query — `withPosts { limit(5) }` gives each user their
first five posts, not five posts across all users. The same holds for
to-one edges, where at most one target exists per parent: a positive
limit is already satisfied, while `limit(0)` loads no target and any
positive offset steps past the only candidate.

### Nested Eager Loading

You can nest eager loads to load multiple levels of relationships:

```kotlin
val users = client.users.query {
    withPosts {
        where(Post.published eq true)
    }
}.allOrThrow()
```

### The `Edges` Data Class

Each entity with edges gets a nested `Edges` data class:

```kotlin
data class User(
    val id: UUID,
    val name: String,
    // ...
    val edges: Edges?,
) {
    data class Edges(
        val posts: List<Post>?,
    )
}
```

- `user.edges` is `null` when no eager loading was requested
- `user.edges?.posts` is `null` for a specific edge that wasn't loaded
- An empty list means the edge was loaded but no related entities exist

## Read-Path Interceptors

An interceptor is a hook that runs on every read query for an entity (or
across every entity for a global interceptor), with a chance to *narrow*
the query (add predicates, clamp limits) or reject it before the driver
call. The mechanism is generic; common uses are tenant scoping, max-limit
guards, and request annotation for tracing.

Interceptors are registered at client construction:

```kotlin
val client = EntClient(driver) {
    privacyContext { PrivacyContext(viewer) }
    interceptors {
        // Per-entity: narrow every Post read to the viewer's tenant.
        posts(
            interceptor = QueryInterceptor { scope, ctx ->
                scope.addPredicate(
                    Post.tenantId eq ctx.privacy.viewer.requireTenantId(),
                )
            },
            name = "tenant-scope",
        )
        // Global: cap unbounded scans at 1000 rows on every entity.
        global(
            interceptor = GlobalQueryInterceptor { scope, _ ->
                scope.rejectIfLimitGreaterThan(1000) {
                    "unbounded reads not allowed; pass query { limit(N) }"
                }
            },
            name = "max-limit",
        )
    }
}
```

Names are mandatory and unique within their scope (per-entity scopes
are independent; globals share one namespace). Names starting with
`framework:` are reserved for framework-owned interceptors installed
by schema mixins.

### What interceptors see

Each interceptor's `intercept(scope, context)` runs once per terminal
read. The `context` carries:

- `context.operation: ReadOperation` — which terminal fired
  (`BY_ID`, `FIRST`, `ALL`, `RAW_COUNT`, `RAW_EXISTS`,
  `VISIBLE_COUNT`, `VISIBLE_EXISTS`, `EDGE_TRAVERSAL`,
  `EDGE_PREDICATE`, `EAGER_LOAD`)
- `context.privacy` — the active `PrivacyContext` (viewer, etc.)
- `context.rootEntity` / `context.currentEntity` /
  `context.sourceEntity` / `context.edgeName` / `context.path` —
  set for traversal / eager / edge-predicate steps; null/empty
  for root reads
- `context.isEagerSubquery` — true iff `operation == EAGER_LOAD`

The `scope` exposes only operations that *narrow* the query:

- `addPredicate(predicate)` — AND with existing predicates
  (per-entity scope only; globals can't add typed predicates)
- `requireLimitAtMost(max)` — clamp the effective limit
- `setDefaultLimitIfAbsent(default)` — set a default when caller
  set none
- `rejectIfLimitGreaterThan(max) { reason }` — reject with
  `code = "max_limit_exceeded"` if unbounded or above `max`
- `addAnnotation(key, value)` — diagnostic key/value, surfaces in
  `explain()`
- `reject(reason, code)` — short-circuit the chain

Interceptors cannot remove caller predicates, raise caller-set
limits, change ordering, or swap the table. That property —
"reduce or reject, never broaden" — is enforced by the scope's
narrow surface, not by a runtime check.

### Rejection mapping

`scope.reject(...)` is converted at the API boundary:

| Terminal shape | On rejection |
|---|---|
| `*OrError` (incl. `byIdOrError`, `rawCountOrError`, etc.) | `Err(EntError.QueryRejected)` |
| `*OrThrow` (`allOrThrow`, `firstOrThrow`, `byIdOrThrow`, …) | `EntQueryRejectedException` |
| Non-result reads (`firstOrNull`, `visibleByIdOrNull`, `rawCount`, `visibleExists`, …) | `EntQueryRejectedException` (NOT collapsed to `null` / `false` / `0`) |
| `explain*` methods | `EntQueryRejectedException` |

The exception's `queryRejected` field carries `entity`,
`operation`, `reason`, optional `code`, and the rejecting
interceptor's `name` — so callers can branch on those fields
without parsing the message.

### Ordering and traversal

Within a single terminal, per-entity interceptors run before
globals, each in registration order. Each interceptor sees a live
shape reflecting prior mutations (`scope.shape` re-derives on
every access).

For traversal chains like
`client.users.query().queryGroups().queryPosts().allOrThrow()`,
each step fires the appropriate entity's interceptors with its
own `QueryContext`:

- `queryGroups()` — fires `User.interceptors` with
  `operation = EDGE_TRAVERSAL`
- `queryPosts()` — fires `Group.interceptors` with
  `operation = EDGE_TRAVERSAL`
- `.allOrThrow()` — fires `Post.interceptors` with
  `operation = ALL`, `sourceEntity = Group`, `edgeName = "posts"`,
  `path = [User→groups→Group, Group→posts→Post]`

Eager-load subqueries (`with{Edge} { ... }`) fire the target's
interceptors with `operation = EAGER_LOAD`. `has { ... }` /
`hasWhere { ... }` edge predicates fire the target's interceptors
with `operation = EDGE_PREDICATE`, narrowing the EXISTS subquery
(so `User.articles.has()` after a soft-delete interceptor is
installed on `Article` does NOT count soft-deleted articles). This
also applies to M2M edge predicates such as `Post.tags.has { ... }`;
the generated edge ref uses the normal `HasEdge` / `HasEdgeWith`
predicate shape, and SQL lowering adds the junction-table join.

The internal `Predicate.HasM2MEdgeFrom` shape is traversal plumbing
for `queryX()` and is not a public `has` / `hasWhere` bypass:
source interceptors fire during the traversal source step, and target
interceptors fire at the traversal terminal.

### See also

- [Read-Path Interceptors RFC](implemented-features/query/read-path-interceptors.md)
  for the full design and the limit-shape rules per operation.

## Transactions

Queries participate in transactions automatically when using a
transaction-scoped client:

```kotlin
client.withTransaction { tx ->
    val user = tx.users.create { name = "Alice"; email = "a@b.com" }.save()
    val posts = tx.posts.query {
        where(Post.authorId eq user.id)
    }.allOrThrow()
    // Both operations run in the same transaction
}
```

If the block throws, the transaction rolls back. Nested
`withTransaction` calls reuse the existing transaction.

For write-side transaction discipline — `TransactionRequirement`
(client-level write guardrail) and `UpdateConsistency.Pessimistic`
(per-save row-locking update mode) — see [Hooks → Execution
Order](05-hooks.md#execution-order) and
[Drivers → Locking (RFC #4)](10-drivers.md#locking-rfc-4).
