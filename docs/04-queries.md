# Queries

The generated `{Entity}Query` builder provides a type-safe API for
filtering, ordering, paginating, traversing edges, and eager loading
related entities.

Generated query builders are mutable and **not thread-safe**. Configure and
execute a query instance from one thread at a time; do not mutate or execute
the same instance concurrently. Create a separate query builder for each
concurrent operation. A fully configured query may be executed repeatedly when
those executions are sequential.

## Basic Usage

```kotlin
val viewerContext = ViewerContext(Viewer.User(currentUserId()))
val users = client.users.query {
    where(User.active eq true)
    orderBy(User.name.asc())
    limit(10)
    offset(20)
}.all(viewerContext).getOrThrow()
```

`.all(viewerContext)` returns `ReadResult<List<User>>` — the canonical exhaustive
result of every read terminal. Use `.firstOrNull(viewerContext)`
(`ReadResult<User?>`) for single results. Counts, existence checks, and
aggregates are ordinary Kotlin collection operations over those entity reads;
use an explicit privacy-bypass context when the calculation must include rows
the application viewer cannot load.

A `ReadResult` is either `Success(value)` or `Failed(exception)`.
`Success(null)` on a singular lookup is authoritative absence;
privacy denial and operational failure are `Failed` carrying a typed
exception (`EntPrivacyDeniedException`, `EntQueryRejectedException`,
or the original driver exception). Two runtime projections cover the
common handling styles without another database call:

- `.getOrThrow()` — return the successful value (absence stays
  `null`) or throw the stored exception directly.
- `.visibleOrNull()` — on a singular result, map *root* LOAD denial
  to `Success(null)`; every other state is unchanged. Privacy as
  absence, made explicit.

Materializing collection terminals evaluate LOAD privacy over the complete
ordered root result. The first explicit batch privacy rule is invoked once with
that list; each later rule receives the ordered still-unresolved subset after
earlier `Allow` / `Deny` decisions. An ordinary scalar rule adapts by visiting
its supplied subset in order.
`findById(viewerContext, id)` and `firstOrNull(viewerContext)` use the same
evaluator with a singleton when a row exists, and do not invoke LOAD rules on
absence. The exact terminal-supplied `ViewerContext` instance is shared by the
terminal's interceptors, root LOAD phase, and all traversal and eager work.

## Indexed Query Helpers

When an entity declares indexes, the generated repo exposes an `indexes`
namespace of staged, type-safe builders that make the index-friendly read
paths discoverable from IDE completion. Each helper just seeds the indexed
predicate and hands back the **normal** query builder, so privacy, read
interceptors, eager loading, ordering, and the terminal choices are all
unchanged.

Given:

```kotlin
class Post : EntSchema("posts", clientName = "posts") {
    override fun id() = EntId.long()
    val authorId by long("author_id")
    val createdAt by time("created_at")
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
    .all(viewerContext)
    .getOrThrow()

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
    .all(viewerContext)
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
    .all(viewerContext)
```

### The unique terminal

When a bound prefix exactly matches a non-nullable **unique** index, the
stage exposes a single-row terminal alongside `query()`:

```kotlin
val result = client.users.indexes.email("a@example.com").find(viewerContext)
// ReadResult<User?> — Success(user), Success(null) on a miss,
// Failed(EntPrivacyDeniedException) on denial, Failed(e) otherwise

client.users.indexes.email("a@example.com").find(viewerContext).getOrThrow()
// User? — miss is null; denial and failure throw

client.users.indexes.email("a@example.com").find(viewerContext).visibleOrNull().getOrThrow()
// User? — miss OR root LOAD denial → null
```

A composite unique index exposes `find()` only at its full stage —
a partial prefix exposes `query()` but not `find()`.

### What generates a helper

