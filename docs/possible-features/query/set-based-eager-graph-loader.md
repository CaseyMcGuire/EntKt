# RFC: Set-Based Eager Graph Loader

## Status

Accepted execution direction as of 2026-08-18.

**Phase 1 implemented 2026-08-19**: set-based nested eager execution (one
interceptor pass, driver query, privacy batch, and nested recursion per
logical edge step), the deterministic effective-ordering contract with
`callerOrderBy` / `hasCallerOrderBy` attribution, and the typed
`QueryPlan.eagerExecution` explain metadata. Per-parent windows remain
emulated in memory. The "Current Behavior" section below describes the
pre-phase-1 executor this RFC replaced.

**Phase 2A implemented 2026-08-20** (design accepted the same day):
PostgreSQL-native per-parent windows for direct to-many edges, using the
runtime-owned relationship plan (`DirectToManyQuery` / `RelatedRows` and
the `executeDirectToMany` fallback selector in `entkt.runtime.driver`),
two new abstract `Driver` members (`directToManyWindowCapability` /
`queryDirectToMany`), typed-array parent-key transport (`= ANY(?)`), a
`ROW_NUMBER() OVER (PARTITION BY ...)` lowering with collision-safe rank
aliases and Long window bounds, frozen-spec structural-predicate
attribution, capability-aware bind budgeting, and the
`EagerWindowStrategy.STORAGE_NATIVE` explain metadata. Drivers without
the native capability retain phase-1 emulation.

Native many-to-many windows and generic physical chunking are deferred to
later phases; they are not prerequisites for phase 2A.

This RFC owns set-based edge-load execution, not the public API used to select
edges. The accepted
[Generated Edge Loading API](../../implemented-features/query/generated-edge-loading-api.md) RFC defines the
`load{Name}` selection surface used in these examples. The executor consumes
the immutable edge-load plan produced by that public DSL.

## Summary

Replace recursive per-parent nested eager loading with set-based eager-edge
execution.

The current first eager level batches parent IDs, but two important costs
remain:

1. A per-parent `limit` or `offset` fetches every matching target row and
   applies the window after grouping in Kotlin.
2. A nested eager load is invoked once for each parent group, so the second
   eager level can reintroduce N+1 queries.

The proposed executor captures the configured eager topology, then processes
it set-at-a-time and depth-first. Each eager edge path is loaded once for the
ordered union of its current parents rather than once per parent group. This
preserves the current edge-path precedence while removing nested N+1 queries.
Combining parent groups intentionally changes per-parent callback invocation
counts and item-level failure ordering; those changes are specified below.

Delivery is intentionally split:

1. Eliminate nested N+1 queries using the existing driver query surface.
2. Add a relationship-aware driver capability that pushes direct to-many
   per-parent windows into PostgreSQL storage.

The first phase does not wait for the second. It fixes query multiplication
even though finite per-parent windows may still overfetch until the driver
capability is available.

## Motivation

Consider:

```kotlin
client.users.query {
    loadPosts {
        orderBy(Post.createdAt.desc())
        limit(5)

        loadComments {
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

Framework-issued relationship-read count should be bounded by requested eager
paths and required storage chunks, never by populated parent groups.
Application callbacks can still issue their own per-item reads unless they use
the batch rule APIs.

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

- Make framework-issued relationship-read count grow with eager edge paths and
  required storage chunks rather than populated parent groups.
- Preserve the current depth-first eager-path precedence across siblings while
  explicitly defining the necessary per-parent callback-order change.
- Push per-parent ordering, limit, and offset into storage where supported,
  without making that capability a prerequisite for nested batching.
- Preserve the existing `EdgeState` result contract.
- Preserve root and eager LOAD privacy behavior.
- Preserve read-interceptor operation, path, and rejection semantics while
  defining the intentional change from per-group to per-edge-step invocation.
- Deduplicate shared many-to-many targets before LOAD privacy evaluation.
- Keep eager execution observable and explainable.
- Avoid one-bind-per-parent amplification for native PostgreSQL direct
  to-many loads through typed-array parent-key transport.

## Non-Goals

- Do not replace eager loading with one large join. Large joins multiply rows,
  complicate per-parent pagination, and can transfer more data than batched
  edge queries.
- Do not add implicit lazy loading.
- Do not weaken strict eager LOAD privacy.
- Do not make `filterVisible()` scan beyond the selected storage window.
- Do not introduce a process-global entity cache.
- Do not automatically batch database work initiated inside scalar privacy
  rules. Applications still use batch privacy rules for set-aware policy I/O.
- Do not require every driver to implement native per-parent pagination.
- Do not promise one physical query per eager path. Junction reads, parameter
  chunking, and driver strategies can require several physical queries.

## Proposed Model

### 1. Capture The Topology, Freeze Each Reached Step

The `load{Name}` calls configure the eager topology before edge execution. For
the example above, the topology is conceptually:

```text
User
└── posts: published, createdAt DESC, first 5 per user
    └── comments: createdAt DESC, first 3 per post
