# RFC: Eager Loading — Native M2M Windows And Chunking

## Status

Partially implemented. Set-based nested execution shipped in phase 1, and
PostgreSQL-native direct to-many windows shipped in phase 2A. The remaining
work is native many-to-many windows and generic physical chunking.

## Implemented Baseline

The runtime executes each selected eager path once for the complete current
parent set, depth-first, with siblings in schema declaration order. Nested
loading does not issue a separate query for each parent group.

Direct to-many edges support PostgreSQL storage windows using
`ROW_NUMBER() OVER (PARTITION BY ...)`, with parent keys transported through
one typed array parameter. Drivers without that capability use in-memory
per-parent windows. Many-to-many windows remain emulated.

Generated code captures typed selections and attachment adapters; the runtime
owns graph traversal, grouping, privacy, and storage coordination. See
[Queries](../../04-queries.md), the
[graph loader](../../../runtime/src/main/kotlin/entkt/runtime/query/execution/EntityGraphLoader.kt),
and the [relationship driver contract](../../../runtime/src/main/kotlin/entkt/runtime/driver/DatabaseDriver.kt).
The [Generated Edge Loading API](../../implemented-features/query/generated-edge-loading-api.md)
owns the public `load{Name}` selection surface.

The earlier phase-1 and phase-2A implementation plans are complete. They are
summarized here only as constraints on the remaining optimizations.

## Ordering And Privacy Contract

Each eager step has one total effective target order: caller `orderBy` terms,
then primary key ascending unless the caller already ordered by that key.
Without caller ordering, primary key ascending is the default.

The effective order is computed before target interceptors and drives storage,
per-parent windows, privacy-batch order, and attachment. Authored ordering is
reported separately through `callerOrderBy` / `hasCallerOrderBy`.

Preserve these rules for native M2M execution and chunking:

- Root LOAD privacy completes before eager loading starts.
- Root and nested work share the exact terminal-supplied `ViewerContext`.
- Target interceptors run once per logical step, including empty parent sets,
  before physical chunking. Each chunk reuses the frozen post-interceptor shape.
- Per-parent windows are selected before target LOAD privacy.
- Strict denial fails the read with the full edge path. `filterVisible()` only
  removes denied targets from the selected window; it does not refill it.
- Privacy and nested recursion receive one ordered distinct target union.
  Scalar rules adapt over that batch; empty batches invoke no privacy rules.
- Nested edges receive only retained targets. Shared targets are loaded once
  and reattached through canonical nested-loaded copies without losing
  source associations or their order.
- Physical chunk boundaries must not change callback counts, privacy order,
  deterministic failure selection, or later-sibling short-circuiting.

## Native Many-To-Many Windows

Current M2M execution discovers junction associations, loads eligible targets,
rebuilds each source's target list, and applies its window in Kotlin. A native
strategy must preserve that logical sequence:

1. Run the junction discovery pass, including its interceptors, before the
   junction driver read. See [Junction Read Interceptors](junction-read-interceptors.md).
2. Build the ordered distinct discovered target-value list, retaining a null
   target value in the interceptor-visible structural predicate if present in
   malformed junction data.
3. Run the target interceptor once with the complete discovered list.
4. Exclude nullable endpoints from storage matching and associations, then
   deduplicate valid `(source ID, target ID)` memberships.
5. Apply all frozen structural, caller, and interceptor target predicates.
6. Rank distinct eligible memberships per source in effective target order,
   and apply each source's offset and limit.
7. Privacy-check and recursively load the ordered distinct selected target
   union once, then reattach through the association map.

Do not rank raw junction rows before target filtering or pair deduplication:
otherwise duplicate memberships or filtered targets consume the window and
change which entities are selected. A shared target receives LOAD privacy
once per eager step, regardless of how many sources reference it.

Discovery must finish before target interception. A native lowering may use a
separate discovery pass or a driver-owned association plan, but cannot move
target callbacks ahead of a junction I/O failure.