Helpers come from explicit `index(...)` declarations and the unique
indexes synthesized by `field.unique()` / `belongsTo(...).unique()`.
Method names are the generated column-ref property names (e.g.
`author_id` → `authorId`), never the storage column name. V1 helper
parameters are non-null, so to query a `NULL` indexed value use the
normal `query { where(col.isNull()) }`. The generator skips
raw-SQL partial indexes, native / non-btree indexes (e.g. pgvector
HNSW/IVFFlat), and indexes over btree-incompatible columns (JSON,
pgvector, bytes).

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
Ticket.priority eq Priority.HIGH
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

- `cosineDistance(q)`, `l2Distance(q)`, and `innerProduct(q)` use the
  corresponding pgvector distance calculation. The query vector is passed as a
  value, just like other query inputs.
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
client.users.query { limit(0) }.firstOrNull(viewerContext).getOrThrow()  // → null
client.users.query { limit(0) }.all(viewerContext).getOrThrow()          // → []
```

Note that `limit(n)` above 1 doesn't change what a first-row terminal
returns — it already fetches a single row.

## Aggregations

Generated queries expose only entity terminals. Calculate collection facts in
Kotlin after the read:

```kotlin
val activeUsers = client.users.query {
    where(User.active eq true)
}.all(viewerContext).getOrThrow()

val activeCount = activeUsers.size
val anyActive = activeUsers.isNotEmpty()
```

Use `firstOrNull()` when only existence matters and you do not need the full
collection:

```kotlin
val emailTaken = client.users.query {
    where(User.email eq "alice@example.com")
}.firstOrNull(viewerContext).getOrThrow() != null
```

Both forms materialize entities and therefore run LOAD privacy. For an
intentional storage-wide calculation, supply an explicit bypass context:

```kotlin
val systemVc = ViewerContext.privacyBypass_DANGEROUS("billing report")
val orders = client.orders.query {
    where(Order.status eq Status.SHIPPED)
}.all(systemVc).getOrThrow()

val units = orders.sumOf { it.quantity }
val latest = orders.maxOfOrNull { it.placedAt }
val countByStatus = orders.groupingBy { it.status }.eachCount()
```

The bypass is visible at the operation boundary and applies to the complete
entity read; read interceptors still run. These calculations honor the query's
ordering, limit, offset, and selected edge loads because they operate on the
materialized result. Generated clients do not expose database-native count,
existence, or aggregate terminals. For a large set where materialization is
inappropriate, use an application-owned storage query with an explicitly
documented authorization boundary.

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
    .all(viewerContext)
    .getOrThrow()  // List<Post>
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
    .all(viewerContext)
    .getOrThrow()
```

The block configures the *target* query only — it is exactly
equivalent to chaining `.where(...)`, `.orderBy(...)`, `.limit(...)`
on the query `queryPosts()` returns. The source selection is captured
before the block runs, so nothing in the block can change which source
rows are traversed.

Traversal follows the source query **as written**: source `where`,
`orderBy`, `limit`, and `offset` select which source rows are
traversed. For example:

```kotlin
client.users.query {
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts().all(viewerContext)
```

means "posts whose author is one of the 10 most-recently-created
users." Three details worth pinning:

- **Source ordering selects the source rows; it does not order the
  target rows.** Posts above come back in target order — order the
  target query (`queryPosts { orderBy(Post.createdAt.desc()) }`) if
  post order matters.
- **A target `limit` is a total target-row limit**, not "N posts per
  user." Use `loadPosts { limit(n) }` for per-source eager-load
  shaping.
- **Target rows are never duplicated** by fan-out, even when several
  selected source rows reach the same target (many-to-many).

Traversal does not apply source LOAD privacy: source rows only
define the target query and are not returned. Target rows keep the
normal strict read semantics — `all()` returns
`Failed(EntPrivacyDeniedException)` when any target row in the
selected window is denied. Callers that need source LOAD privacy to
decide which rows are traversed should materialize the source query
first with `all()` (under the strict model a denied source row fails
that read rather than being skipped), then query the target by id.

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

The same `has { ... }` API works for direct and many-to-many edges.

## Edge Loading