```

The topology records, per edge:

- source and target schema metadata
- join or junction metadata
- target predicates and interceptors
- target ordering
- per-parent offset and limit
- `filterVisible()` posture
- nested eager children
- privacy-denial path metadata

This is internal execution metadata, not a public query result type.

Each eager query's mutable operands are semantically snapshotted only when
depth-first traversal reaches that logical edge step, matching current timing.
The executor does not snapshot every nested predicate at terminal entry and
does not run eager interceptors while capturing topology. A target interceptor
still executes after any association discovery needed to build that step's
structural predicate. This preserves rejection, side-effect, mutable-operand,
and many-to-many junction-failure precedence.

### 2. Execute Set-Based Depth-First

The executor retains the existing eager-edge order, which is schema declaration
order rather than the order of `load{Name}` calls. For each configured edge it
completes that edge and all of its nested children before starting the next
sibling:

```text
loadNode(node, parents):
  for edge in node.configuredEdgesInSchemaDeclarationOrder:
    groups = loadEdge(edge, parents)
    selected = orderedDistinctVisibleTargets(groups)
    loadedTargets = loadNode(edge.target, selected)
    parents = attach(groups, loadedTargets)
  return parents
```

One direct edge step therefore executes in this order:

1. Run the target read interceptors once for the logical eager step.
2. Load and decode targets for all current parents.
3. Apply each parent's configured storage window.
4. Evaluate target LOAD privacy once over the ordered distinct selected
   targets.
5. Remove denied targets when `filterVisible()` is enabled, or fail in strict
   mode.
6. Recursively load the edge's nested children once for the ordered distinct
   retained targets.
7. Reattach the nested-loaded target copies to every parent association.

A many-to-many step first loads its junction associations, matching current
failure precedence, and then performs steps 1 through 7 for the distinct
target IDs. A junction-driver failure therefore still occurs before the target
interceptor pass.

This is set-based by edge, not breadth-first by graph depth. Breadth-first and
depth-first have the same asymptotic query count here because sibling edges
usually target different relationship shapes and cannot share a query.
Depth-first is preferred because it preserves today's observable path
precedence: a failure in the first schema-declared edge's nested child stops
work before the next sibling edge begins. Reversing the order of `load{Name}`
configuration calls does not reverse this execution order.

The guarantee is not "one query per depth" or even always "one physical query
per edge." A many-to-many edge normally needs a junction query plus a target
query, and large parent sets can be chunked. The guarantee is a bounded number
of physical queries per requested edge path and storage chunk, never one query
per parent group.

### 3. Define One Logical Edge Step

An eager path is evaluated once for the complete current parent set, including
when that set is empty. A logical edge step owns:

- one interceptor pass
- one frozen post-interceptor query shape
- one ordered target sequence assembled from any physical chunks
- one LOAD-privacy batch
- one recursive nested-edge pass

Physical chunking is below this boundary. It must not rerun interceptors,
split privacy evaluation into chunk-sized batches, or change denial and
exception order.

The empty case remains observable. If an eager edge is configured but no
parents or targets reach it, its interceptor pass and configured nested eager
steps still execute once with empty structural inputs. Driver reads and LOAD
privacy callbacks remain data-gated and receive no empty callback invocation.

### 4. Attach Results By Key

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

For grouped edges, the executor must not recursively load each association
list. It builds one ordered distinct target list, recursively loads that list
once, indexes the returned copies by target ID, and substitutes those copies
back into every association. A many-to-many target shared by several parents
is decoded, privacy-checked, and nested-loaded once while remaining attached
to every source.

## Delivery Phases

### Phase 1: Eliminate Nested N+1 Queries

Phase 1 uses the existing query and driver primitives. It changes grouped
has-many, has-one, and many-to-many recursion from:

```text
for each parent group:
  load nested edges for that group's targets
