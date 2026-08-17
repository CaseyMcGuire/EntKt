# RFC: Set-Based Eager Graph Loader

## Status

Possible future feature. This is not implemented.

## Summary

Replace recursive per-parent nested eager loading with a set-based graph
executor.

The current first eager level batches parent IDs, but two important costs
remain:

1. A per-parent `limit` or `offset` fetches every matching target row and
   applies the window after grouping in Kotlin.
2. A nested eager load is invoked once for each parent group, so the second
   eager level can reintroduce N+1 queries.

The proposed executor first freezes the complete eager graph, then processes
it breadth-first. Each eager edge path is loaded for all of its current parents
as a set. Per-parent windows are pushed into SQL where the driver supports
them.

## Motivation

Consider:

```kotlin
client.users.query {
    withPosts {
        orderBy(Post.createdAt.desc())
        limit(5)

        withComments {
            orderBy(Comment.createdAt.desc())
            limit(3)
        }
    }
}.all()
```

For 100 users, the intended work is approximately:

```text
1 query: users
1 query: first five posts for all users
1 query: first three comments for all selected posts
```

The query count should be determined by the requested eager paths, not by the
number of root entities.

## Current Behavior

### First-Level Batching

The first eager level correctly collects every parent ID and issues one target
query using an `IN` predicate:

```sql
SELECT *
FROM posts
WHERE author_id IN (...)
ORDER BY created_at DESC
```

This avoids one post query per user.

### Per-Parent Window Overfetch

A SQL `LIMIT 5` on that query would mean five posts total, not five posts per
user. The current loader therefore omits the driver-level limit, fetches every
matching post, groups the rows by `author_id`, and applies `drop(offset)` and
`take(limit)` in Kotlin.

If 100 users each have 10,000 posts, the loader can fetch and hydrate one
million posts to return 500. The API result is correct, but its cost is much
larger than the requested result shape suggests.

### Nested N+1

After grouping posts by user, nested eager loading currently calls the child
query's `loadEdges` once per group. Loading comments can therefore become:

```text
1 query: users
1 query: posts for all users
100 queries: comments for each user's selected post group
```

The first eager level is batched, but recursion recreates N+1 at the next
level.

## Goals

- Make the query count grow with eager edge paths rather than parent count.
- Push per-parent ordering, limit, and offset into storage where supported.
- Preserve the existing `EdgeState` result contract.
- Preserve root and eager LOAD privacy behavior.
- Preserve read-interceptor operation, path, and rejection semantics.
- Deduplicate shared many-to-many targets before LOAD privacy evaluation.
- Keep eager execution observable and explainable.
- Bound large ID collections through deterministic chunking.

## Non-Goals

- Do not replace eager loading with one large join. Large joins multiply rows,
  complicate per-parent pagination, and can transfer more data than batched
  edge queries.
- Do not add implicit lazy loading.
- Do not weaken strict eager LOAD privacy.
- Do not make `filterVisible()` scan beyond the selected storage window.
- Do not introduce a process-global entity cache.
- Do not require every driver to implement native per-parent pagination.

## Proposed Model

### 1. Freeze The Eager Graph

The `with{Edge}` calls should configure an internal immutable plan before any
edge query runs. For the example above, the plan is conceptually:

```text
User
└── posts: published, createdAt DESC, first 5 per user
    └── comments: createdAt DESC, first 3 per post
```

The plan records, per edge:

- source and target schema metadata
- join or junction metadata
- target predicates and interceptors
- target ordering
- per-parent offset and limit
- `filterVisible()` posture
- nested eager children
- privacy-denial path metadata

This is an execution plan, not a public query result type.

### 2. Execute Breadth-First

The executor processes all parents for one eager edge invocation together:

1. Execute the root query and enforce root LOAD privacy.
2. For each first-level eager edge, collect all source keys and load its target
   window as a set.
3. Enforce that eager edge's LOAD privacy and attach its results.
4. Collect the selected targets from all parent groups.
5. Execute each nested eager edge once for that complete target set.
6. Continue until every requested path has been processed.

Several sibling edges still require separate queries because they have
different target tables or shapes. A many-to-many edge normally needs a
junction query plus a target query. The guarantee is therefore not "one query
per depth"; it is "a bounded number of queries per requested edge path, not per
parent."

### 3. Attach Results By Key

Each edge step returns both its decoded targets and an association map:

```text
source key -> ordered target keys or entities
```

The executor uses this map to construct copied entities with the appropriate:

```kotlin
EdgeState.Loaded(value)
```

It must retain the distinctions between:

- `Unloaded`
- `Loaded(null)`
- `Loaded(emptyList())`
- `Loaded(nonEmptyList)`

## Per-Parent Pagination

For PostgreSQL, a direct to-many edge can use a window function:

```sql
WITH ranked_posts AS (
    SELECT
        posts.*,
        ROW_NUMBER() OVER (
            PARTITION BY author_id
            ORDER BY created_at DESC, id DESC
        ) AS entkt_row
    FROM posts
    WHERE author_id = ANY(?)
      AND published = true
)
SELECT *
FROM ranked_posts
WHERE entkt_row > :offset
  AND entkt_row <= :offset + :limit
ORDER BY author_id, entkt_row
```

The primary key is appended as a deterministic tie-breaker when the caller's
ordering is not unique. With `offset = 0` and `limit = 5`, the database returns
at most five posts per user.

A lateral query is another possible PostgreSQL lowering. The driver-facing
contract should describe per-parent window semantics rather than expose a
PostgreSQL-specific strategy to generated code.