By default, relationships are unloaded. Use the generated `load{Edge}()`
methods to select the relationships materialized for the returned
entities — `{Edge}` is always the delegated Kotlin edge declaration on
the schema, never a storage string or a pluralized type name. Loading
preserves the query's result root: `loadPosts()` returns the same users
and fills `user.edges.posts`, while `queryPosts()` *changes* the result
root to posts. No relationship accessor ever performs implicit database
I/O — an edge is either selected up front or stays `Unloaded`.

```kotlin
val users = client.users.query {
    where(User.active eq true)
    loadPosts {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
    }
}.all(viewerContext).getOrThrow()

// Access loaded edges
users.forEach { user ->
    val posts: EdgeState<List<Post>> = user.edges.posts
    // EdgeState.Unloaded            = loadPosts() was not called
    // EdgeState.Loaded(emptyList()) = loaded, but no matching posts
    // EdgeState.Loaded([...])       = loaded with data
    val loaded: List<Post> = posts.requireLoaded()
}
```

Because `{Edge}` is the delegated declaration, two edges to the same
target type keep their distinct roles, and a storage name never leaks
into the API:

```kotlin
class User : EntSchema("people", clientName = "users") {
    val authoredPosts by hasMany<Post>("authored_post_rows")
    val reviewedPosts by hasMany<Post>("reviewed_post_rows")
}

client.users.query {
    loadAuthoredPosts()   // not loadPosts, loadPostEdges, or loadUser
    loadReviewedPosts { orderBy(Post.createdAt.desc()) }
}.all(viewerContext)
```

A declaration/storage mismatch generates only the declaration-based
method: `val directories by hasMany<Directory>("legacy_owner")`
generates `loadDirectories()` — nothing is derived from the
`legacy_owner` storage string, the `Directory` type, or an English
pluralizer.

The executor is set-based: each configured eager edge collects the
IDs of every current parent, batch-loads the related entities with
one `IN (id1, id2, ...)` query, and runs each nested eager edge once
for the ordered distinct union of retained targets — never once per
parent group. Framework-issued relationship-read counts are bounded
by configured eager paths, not by how many parents have data. That
is an execution detail, not part of the method's contract:
`load{Edge}()` promises relationship materialization, not a fixed
SQL strategy or statement count — many-to-many edges add a junction
read.

Every eager target query executes one deterministic *effective
order*: the caller's `orderBy` terms followed by the target primary
key ascending, unless the caller already ordered by the primary key.
That order drives storage reads, per-parent windows, privacy-batch
order, and association order — so tied rows enter a finite window
deterministically. Interceptors see the effective order on
`shape.orderBy` and the caller-authored terms separately on
`shape.callerOrderBy` / `hasCallerOrderBy`; root (non-eager) queries
gain no framework ordering term.

`limit` and `offset` inside a `load...()` block apply **per parent**, not
to that batched query — `loadPosts { limit(5) }` gives each user their
first five posts, not five posts across all users. How the window
executes depends on the edge kind and the driver. On a driver with
native per-parent windows (PostgreSQL), a direct to-many edge pushes
the window into storage — one statement ranks each parent's targets
with the effective order and returns only in-window rows, with the
parent keys transported as a single typed array. Every other edge kind —
and every driver without the capability — emulates the window in memory: the
driver fetches every matching row and the runtime slices each
parent's window in Kotlin, so a finite window bounds the result, not
the rows fetched. Both strategies
select exactly the same rows; they follow the same effective order
with its primary-key tie-breaker. The same per-parent semantics hold
for to-one edges, where at most one target exists per parent: a
positive limit is already satisfied, while `limit(0)` loads no target
and any positive offset steps past the only candidate.

### One Selection Per Edge

A query selects each edge at most once. A second `load{Edge}` call for
the same edge throws `EntQueryConfigurationException` immediately — the
first block is never silently replaced or merged:

```kotlin
client.users.query {
    loadPosts { where(Post.published eq true) }
    loadPosts { orderBy(Post.createdAt.desc()) } // throws
}
```

Compose all configuration for one edge in one block. Executing the
same fully configured query object more than once is not a duplicate
selection — the selected graph remains part of the query until the
query object is discarded.

