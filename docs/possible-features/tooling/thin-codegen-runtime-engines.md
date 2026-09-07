# RFC: Runtime Engines — Remaining Verification And Documentation

## Status

The core extraction is implemented. This note retains the verification and
contributor-documentation work from the original RFC; it is no longer a plan
to introduce runtime execution engines.

## Implemented Baseline

Generated code now supplies schema-specific types, descriptors, mappings,
converters, and dependency wiring. Ordinary Kotlin runtime code owns the shared
execution paths:

- `EntityQueryBuilder`, `ReadQueryExecutor`, and `EntityGraphLoader` own row
  terminals and graph loading.
- `EntityRepository`, `GeneratedIdRepository`, and `ExplicitIdRepository` own
  repository entry points and construct shared executors.
- `PendingMutation` owns the single-use `configure`, `save(viewerContext)`, and
  `saveAndLoad(viewerContext)` lifecycle.
- Scalar and bulk CREATE, UPDATE, and DELETE operations live in
  `runtime/mutation/execution`; `MutationExecutor` owns transaction and failure
  coordination.
- Runtime privacy, validation, and hook evaluators own callback loops;
  generated code supplies typed inputs and registration adapters.
- Generated before-hook states are immutable. Transforming hooks return the
  state consumed by the next hook.

See the [generated API overview](../../../codegen/README.md),
[runtime repository base](../../../runtime/src/main/kotlin/entkt/runtime/repository/EntityRepository.kt),
[graph loader](../../../runtime/src/main/kotlin/entkt/runtime/query/execution/EntityGraphLoader.kt),
and [mutation executor](../../../runtime/src/main/kotlin/entkt/runtime/mutation/execution/MutationExecutor.kt).

The former migration phases, proposed engine names, and pre-extraction source
counts are obsolete. Performance completion must be supported by reproducible
measurements rather than inferred from the existence of the engines.

## Ownership Contract To Preserve

Generated code owns schema-specific Kotlin names and types, direct row codecs,
ID accessors, candidate and patch construction, edge attachment, and small
adapters. Runtime code owns reusable lifecycle ordering, privacy and validation
evaluation, graph traversal, storage coordination, and failure handling.

A generated method may validate typed DSL state, construct an immutable
operation description, and delegate. It must not copy generic execution loops
or transaction state machines per entity. Driver-specific lowering stays
behind `DatabaseDriver`.

Keep compile-time typing without reflection on the row-processing path.
Localized internal erasure must remain behind `@EntktInternal`, be established
through typed factories, and have focused contract coverage. Framework engines
must not retain per-execution mutation values after completion.

## Remaining Verification

### Generated Size And Performance

Publish a reproducible comparison against a documented pre-extraction revision
using the same schema fixture and build environment. Include:

- generated source lines and files per entity;
- generated classfile count and byte size;
- clean application compilation and incremental compilation after a schema change;
- allocations per decoded row and simple query throughput;
- eager graph throughput and scalar/bulk mutation latency;
- startup and client-construction time.

Choose explicit regression thresholds before interpreting results. Verify that
source reduction did not introduce additional database statements, row
fetches, per-row reflection, or avoidable adapter allocations. Existing
correctness tests do not establish throughput or allocation parity.

### Ownership And Semantic Coverage

Audit remaining emitters against the ownership contract. Retain schema-specific
code when it provides type safety; record any residual generic algorithm as a
bounded follow-up rather than restarting the extraction.

Use the existing runtime, generated compile, integration, and driver tests to
identify coverage gaps. Relevant contracts include:

- result variants, typed failures, write states, and returned LOAD disclosure;
- cancellation, first-failure order, suppressed exceptions, and rollback-only state;
- caller-owned and EntKt-owned commit, rollback, and uncertain outcomes;
- callback order, immutable hook-state transformation, and empty batches;
- frozen interceptor inputs, native/emulated windows, and driver data gates;
- shared-target deduplication, attachment identity, and nested execution order;
- Kotlin/Java call-site typing, member collisions, and internal SPI boundaries.

Do not restore old production algorithms merely to compare them. Use recorded
contracts, fixtures, or a separate historical checkout for any missing parity
measurement. Driver tests continue to own storage semantics; runtime tests own
execution ordering.

## Contributor Documentation

Reconcile architecture and generated API documentation with the current types,
including immutable hook states and the shared `ReadOnlyEntClient`. Document:

- how to add schema-specific metadata or an adapter;
- how to change a shared engine phase;
- where runtime, codegen compile, integration, and driver tests belong;
- cross-module visibility and the `@EntktInternal` boundary;
- how to reproduce size and performance measurements.

Generated KDoc should explain application behavior without presenting engine
implementation classes as stable application extension points.

## Open Decisions

- Benchmark thresholds and the reproducible comparison fixture.
- Whether runtime/generated SPI compatibility needs an explicit version marker
  before artifacts evolve independently.
- Whether a typed row view belongs in the later driver SPI work; it is not a
  prerequisite for the extraction already implemented.

Close this note when the ownership audit, remaining documentation, and measured
completion criteria are recorded. Do not mark unmeasured performance claims as
verified.

## Related Features

- [Eager Loading: Native M2M Windows And Chunking](../query/set-based-eager-graph-loader.md)
- [Structured Mutation Pipeline](../mutation/structured-mutation-pipeline.md)
- [Modular Driver SPI](modular-driver-spi.md)
- [Driver Capability Matrix](driver-capability-matrix.md)
- [Codegen Plugin Hooks](codegen-plugin-hooks.md)
- [Same-Module Schema Processing](same-module-schema-processing.md)
