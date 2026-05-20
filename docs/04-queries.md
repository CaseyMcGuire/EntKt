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
}.all()
```

`.all()` returns a `List<User>`. Use `.firstOrNull()` for single results,
`.visibleCount()` for a privacy-aware count, `.rawCount()` for a fast
aggregate count, or `.rawExists()` / `.visibleExists()` to check whether
a match exists (raw skips LOAD privacy; visible applies it).

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

## Pagination

```kotlin
client.users.query {
    limit(10)
    offset(20)
}
```

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
    .all()  // List<Post>
```

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
}.all()

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

### Nested Eager Loading

You can nest eager loads to load multiple levels of relationships:

```kotlin
val users = client.users.query {
    withPosts {
        where(Post.published eq true)
    }
}.all()
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
installed on `Article` does NOT count soft-deleted articles).

### See also

- [Read-Path Interceptors RFC](possible-features/query/read-path-interceptors.md)
  for the full design and the limit-shape rules per operation.

## Transactions

Queries participate in transactions automatically when using a
transaction-scoped client:

```kotlin
client.withTransaction { tx ->
    val user = tx.users.create { name = "Alice"; email = "a@b.com" }.save()
    val posts = tx.posts.query {
        where(Post.authorId eq user.id)
    }.all()
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
