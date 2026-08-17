# RFC: Thin Codegen And Runtime Execution Engines

## Status

Possible future architectural direction. This is not implemented.

## Summary

Keep generated code focused on Kotlin types, names, metadata, and narrow
adapters. Move general query, eager-load, lifecycle, privacy, validation, and
mutation algorithms into reusable runtime engines.

The goal is smaller generated output and one implementation of framework
semantics without sacrificing typed application APIs or adding reflection to
hot paths.

## Motivation

Generated repositories currently contain substantial execution logic for each
entity: query interception, materialization, privacy evaluation, eager loading,
create/update/delete lifecycles, bulk operations, and transaction failure
mapping.

That approach makes generated files self-contained, but it has costs:

- application compile time and bytecode grow with entity count
- lifecycle fixes must be emitted correctly into every generated artifact
- behavior is harder to optimize globally
- query and mutation algorithms are tested partly as generated source strings
- driver and result-contract changes cause broad generated churn

Generated source should describe what is entity-specific. Runtime engines
should execute what is framework-generic.

## Target Boundary

Generated code continues to own:

- entity data classes and `Edges`
- typed query columns and edge references
- create/update DSL properties
- typed privacy, validation, and hook contexts
- row decoder and write-value encoder adapters
- immutable schema, field, edge, and index metadata
- repository method names and result types
- generated-member collision validation

Runtime code owns:

- query-spec freezing and interceptor execution
- row-terminal execution and result capture
- eager graph planning and execution
- privacy and validation batch evaluation
- mutation phase orchestration
- transaction/write-state propagation
- driver capability preflights
- common bulk-operation algorithms

## Possible Internal Shape

Generated repositories could delegate through typed adapters:

```kotlin
internal object UserRuntimeModel : RuntimeEntityModel<User, UserId> {
    override val schema = User.SCHEMA
    override fun decode(row: RowView): User = User(/* typed casts */)
    override fun key(entity: User): UserId = entity.id
}

public fun all(): ReadResult<List<User>> =
    readEngine.all(UserRuntimeModel, frozenSpec(), eagerPlan())
```

The exact interfaces are internal. Application code still sees generated
`UserQuery`, `UserRepo`, and typed results.

Mutation builders similarly produce an immutable typed mutation description
consumed by a runtime lifecycle engine. Hooks retain generated typed views;
only orchestration moves.

## No Reflection In Hot Paths

The runtime engine should not discover fields or invoke constructors through
reflection for every row. Codegen supplies direct decoders, encoders, key
accessors, and edge attachment functions.

Metadata may use erased internal collections at the driver boundary, but
application-facing and generated adapter boundaries remain typed.

## Performance Requirements

This refactor is justified only if it improves maintainability without
regressing execution. Benchmarks should track:

- generated source lines and classfile size per entity
- clean and incremental Kotlin compilation time
- allocations per decoded row
- simple query throughput
- eager graph throughput
- create/update latency
- application startup and client-construction time

Generic engines should use frozen immutable plans and precomputed adapters so
they do not trade compile-time duplication for runtime interpretation overhead.

## Migration Strategy

Do not rewrite every generated surface at once.

1. Introduce an internal runtime engine for one narrow terminal, such as
   `all()` without eager edges.
2. Generate adapters while keeping the old path available in tests.
3. Prove source compatibility and semantic parity.
4. Move eager loading, then mutation lifecycle orchestration.
5. Delete duplicated emitters only after generated compile tests and integration
   tests cover the shared engine.

Generated implementation details may break during this migration; public
repository, builder, and result contracts should change only through their own
RFCs.

## Relationship To Codegen Plugins

Runtime engines make extension points more constrained. Codegen plugins should
contribute metadata, typed adapters, or declared lifecycle/query stages rather
than arbitrary copies of generated execution algorithms.

## Non-Goals

- Do not replace generated typed APIs with a dynamic repository.
- Do not move schema correctness checks from generation to first query.
- Do not use reflection for row hydration.
- Do not combine synchronous and suspend execution behind one ambiguous engine.
- Do not broaden public APIs merely to fit an internal abstraction.

## Test Requirements

- old and new execution paths produce identical results and failures during
  migration
- generated APIs retain compile-time field, edge, and ID types
- privacy, validation, hooks, interceptors, and write states preserve ordering
- generated source and classfile size decrease materially
- benchmarks show no meaningful hot-path regression
- engine failures retain entity, field, edge, and operation diagnostics
- custom driver behavior remains behind the driver SPI

## Related Features

- [Set-Based Eager Graph Loader](../query/set-based-eager-graph-loader.md)
- [Structured Mutation Pipeline](../mutation/structured-mutation-pipeline.md)
- [Modular Driver SPI](modular-driver-spi.md)
- [Codegen Plugin Hooks](codegen-plugin-hooks.md)