The rejection also covers re-entrant selection (`load{Edge}` called
again from inside its own configuration block) — a failed selection is
rolled back, so a caught error leaves the query as if it never
happened.

A terminal captures an immutable snapshot of the complete selected graph
before execution begins. If an interceptor or privacy rule retains and mutates
the reusable query builder during execution, that mutation cannot change the
in-flight read; it is visible only to a later terminal on the same builder.
Query builders remain non-thread-safe, so this does not make concurrent
mutation and execution safe.

### Traversal Cannot Carry Selected Source Edges

`query{Edge}` traversal rejects a source query with selected edges: a
traversal changes the result root, so there is no coherent graph to carry
across. Traverse first, then select loads on the target query:

```kotlin
client.users.query { loadPosts() }.queryPosts()   // throws

client.users.query { where(User.active eq true) }
    .queryPosts { loadComments() }                // fine
    .all(viewerContext)
```

### Eager Privacy and `filterVisible()`

Eager loading is strict by default: if LOAD privacy denies any eagerly
loaded target, the whole read fails with
`Failed(EntPrivacyDeniedException(SelectedEdgePath(steps), ...))` — no partial
graph, no silently omitted target. The exception's `origin` carries the
schema-edge path from the root to the denied target, so root denial
(`Root`) and eager denial stay distinguishable; `visibleOrNull()` maps
only *root* denial to absence, so adding an eager load can never make a
visible root look absent.

For each eager query, LOAD privacy receives one ordered batch of the distinct
targets that remain in at least one parent's requested window, in effective
target order. A shared many-to-many target is evaluated once, not once per
parent. Strict mode reports the first denied target after evaluating that
batch; `filterVisible()` removes every denied target from every group that
references it. Nested eager paths repeat the contract once per logical edge
step: a nested edge's privacy batch holds the ordered distinct union of every
parent group's retained targets, not one batch per group.

Each `load{Edge} { ... }` call returns an `EdgeLoad` handle. Calling
`filterVisible()` on it opts that one edge into retaining only visible
targets:

```kotlin
val users = client.users.query {
    where(User.active eq true)
    loadPosts {
        orderBy(Post.createdAt.desc())
        limit(10)
    }.filterVisible()
}.all(viewerContext).getOrThrow()
```

With `filterVisible()`, a denied to-one target loads as
`EdgeState.Loaded(null)` and denied to-many targets are omitted from
the loaded list — without scanning beyond the selected eager-load
window to replace them. The setting applies only to that exact edge
(nested eager loads must opt in independently), never changes root
denial behavior, and does not suppress eager-query rejection or
ordinary driver/materialization failures. Ignoring the returned handle
keeps the strict default.

### Nested Edge Loading

Nested `load{Edge}` calls select a multi-level graph. Every nested
block is the ordinary generated query DSL for that relationship's
target, so its fields, query operations, traversals, and loadable
edges all complete normally:

```kotlin
val users = client.users.query {
    loadPosts {
        where(Post.published eq true)

        loadComments {
            orderBy(Comment.createdAt.asc())
        }
    }
}.all(viewerContext).getOrThrow()
```

### The `Edges` Data Class

Each entity with edges gets a nested `Edges` data class:

```kotlin
data class User(
    val id: UUID,
    val name: String,
    // ...
    val edges: Edges = Edges(),
) {
    data class Edges(
        val posts: EdgeState<List<Post>> = EdgeState.Unloaded,
    )
}
```

The `edges` container itself is never null — it defaults to an empty
`Edges()`. Each edge carries an explicit `EdgeState`, so loaded vs
unloaded is a first-class distinction:

- `user.edges.posts` is `EdgeState.Unloaded` when `loadPosts()` was not
  called
- `EdgeState.Loaded(emptyList())` means the edge was loaded but no
  related entities exist
- To-one edges (e.g. `article.edges.author`) are `EdgeState<Author?>`;
  `Loaded(null)` means the edge was requested but no target was returned
  — distinct from `Unloaded`. This holds even for a required foreign
  key: eager predicates, interceptors, `limit(0)`, or an offset can all
  exclude the target.