The driver result must retain source-to-target associations as well as
canonical target order; flat target rows alone are insufficient. Keep M2M
emulation until a native strategy satisfies the same ordering, privacy,
null-endpoint, and failure contracts.

Window arithmetic must not overflow `Int` when adding offset and limit. Any
synthetic ranking columns need collision-safe names and must be removed before
entity decoding, as in the implemented direct to-many lowering.

## Generic Physical Chunking

The PostgreSQL direct to-many array path does not need one bind per parent.
Other relationship shapes still use the ordinary query bind budget. Generic
chunking remains open; the runtime must not guess a universal safe `IN` size.

Drivers may use array/table-valued parameters or several physical reads. A
chunking strategy must account for all binds, including caller/interceptor
predicates and ordering expressions. If the fixed non-relationship cost alone
exhausts the backend limit, reject before the first physical target read or use
an alternate lowering. Splitting parent IDs cannot fix that case.

Preserve:

- per-parent ordering and source encounter order on attachment;
- one logical interceptor pass and privacy batch per eager step;
- stable target deduplication and merge order;
- deterministic failure and denial reporting.

All physical target reads for one logical relationship query must observe one
database snapshot. Independent PostgreSQL statements at `READ COMMITTED` do
not meet that requirement. A conforming lowering needs a single target-reading
statement, an explicitly shared snapshot, or equivalent consistency. Otherwise
the merged result can differ from every possible unchunked query result.

If chunks run concurrently, choose errors by deterministic chunk ordinal,
not completion race. Physical chunks remain an implementation detail rather
than a new application callback boundary.

Until another capability lands, fallback statements inherit the driver's bind
limit. PostgreSQL rejects an over-limit statement before that statement's I/O
with `PostgresBindLimitException`; an eager load's root query may already have
executed. Do not describe existing fallbacks as generically chunked.

## Explain And Diagnostics

The original implementation recorded native/emulated strategy metadata in
query plans, but the generated explain family is no longer exposed. Future
diagnostics should derive strategy information from current runtime planning
and keep it separate from caller-controlled interceptor annotations.

A future native M2M strategy must report its actual window strategy. Inspection
must not imply native pagination while the runtime fetches and slices every
eligible target. Dry-run inspection should be a structural walk and may describe later siblings
that a failing runtime read would never reach.

Runtime physical-query counts, rows fetched, rows retained, and overfetch
metrics belong to [Query Observability Diagnostics](query-observability-diagnostics.md).
Chunk estimates cannot be inferred solely from a structural plan without the
runtime parent set and driver strategy.

## Open Decisions

- Native M2M driver operation and association-plan shape.
- Capability and fallback selection for native M2M windows.
- Chunking thresholds, transport, snapshot guarantees, and stable merge strategy
  when a single array/table input is unavailable.

## Test Requirements For Remaining Work

Keep existing nested eager, effective ordering, native direct to-many,
interceptor, privacy, and bind-limit tests as regression coverage. Add focused
coverage when implementing the remaining strategies:

- native/emulated M2M equivalence with duplicate memberships, null endpoints,
  target filtering, ties, offsets, empty sets, and shared targets;
- target filtering and pair deduplication occur before ranking;
- junction failures still precede target callbacks;
- native M2M execution reduces row overfetch without changing selected targets;
- chunk boundaries preserve ordering, associations, callback counts, and failures;
- full bind budgeting rejects impossible queries before target I/O;
- concurrent writes cannot produce a mixed-snapshot merged target result;
- explain reports the strategy actually selected.

## Related Features

- [Generated Edge Loading API](../../implemented-features/query/generated-edge-loading-api.md)
- [Request-Scoped Entity Loading](request-scoped-entity-loading.md)
- [Driver Capability Matrix](../tooling/driver-capability-matrix.md)
- [Projection / Select API](projection-select-api.md)