```

to:

```text
selectedTargets = edgeStep.orderedTargets
  .filterToSelectedWindowIds(groups)
  .distinctById()

nestedLoadedById = loadNestedEdges(selectedTargets).associateById()
groups = groups.replaceTargetsFrom(nestedLoadedById)
```

Belongs-to already invokes nested loading once for its batched target set and
must move through the same executor without changing results.

This phase deliberately retains the current Kotlin-side per-parent
`drop(offset).take(limit)` implementation. It fixes query-count growth but not
row overfetch. Explain output must say that the window is emulated in memory
rather than imply storage-level pagination.

No new relationship-aware driver method is required for phase 1. Phase 1 uses
at most one existing `Driver.query` call per target-table fetch; empty parent
sets and windows such as `limit(0)` can use zero. Many-to-many retains its
separate junction query. Phase 1 therefore inherits the driver's current
parameter limit. It does not claim generic large-parent chunking; that requires
a later driver-owned relationship-query capability.

### Phase 2A: Native Direct To-Many Windows

Phase 2A adds a relationship-aware driver capability for direct to-many
per-parent ordering, offset, and limit. PostgreSQL implements it with a native
window query and typed-array parent-key transport. Where that capability is
unavailable, phase-1 emulation remains. It does not change graph traversal,
privacy, interceptor, attachment, or failure semantics established by phase 1.

Belongs-to and has-one do not need a native window: each source has at most one
target, and the existing phase-1 fetch gates already avoid discarded rows.
Many-to-many retains phase-1 window emulation until a later phase can preserve
its junction-interceptor, pair-deduplication, and failure-precedence contracts.

### Phase 2B And Later: M2M Windows And Generic Chunking

Native many-to-many pagination and generic physical chunking are separate
follow-up features. They must not expand phase 2A's driver contract or delay
the direct to-many overfetch fix.

## Ordering Contract

Set batching and per-parent windows require one total effective target order.
Both phases use:

1. the caller's `orderBy` terms, in order
2. the target primary key ascending, unless the caller already ordered by the
   primary key

With no caller ordering, the effective order is primary key ascending. The
same effective order drives storage reads, Kotlin-side phase-1 grouping and
windows, phase-2A storage windows, privacy-batch order, and association order.

The executor computes this effective order before the `EAGER_LOAD` interceptor
pass. `QueryShape.orderBy` and explain output therefore describe the order that
storage will execute, including the framework-added primary-key term. Because
that would make `hasOrderBy` true even when the caller supplied none, query
shape metadata must also retain authored-order attribution, for example
`callerOrderBy` / `hasCallerOrderBy`, on typed and untyped interceptor views.
Policies that specifically require caller-authored ordering use that
attribution rather than `hasOrderBy`.

This is an intentional deterministic-ordering rule. Today tied caller terms
and queries without `orderBy` retain unspecified driver order. Adding the
primary-key term can change which tied rows enter a finite window, but prevents
phase 1 and phase 2A from selecting different rows for the same query.
It can also add a database sort to an otherwise unordered, unbounded eager
query; that cost is accepted in exchange for deterministic privacy, failure,
and chunk-merge order and must be visible in explain output.

For privacy and nested recursion, selected targets are flattened in effective
target order and deduplicated by target ID at first occurrence. Recursively
loaded target copies are the canonical copies for that edge step. Every source
association is rebuilt from those copies before the source entities are
returned, so attachment never retains a stale pre-recursion target instance.

## Per-Parent Pagination

For PostgreSQL, a direct to-many edge can use a window function:

```sql
WITH ranked_posts AS (
    SELECT
        posts.*,
        ROW_NUMBER() OVER (
            PARTITION BY author_id
            ORDER BY created_at DESC, id ASC
        ) AS "__entkt_rank_0"
    FROM posts
    WHERE author_id = ANY(?)
      AND published = true
)
SELECT *
FROM ranked_posts
WHERE "__entkt_rank_0" > CAST(:offset AS BIGINT)
  AND "__entkt_rank_0" <= CAST(:offset AS BIGINT) + CAST(:limit AS BIGINT)