Four helpers cover the access patterns:

- `state.isLoaded` — `true` for any `Loaded`, including a loaded `null`
  or empty list
- `state.requireLoaded()` — the loaded value; throws
  `EdgeNotLoadedException` (an `IllegalStateException` — a
  programming error, not a read failure) when the edge was not
  requested. Use it when the
  surrounding query always requests the edge.
- `state.loadedOrNull()` — the `Loaded` wrapper or `null`; preserves the
  `Unloaded` vs `Loaded(null)` distinction
- `state.valueOrNull()` — the value or `null`; deliberately collapses
  `Unloaded` and `Loaded(null)` when the caller doesn't need to tell
  them apart

## Read-Path Interceptors

An interceptor is a hook that runs on every read query for an entity (or
across every entity for a global interceptor), with a chance to *narrow*
the query (add predicates, clamp limits) or reject it before execution.
The mechanism is generic; common uses are tenant scoping, max-limit
guards, and request annotation for tracing.

Interceptors are registered at client construction:

```kotlin
val client = EntClient(driver) {
    interceptors {
        // Per-entity: narrow every Post read to the viewer's tenant.
        posts(
            interceptor = QueryInterceptor { scope, ctx ->
                scope.addPredicate(
                    Post.tenantId eq ctx.viewerContext.viewer.requireTenantId(),
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

- `context.operation: ReadOperation` — which read fired
  (`BY_ID`, `FIRST`, `ALL`, `EDGE_TRAVERSAL`, `EDGE_PREDICATE`,
  `EAGER_LOAD`, `EAGER_JUNCTION`, `DELETE_CANDIDATES`)
- `context.viewerContext` — the active `ViewerContext` (viewer, etc.)
- `context.rootEntity` / `context.currentEntity` /
  `context.sourceEntity` / `context.edgeName` / `context.path` —
  set for traversal / eager / edge-predicate steps; null/empty
  for root reads
- `context.isEagerSubquery` — true iff the operation is
  `EAGER_LOAD` or `EAGER_JUNCTION`

The `scope` exposes only operations that *narrow* the query:

- `addPredicate(predicate)` — AND with existing predicates
  (per-entity scope only; globals can't add typed predicates)
- `requireLimitAtMost(max)` — clamp the effective limit
- `setDefaultLimitIfAbsent(default)` — set a default when caller
  set none
- `rejectIfLimitGreaterThan(max) { reason }` — reject with
  `code = "max_limit_exceeded"` if unbounded or above `max`
- `addAnnotation(key, value)` — attach diagnostic metadata visible to
  the current and later interceptors through `scope.shape.annotations`
- `reject(reason, code)` — short-circuit the chain

Interceptors cannot remove caller predicates, raise caller-set
limits, change ordering, or swap the table. That property —
"reduce or reject, never broaden" — is part of the interceptor API.

### Rejection mapping

`scope.reject(...)` is converted at the API boundary:

| Terminal shape | On rejection |
|---|---|
| Result-bearing reads (`findById`, `firstOrNull`, `all`, `find`) | `ReadResult.Failed(EntQueryRejectedException)` — never collapsed to `null` or an empty list; `.getOrThrow()` throws it, `.visibleOrNull()` leaves it unchanged |

`EntQueryRejectedException` exposes direct properties — `entityType`,
`reason`, optional `code`, and the rejecting interceptor's
`interceptor` name — so callers can branch on those fields without
parsing the message.

`EntQueryConfigurationException` is a different failure family:
deterministic application misuse discovered by the query DSL itself —
selecting one edge twice, or a selected edge-load graph reaching a
`query{Edge}` traversal. Configuration operations throw it immediately.
It carries `entityType` and `reason`.

### Ordering and traversal

Within a single terminal, per-entity interceptors run before
globals, each in registration order. Each interceptor sees a live
shape reflecting prior mutations (`scope.shape` re-derives on
every access).

For traversal chains like
`client.users.query().queryGroups().queryPosts().all(viewerContext)`,
each step fires the appropriate entity's interceptors with its
own `QueryContext`:

- `queryGroups()` — fires `User.interceptors` with
  `operation = EDGE_TRAVERSAL`
- `queryPosts()` — fires `Group.interceptors` with
  `operation = EDGE_TRAVERSAL`
- `.all(viewerContext)` — fires `Post.interceptors` with
  `operation = ALL`, `sourceEntity = Group`, `edgeName = "posts"`,
  `path = [User→groups→Group, Group→posts→Post]`

Eager loads (`load{Edge} { ... }`) fire the related entity's
interceptors with `operation = EAGER_LOAD`. `has { ... }` edge
predicates fire the related entity's interceptors with
`operation = EDGE_PREDICATE`. This means an interceptor that hides
deleted articles also prevents those articles from satisfying
`User.articles.has()`. The same behavior applies to many-to-many
edges.

An eager many-to-many step additionally fires the JUNCTION entity's
interceptors with `operation = EAGER_JUNCTION` before its junction
discovery read, so interceptor predicates registered on the junction
(tenant scoping, `ExcludeDeleted`) decide which junction rows
contribute relationships, exactly as they narrow direct junction
reads. Junction LOAD *privacy* deliberately does NOT run on
discovery — junction rows are never materialized to the caller
here, and the fail-closed model would otherwise fail every
many-to-many read for schemas without junction Allow rules — so a
junction guarded only by privacy rules (no interceptors) still
contributes every relationship to eager loading. The M2M `queryX()`
traversal and `has {}` lowerings do not yet run junction-entity
interceptors either (see the junction read-interceptors RFC for the
open phases).

## Transactions

Queries participate in transactions automatically when using a
transaction-scoped client. `withTransaction` runs its block with a
`TransactionScope` receiver and an `EntTransactionClient` parameter,
then returns the exhaustive `TransactionResult<T>`:

```kotlin
val posts = client.withTransaction { tx ->
    val user = tx.users.create { name = "Alice"; email = "a@b.com" }
        .saveAndLoad(viewerContext)
        .orRollback()
    tx.posts.query {
        where(Post.authorId eq user.id)
    }.all(viewerContext).orRollback()
    // Both operations run in the same transaction
}.getOrThrow()
```

`orRollback()` — available only inside the scope — returns a successful
read or mutation value; on a `Failed` result it stops the block so the
transaction rolls back. A mutation failure produced through the `tx`
client marks the scope rollback-only even if its result is ignored, so
a normally returning block still rolls back and reports the first
recorded failure. If the block throws, the transaction rolls back.
`TransactionResult` is `Success(value)` or
`Failed(exception, transactionState)` with `transactionState` either
`NotCommitted` (rollback confirmed) or `OutcomeUnknown`;
`.getOrThrow()` rethrows the exact stored exception when rollback was
confirmed, so ordinary typed catches work. When commit or rollback
could not be confirmed, it instead throws
`EntTransactionOutcomeUnknownException` with the stored exception as
its cause. Callers needing the complete state without throwing can
inspect `TransactionResult.Failed` directly.
`EntTransactionClient` exposes repositories but has no `withTransaction`
member, so nested client transactions do not
compile. At the lower-level driver API, an already-transactional driver
still throws `NestedTransactionUnsupportedException` before the nested
block runs.

`EntClient` and `EntTransactionClient` both implement the generated
`EntClientScope` interface, which contains repositories but no transaction
entry point or configuration APIs. Helpers that should operate with either client can
accept this interface instead of overloading on the two concrete types.
`beforeCreate` and `beforeUpdate` hook contexts expose the same narrow type,
plus the operation's `ctx.viewerContext`, so nested repository operations can
pass the exact context explicitly without making nested client transactions
reachable through `ctx.client`.

For write-side transaction discipline — `TransactionRequirement`
(client-level write guardrail) and `UpdateConsistency.Pessimistic`
(per-save row-locking update mode) — see [Hooks → Execution
Order](05-hooks.md#execution-order) and
[Drivers → Locking (RFC #4)](10-drivers.md#locking-rfc-4).