When no finite limit exists, an ordinary batched `IN` query remains
appropriate. An offset without a limit still benefits from a window-function
lowering because it avoids transferring the discarded prefix for every
parent.

## Many-To-Many Edges

A many-to-many eager step generally performs:

1. One junction query for all source IDs.
2. Per-source junction ordering/windowing if the edge shape requires it.
3. One target query for the distinct target IDs selected by those windows.
4. One association pass that attaches shared targets to every relevant source.

The executor must evaluate a shared target's LOAD privacy once per eager step,
not once for every source that references it.

## Privacy And Interceptor Semantics

The optimization must not change observable authorization behavior.

- Root LOAD privacy completes before eager loading starts.
- Each eager query runs its target read interceptors with
  `ReadOperation.EAGER_LOAD` and the complete edge path.
- The per-parent storage window is selected before target LOAD privacy, matching
  the current contract.
- Strict mode fails the whole read on an eager target denial and reports the
  full edge path.
- `filterVisible()` removes denied targets only from that edge's already
  selected window.
- Nested eager edges receive only targets retained by their parent edge.
- Shared targets are deduplicated for privacy evaluation without losing their
  associations or order.
- Configured eager interceptors retain their documented empty-input behavior.

Batch privacy rules should receive one ordered distinct target batch per
set-based eager step. Scalar rules may still adapt over that batch in order.

## Driver Contract

The runtime needs a driver operation expressed in relationship terms, for
example an internal shape equivalent to:

```kotlin
fun queryRelated(
    targetTable: String,
    parentColumn: String,
    parentIds: List<Any>,
    predicates: List<Predicate<*>>,
    orderBy: List<OrderField<*>>,
    perParentLimit: Int?,
    perParentOffset: Int,
): RelatedRows
```

The exact API is open. It should return enough association information to
avoid relying on row order alone.

Driver capabilities should state whether per-parent windows are:

- natively supported
- emulated with bounded behavior
- unsupported

An emulated fallback must remain explicit in diagnostics because its
performance can differ substantially. Silent full-result overfetch should not
be treated as equivalent to native support.

## Large Parent Sets

The executor must chunk parent keys when a driver or database has a parameter
limit. Chunking must preserve:

- per-parent result ordering
- source encounter order when attaching results
- one logical privacy batch per eager step, even if storage used several
  physical chunks
- deterministic failure and denial reporting

Physical chunk count should be available to tracing and diagnostics.

## Explain And Tracing

The existing query plan should distinguish logical eager steps from physical
query multiplicity. Useful diagnostics include:

```text
posts:
  strategy: window-partitioned batch
  parent key: users.id -> posts.author_id
  per-parent window: offset 0, limit 5
  estimated queries: 1..N chunks

comments:
  strategy: window-partitioned batch
  parent key: posts.id -> comments.post_id
  per-parent window: offset 0, limit 3
  estimated queries: 1..N chunks
```

Runtime tracing should report actual chunk count, rows fetched, rows retained,
and whether a driver used native or emulated per-parent pagination. Bind values
remain redacted by default.

## Implementation Direction

A safe sequence is:

1. Extract an internal immutable eager graph from the existing generated query
   configuration without changing the public DSL.
2. Add query-count tests that demonstrate the current nested N+1 behavior and
   specify the desired bounded count.
3. Add a PostgreSQL per-parent-window driver primitive and integration tests.
4. Execute one direct to-many eager path through the new runtime executor.
5. Add nested breadth-first execution.
6. Add belongs-to, has-one, and many-to-many strategies.
7. Move privacy, interceptor, explain, and `filterVisible()` behavior onto the
   shared executor while retaining contract tests.
8. Remove the recursive generated `loadEdges` implementation after parity is
   established.

This should be implemented as a runtime execution engine with generated typed
metadata adapters, rather than reproducing the graph algorithm in every
generated query class.

## Test Requirements

Before implementation is complete, tests should prove:

- one direct eager edge uses a bounded number of queries independent of parent
  count
- a two-level eager graph does not issue one nested query per root parent
- per-parent limit and offset return the same rows as the current semantics
- PostgreSQL does not fetch rows outside finite per-parent windows
- caller ordering is preserved with a deterministic primary-key tie-breaker
- belongs-to, has-one, has-many, and many-to-many attachments are correct
- shared many-to-many targets are decoded and privacy-checked once per step
- strict eager denial retains the complete root-to-target path
- `filterVisible()` affects only the configured edge and does not over-scan
- nested edges load only retained parent targets
- read interceptors receive the same operation and path metadata as today
- eager interceptors still run for configured empty result sets
- physical chunking does not change ordering, privacy batches, or results
- `EdgeState.Unloaded`, `Loaded(null)`, and `Loaded(emptyList())` remain
  distinguishable
- explain output describes the chosen eager strategy and possible chunking

## Open Decisions

- The internal eager-plan and association-result type shapes.
- Window functions versus lateral queries for PostgreSQL.
- Whether per-parent offset without a limit is supported natively by every
  driver or rejected by drivers that cannot lower it efficiently.
- The threshold and mechanism for parent-ID chunking.
- Whether a driver with only an overfetch fallback should reject finite
  per-parent windows by default or require an explicit emulation opt-in.
- How estimated physical query counts compose with nested paths and chunks.

## Related Features

- [Request-Scoped Entity Loading](request-scoped-entity-loading.md)
- [Query Observability Diagnostics](query-observability-diagnostics.md)
- [Driver Capability Matrix](../tooling/driver-capability-matrix.md)
- [Projection / Select API](projection-select-api.md)