ORDER BY created_at DESC, id ASC
```

The primary key is the deterministic final term required by the ordering
contract. With `offset = 0` and `limit = 5`, the database returns at most five
posts per user.

`"__entkt_rank_0"` is illustrative. The renderer must allocate a fresh alias
that cannot collide with registered storage columns or other selected aliases,
and synthetic ranking columns must be removed before target row maps are passed
to entity decoding.

Window-bound arithmetic uses a non-overflowing width. The Kotlin DSL values are
`Int`, but `offset + limit` must not be evaluated as an `Int` in generated code
or SQL.

The ranked input must include the frozen structural, caller, and interceptor
predicates before `ROW_NUMBER()` is assigned. Applying an interceptor predicate
after ranking could return fewer than the requested limit even when later
matching rows exist, and would not match phase-1 semantics.

Phase 2A selects `ROW_NUMBER() OVER (PARTITION BY ...)` as PostgreSQL's native
lowering. The driver-facing contract remains expressed in per-parent window
semantics rather than exposing this PostgreSQL strategy to generated code.

The parent keys are passed as one typed PostgreSQL array (`ANY(?)` or an
equivalent `unnest(?)` relation), not expanded into one bind per source. This
keeps direct relationship loads below PostgreSQL's scalar bind limit without
introducing physical chunks into phase 2A.

When neither a finite limit nor a positive offset exists, the native operation
may omit `ROW_NUMBER()` while retaining its typed-array relationship predicate;
the phase-1 fallback continues to use an ordinary batched `IN` query. An offset
without a limit still benefits from the window-function lowering because it
avoids transferring the discarded prefix for every parent.

## Many-To-Many Edges

A phase-1 many-to-many eager step performs:

1. One junction query for all source IDs. (Since the
   [junction read-interceptors RFC](junction-read-interceptors.md)
   phase 1, the junction entity's read interceptors run first with
   `ReadOperation.EAGER_JUNCTION` and their predicates narrow this
   query.)
2. Build the ordered distinct discovered target-value list exactly as today,
   including a nullable target value if malformed junction data contains one.
3. Run the target interceptor once with that complete list in its structural
   target-ID predicate.
4. Exclude nullable endpoints from storage matching and associations, then
   deduplicate valid `(source ID, target ID)` memberships.
5. Query eligible targets using all frozen target predicates and the effective
   target order.
6. Rebuild each source's eligible target list in that target order.
7. Apply each source's window, then privacy-check and recursively load the
   ordered distinct target union once.
8. Reattach canonical nested-loaded targets through the association map.

Any later native many-to-many phase must not rank raw junction rows before
target filtering or pair dedup.
For `loadTags { where(...); orderBy(...); limit(n) }`, it ranks distinct
eligible `(source, target)` memberships only after all caller and interceptor
target predicates have been applied, partitioned by source and ordered by the
effective target order. Otherwise duplicate memberships or filtered targets
can consume the window and change the result.

Preserving current failure precedence also requires association discovery to
complete before the target interceptor. A native many-to-many strategy may use
a separate discovery pass or a driver-owned opaque association plan, but it
must not move target-interceptor execution ahead of a junction I/O failure.
Until a native strategy satisfies both requirements, many-to-many retains the
phase-1 lowering.

The executor must evaluate a shared target's LOAD privacy once per eager step,
not once for every source that references it.

## Privacy And Interceptor Semantics

The optimization preserves the privacy model and nondisclosure guarantees. It
explicitly changes the unit of nested callback invocation from a parent group
to a logical eager edge step. Callback outcomes that depend on batch size,
membership, structural predicate shape, or invocation count are not promised
to match the old per-group execution, even when the callback is otherwise
pure.

- Root LOAD privacy completes before eager loading starts.
- The terminal captures one `PrivacyContext`; root interceptors, root LOAD
  privacy, and every eager and nested step receive that exact object.
- Each logical eager edge step runs its target read interceptors exactly once
  with `ReadOperation.EAGER_LOAD` and the complete edge path, even when its
  current parent set is empty.
- Grouped nested interceptors currently run once per populated parent group.
  Phase 1 intentionally changes that count to once for the ordered union. An
  interceptor must describe the eager query as a whole, not rely on being
  invoked once per parent.
- Interceptors run before physical parent-ID chunking. Their post-interceptor
  shape is frozen and reused by every chunk; chunking never reruns application
  interceptor code.
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

Batch privacy rules receive one ordered distinct target batch per logical eager
step. Scalar rules adapt over that batch in order. Empty target batches invoke
no privacy rule, as today.

Combining groups necessarily changes item-level callback and failure order.
Today a nested rule can be invoked separately for group A and then group B.
After phase 1 it receives one batch containing the ordered union. The new
contract is:

- direct and many-to-many target order follows the logical target-query order
  after per-parent windows, with duplicate target IDs removed at first
  occurrence
- physical chunks are stably merged into that logical order before privacy
- returned strict denials are projected by the existing batch-privacy contract
  from that one ordered batch
- a callback exception or strict denial stops nested work and every later
  schema-declared sibling edge

The executor does not promise to preserve which old parent-group invocation
would have failed first. That per-group behavior is the N+1 mechanism being
removed.

## Observable Compatibility

The public edge-selection syntax is outside this executor RFC. Entity result
types and these execution contracts remain stable:

- root query and root LOAD privacy complete before eager loading
- sibling paths and their descendants execute depth-first in schema
  declaration order
- windowing precedes eager LOAD privacy
- `filterVisible()` never replacement-scans
- eager denial paths and `EdgeState` distinctions are preserved
- one terminal-captured `PrivacyContext` is shared throughout the graph

These behaviors intentionally change:

- a grouped nested interceptor runs once per logical edge step instead of once
  per populated parent group, and its structural `IN` predicate contains the
  complete logical target-match set for that step: source IDs for
  has-many/has-one target FKs, ordered distinct non-null source FK values for
  belongs-to target IDs, and discovered target values for many-to-many target
  IDs
- nested privacy rules receive one ordered distinct union batch instead of one
  batch per group
- item-level callback and first-failure order follow that union rather than the
  old group iteration
- effective eager ordering gains the deterministic primary-key rule described
  above; interceptors see that effective order and gain separate authored-order
  attribution

These are callback and ordering changes even though they require no public
method rename. Migration notes must call them out when phase 1 ships.

## Phase 2A Driver Contract

Phase 1 uses `Driver.query`. Phase 2A adds a strategy-neutral operation for a
direct to-many relationship. The runtime owns the logical plan and fallback;
generated code supplies typed schema metadata, entity decoding, IDs, and edge
attachment adapters. A conceptual cross-module shape is:

```kotlin
@EntktInternal
data class DirectToManyQuery(
    val targetTable: String,
    val sourceKeys: List<Any>,
    val targetForeignKey: String,
    val targetPredicates: List<Predicate<*>>,
    val effectiveOrder: List<OrderField<*>>,
    val window: PerParentWindow,
)

