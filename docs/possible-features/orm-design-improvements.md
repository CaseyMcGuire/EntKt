# EntKt ORM Design Improvements

## Status

Repository audit notes from 2026-08-17, reconciled with the code on 2026-09-07.
These are candidate directions, not a committed roadmap or public API contract.
Implemented foundations are identified below; linked active notes retain open
follow-ups.

This page preserves the high-level findings in one place. The linked RFCs own
the detailed semantics and may be accepted, revised, split, or rejected
independently.

## Objective

EntKt should be a clean, ergonomic, performant Kotlin ORM whose behavior is
explicit at the call site and predictable under privacy, pagination,
transactions, concurrency, and large relationship graphs.

The audit found that the highest-value work is mostly about execution strategy
and semantic boundaries rather than adding more scalar types.

## Candidate Directions

### 1. Explicit, Set-Based Edge Loading

Keep relationship loading explicit at the query call site, then execute each
selected edge path once for the complete current parent batch rather than once
per parent group. Preserve `EdgeState` and per-parent query semantics without
making one large SQL statement the public contract. *(Phase 1 of this —
set-based nested execution with in-memory per-parent windows — shipped
2026-08-19; phase 2A — PostgreSQL-native per-parent storage windows for
direct to-many edges, with typed-array parent-key transport — shipped
2026-08-20. Native many-to-many windows and generic chunking remain
open.)*

Public edge-selection syntax and execution strategy are separate design
decisions. The executor consumes an immutable edge-load plan; generated API
names follow the schema declaration-name contract.

Detailed notes:

- [Generated Edge Loading API](../04-queries.md#edge-loading)
- [Set-Based Eager Graph Loader](query/set-based-eager-graph-loader.md)
- [Schema Declaration Names As Generated API](../02-schema.md#names)

### 2. Query-Time Visibility Predicates

Separate set-based row visibility from entity LOAD authorization. Apply typed
visibility predicates before ordering, pagination, traversal, aggregation, and
projection; retain arbitrary Kotlin LOAD rules as the final materialization
authority.

Detailed note: [Query-Time Visibility Predicates](privacy-validation/query-time-visibility-predicates.md).

### 3. Transaction-Aware Mutation Phases

Give mutations explicit phases for normalization, input validation, derivation,
authorization, invariant validation, persistence, post-persist observation, and
post-commit effects. In particular, distinguish `afterPersist` from
`afterCommit` so rollback cannot be mistaken for external-effect rollback.

Detailed note: [Structured Mutation Pipeline](mutation/structured-mutation-pipeline.md).

### 4. Typed Projections And Stable Pages

Add column projections so callers do not hydrate large unused fields. Add
cursor pages with deterministic ordering and a primary-key tie-breaker. Keep
offset windows as a simple storage primitive rather than the preferred API for
changing feeds.

Detailed notes:

- [Projection / Select API](query/projection-select-api.md)
- [Cursor Pagination](query/cursor-pagination.md)

### 5. Coherent Concurrency Control

Treat version locking, compare-and-set expectations, pessimistic locks, and
delete consistency as one model. Use shared terminology and a structured
conflict result while preserving explicit opt-in and driver capability checks.

Detailed note: [Coherent Write Concurrency Model](mutation/coherent-write-concurrency.md).

### 6. Thin Generated APIs Backed By Runtime Engines

The core extraction has landed: shared runtime code now owns query, graph,
privacy, validation, and mutation execution. Generated code supplies types,
descriptors, converters, and wiring. Remaining work concerns ownership audits,
contributor documentation, and reproducible size/performance verification.

Detailed note: [Thin Codegen And Runtime Execution Engines](tooling/thin-codegen-runtime-engines.md).

### 7. Modular Driver SPI And Structured Capabilities

Split the broad driver interface into cohesive contracts and make native,
emulated, and unsupported behavior explicit. Validate feature requirements
before execution instead of relying on scattered booleans and late failures.

Detailed notes:

- [Modular Driver SPI](tooling/modular-driver-spi.md)
- [Driver Capability Matrix](tooling/driver-capability-matrix.md)

### 8. Honest Migration Risk And Online DDL

Replace the binary safe/manual vocabulary with risk categories that distinguish
additive, data-dependent, blocking, and destructive operations. Offer explicit
PostgreSQL online strategies without silently choosing operationally expensive
behavior.

Detailed notes:

- [Migration Risk And Online DDL](tooling/migration-risk-and-online-ddl.md)
- [Migration Diagnostics](tooling/migration-diagnostics.md)

### 9. Same-Module Schema Processing

Explore source-based schema processing so applications are not forced to place
schemas in a separate compiled module. Preserve the current explicit schema DSL
while adding source positions, incremental generation, and cacheable builds.

Detailed notes:

- [Same-Module Schema Processing](tooling/same-module-schema-processing.md)
- [Gradle Developer Experience](tooling/gradle-dx.md)

## Important Dependencies

Some directions should be designed together:

- Query-time visibility should precede privacy-correct projection, counts, and
  public cursor pagination.
- Set-based edge loading should use the modular driver capability model for
  native per-parent windows and deterministic physical chunking, while keeping
  emulated support explicit in plans and diagnostics.
- Implement new eager strategies and mutation phases in the existing runtime
  engines, with generated adapters only for schema-specific facts.
- `afterCommit` semantics depend on explicit transaction ownership and nested
  transaction behavior.
- Same-module processing should preserve the same resolved schema model used by
  migrations and code generation rather than create a second schema dialect.

## Foundations To Preserve

The audit recommends retaining these existing choices:

- explicit `EdgeState.Unloaded` versus `Loaded(null)` and
  `Loaded(emptyList())`
- canonical `ReadResult`, `MutationResult`, and `TransactionResult` contracts
- explicit terminal `ViewerContext` and entity reads, with collection-based
  counts and aggregates rather than generated raw terminals
- typed field, inverse-edge, and junction handles
- fail-closed privacy and explicit privacy-as-absence projection
- generated-member collision validation
- refusal to silently generate destructive migrations
- read interceptors that may reduce or reject a query but cannot broaden it