@EntktInternal
data class PerParentWindow(
    val offset: Int,
    val limit: Int?,
)

@EntktInternal
data class RelatedRow(
    val sourceKey: Any,
    val targetRow: Map<String, Any?>,
)

@EntktInternal
data class RelatedRows(
    val rows: List<RelatedRow>,
    val strategy: EagerWindowStrategy,
)

enum class DirectToManyWindowCapability { NATIVE, EMULATED }

fun directToManyWindowCapability(): DirectToManyWindowCapability
fun queryDirectToMany(query: DirectToManyQuery): RelatedRows
```

The names above are the accepted semantic boundary, not a promise that these
exact data classes must be copied verbatim. The implemented SPI must preserve
the same information and keep PostgreSQL-specific ranking concepts out of
generated code.

Before the driver call, the target interceptor still sees the complete logical
structural predicate (`targetForeignKey IN sourceKeys`) exactly once. After
interception, the runtime retains that framework-owned relationship constraint
separately from caller and interceptor predicates. `targetPredicates` therefore
contains the remaining frozen target predicates; the driver lowers the
relationship constraint itself using the typed parent array.

`RelatedRows` returns target rows in the same global effective target order as
phase 1, with each row's source association key carried outside the entity row
map. Synthetic ranking aliases are driver-private and never reach generated
entity decoding. The runtime groups by `sourceKey`, runs the existing logical
privacy/nested pass, and attaches generated entity copies exactly as phase 1
does.

Any executor, relationship-plan, or driver type referenced across the runtime,
driver, and generated application-module boundary must be public and guarded by
`@EntktInternal`; Kotlin `internal` is not visible across those modules. Types
used only inside one generated file can remain private. Every new generated
query member name must also participate in generated-member collision
validation before code emission.

Driver capabilities state whether direct to-many per-parent windows are:

- natively supported
- emulated in memory with explicitly disclosed overfetch behavior

The emulated phase-1 behavior is the mandatory compatibility fallback whenever
a driver lacks native direct to-many window support. Phase 2A does not reject
an eager query that phase 1 accepted. Silent full-result overfetch must not be
reported as native per-parent pagination.

## Large Parent Sets

Phase 2A's PostgreSQL direct to-many lowering sends parent keys through one
typed array parameter. Parent cardinality therefore does not consume one bind
per source and does not require physical chunks for this path. Caller and
interceptor predicates still consume the ordinary statement bind budget and
retain the driver's deterministic pre-I/O limit checks.

Generic chunking remains a later phase. When it is designed, the driver must
respect the complete bind budget, including target predicates; the framework
must not guess a universal safe `IN` size because database limits differ and
non-structural predicates consume parameters too.

A driver may avoid chunks through an array/table-valued parameter or execute
several physical queries. If it chunks, it must preserve:

- per-parent result ordering
- source encounter order when attaching results
- one logical privacy batch per eager step, even if storage used several
  physical chunks
- deterministic failure and denial reporting

All physical target reads for one logical relationship query must observe one
database snapshot. On PostgreSQL, independent statements under ordinary
`READ COMMITTED` do not satisfy this requirement. A conforming lowering uses a
single target-reading statement, an explicitly shared snapshot, or another
mechanism with equivalent consistency. Otherwise concurrent writes could make
the merged rows differ from every possible unchunked query result.

If chunks execute concurrently, error selection is still by deterministic
chunk ordinal rather than completion race. Physical chunk count can be exposed
to a future tracing facility but does not alter the logical result contract.

Before choosing chunk sizes, the driver accounts for binds contributed by
target predicates and ordering expressions. If that fixed, non-relationship
cost alone exhausts the backend limit, the driver must use an alternate
lowering or reject deterministically before the first physical target read;
splitting relationship IDs cannot make such a query valid.

Phase 1 and every phase-2A fallback explicitly inherit the current
`Driver.query` limit. Merging parent
groups can reach that limit sooner than the old per-group recursion, so phase 1
must be documented and tested only within the supported parameter range. Large
direct to-many parent sets are handled by the phase-2A PostgreSQL typed-array
path; other relationship shapes require a later capability. Until then, the
PostgreSQL driver rejects an over-limit fallback statement
deterministically before any I/O with an actionable `PostgresBindLimitException`
(counting the statement's complete bind list, not only relationship IDs); the
root query of an eager load may already have executed, but the invalid
relationship statement never reaches PostgreSQL.

## Explain

The existing `QueryPlan` remains a structural description; it does not predict
runtime query multiplicity or row counts. Phase 1 adds a typed,
framework-owned eager-execution metadata field that identifies, per eager
path:

- set-batched nested execution
- the canonical effective order
- in-memory per-parent window emulation and its overfetch risk

This metadata must not share the application/interceptor annotation map, whose
keys are caller-controlled and last-writer-wins. An application annotation
cannot override the framework's reported execution strategy.

Phase 2A adds native-versus-emulated window metadata. It does not put
estimated chunk counts into `QueryPlan`, because those depend on runtime parent
sets and driver choices.

Explain remains a structural walk rather than a simulation of runtime
fail-fast execution. It may describe later sibling steps even though a real
read would stop before them after an earlier failure. Rejection selection in
the plan remains root-first and depth-first.

Runtime rows-fetched, rows-retained, and physical-chunk metrics belong to the
separate query-observability feature. This RFC only requires the internal
executor and driver result to make those facts available to a future tracer.
It does not change current bind-value rendering or define a redaction policy.

## Implementation Direction

A safe sequence is split into independently reviewable phases.

### Phase 1: Nested Set Batching

1. Add query-count and callback-trace tests that pin the current grouped N+1,
   schema-declaration sibling order, depth-first precedence, and empty passes.
2. Capture configured eager topology, then snapshot each eager query only when
   depth-first execution reaches it.
3. Introduce one internal edge-step result containing ordered target entities
   plus source-to-target-ID associations.
4. For grouped direct edges, recursively load the ordered distinct union once,
   then rebuild every group from canonical nested-loaded copies.
5. Apply the same algorithm to many-to-many targets, preserving pair dedup,
   target ordering, shared associations, and junction-before-interceptor
   precedence.
6. Move belongs-to and has-one through the same executor and verify parity.
7. Retain current in-memory windows, expose that strategy in explain output,
   and remove per-group recursive calls.
8. Update `QueryPlan` KDoc, `docs/04-queries.md`, `docs/06-privacy.md`, the ORM
   design overview, and the breaking-change log so none describe per-parent
   nested invocation as current behavior.

### Phase 2A: Native Direct To-Many Windows

1. Introduce the runtime-owned direct-to-many relationship plan, result
   envelope, and native-versus-emulated driver capability.
2. Preserve the framework structural relationship constraint separately from
   caller and interceptor predicates after the one logical interceptor pass.
3. Add PostgreSQL typed-array parent-key binding and a `ROW_NUMBER() OVER
   (PARTITION BY ...)` lowering with collision-safe private rank aliases.
4. Return source-key/target-row envelopes in canonical effective target order,
   stripping every synthetic driver column before entity decoding.
5. Switch direct to-many eager steps to the native capability when available;
   keep phase-1 emulation as the mandatory fallback.
6. Expose native-versus-emulated window metadata in explain output.

### Phase 2B And Later

1. Design native many-to-many windowing without changing junction discovery,
   interceptor, filtering, pair-deduplication, or failure precedence.
2. Design generic driver-owned physical chunking and stable logical-result
   merging for relationship shapes that cannot use a single array/table input.

The final architecture should be a runtime execution engine with generated
typed metadata and attachment adapters, rather than reproducing the graph
algorithm in every generated query class. A temporary generated phase-1 seam
is acceptable only if it preserves this edge-step contract and is removed when
the shared executor lands.

## Test Requirements

### Phase 1

Before nested set batching ships, tests should prove:

- exact nested query counts do not grow with populated parent groups while the
  logical parent union is within the current driver limit
- a two-level direct graph and a two-level many-to-many graph each recurse once
  per configured path, not once per outer group
- reversed `load{Name}` call order does not change schema-declaration execution
  order
- a descendant failure under an earlier sibling occurs before any callback or
  query for a later sibling
- root privacy still completes before every eager step
- a grouped nested interceptor runs once with the complete structural
  target-match set for its edge shape—has-many/has-one source IDs, belongs-to
  source FK values, or many-to-many discovered target values—plus
  `ReadOperation.EAGER_LOAD`, the complete path, and the exact root
  `PrivacyContext`
- configured empty eager paths each receive exactly one interceptor pass while
  driver reads and privacy callbacks remain skipped
- one ordered distinct privacy batch is used per logical edge step, including
  deterministic first-denial projection and thrown-rule propagation
- strict denial retains the complete root-to-target path and stops later
  descendants and siblings
- `filterVisible()` affects only the configured edge, never replacement-scans,
  and denied targets do not trigger nested reads
- belongs-to, has-one, has-many, and many-to-many attachments use canonical
  nested-loaded target copies without changing root or association order
- duplicate many-to-many pairs collapse, a shared target remains attached to
  every source, and that target is decoded, privacy-checked, and nested-loaded
  once per logical step
- malformed many-to-many null target values remain visible in the interceptor's
  structural predicate but never become associations or storage matches
- a many-to-many junction failure still precedes target-interceptor rejection
- tied and absent caller ordering use the documented primary-key rule in both
  finite and unbounded eager queries, while interceptor shapes distinguish
  authored from effective ordering
- `EdgeState.Unloaded`, `Loaded(null)`, and `Loaded(emptyList())` remain
  distinguishable
- explain output uses depth-first schema order and labels in-memory window
  emulation without claiming row-fetch reduction, and application annotations
  cannot override framework execution metadata
- new generated query members participate in collision validation

### Phase 2A

Before native direct to-many windows ship, tests should prove:

- per-parent limit and offset select exactly the same rows as phase 1
- PostgreSQL does not fetch rows outside finite per-parent windows
- generated ranking aliases cannot collide with schema columns and never reach
  entity row decoding
- extreme `offset` and `limit` values cannot overflow window-bound arithmetic
- offset without a limit uses native lowering where supported and otherwise
  retains phase-1 emulation
- PostgreSQL's window lowering uses the canonical effective order
- typed-array transport supports parent sets larger than the scalar bind limit
  without physical chunks or repeated interceptor/privacy callbacks
- interceptors see one full logical relationship constraint while the driver
  lowers only that separately attributed constraint through the parent array
- direct association maps remain correct for several parents, empty groups,
  duplicate source keys, and nested loads
- fixed non-relationship binds that exhaust the backend limit use an alternate
  lowering or reject before physical target I/O
- a generated application and custom driver compile against every public
  `@EntktInternal` cross-module SPI type
- typed explain metadata reports native versus emulated window strategy
  accurately

## Resolved Decisions

- Traverse set-based eager steps depth-first, not breadth-first.
- Execute siblings in schema declaration order, not configuration-call order.
- Merge grouped parents into one logical interceptor/privacy/nested step.
- Use one canonical effective order with primary-key ascending as the default
  and tie-breaker.
- Deliver nested N+1 elimination before native per-parent pagination.
- Keep physical chunking below the logical interceptor and privacy boundary.
- Use phase-1 emulation whenever native relationship windows are unavailable.
- Scope phase 2A to direct to-many edges; belongs-to and has-one need no native
  window, and many-to-many remains emulated until a later phase.
- Use PostgreSQL window functions, not lateral queries, for phase-2A native
  direct to-many pagination.
- Transport direct relationship parent keys through one typed PostgreSQL array
  rather than scalar bind expansion or physical chunking.
- Keep relationship planning and fallback in the runtime; generated code owns
  typed metadata, decoding, IDs, and attachment adapters.
- Keep generic physical chunking out of phase 2A.

## Deferred Decisions

- Native many-to-many window-query and association-plan shape.
- The threshold, transport, and stable-merge mechanism for generic physical
  parent-ID chunking where a single array/table input is unavailable.

## Related Features

- [Generated Edge Loading API](../../implemented-features/query/generated-edge-loading-api.md)
- [Request-Scoped Entity Loading](request-scoped-entity-loading.md)
- [Query Observability Diagnostics](query-observability-diagnostics.md)
- [Driver Capability Matrix](../tooling/driver-capability-matrix.md)
- [Projection / Select API](projection-select-api.md)
