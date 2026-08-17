# RFC: Modular Driver SPI

## Status

Possible future architectural direction. This is not implemented.

## Summary

Split the broad `Driver` interface into cohesive contracts for sessions,
queries, mutations, transactions, schema metadata, and optional database
features. Pair those contracts with one structured capability descriptor.

The goal is to make driver support explicit and testable without forcing every
driver to inherit a large set of unrelated default methods and booleans.

## Motivation

The current driver surface includes registration, JSON copying, native storage,
CRUD, aggregates, bulk writes, query explanation, transaction ownership, row
locking, relationship locking, and mutation-exception classification.

That creates several pressures:

- a small custom driver must understand unrelated optional contracts
- feature support is distributed across boolean flags and methods
- a root driver can advertise family support for an operation that only its
  transaction-scoped child may execute
- native, emulated, and unsupported behavior are not represented uniformly
- generated code must know which preflight belongs to which method
- one interface change creates churn across every driver implementation

## Design Principles

- Capabilities describe a driver family's support; session types describe what
  can be called in the current context.
- Unsupported behavior fails before hooks, policies, or SQL execution.
- Emulated support is distinct from native support when cost or semantics differ.
- Core operations remain small enough for third-party drivers to implement.
- Dialect-specific behavior stays behind explicit extension contracts.
- Generated code depends on framework commands and metadata, not SQL strings.

## Possible Contract Shape

One possible decomposition is:

```kotlin
interface Driver {
    val identity: DriverIdentity
    val capabilities: DriverCapabilities
    fun openSession(): DriverSession
}

interface DriverSession :
    QueryExecutor,
    MutationExecutor,
    SchemaMetadataAccess,
    AutoCloseable

interface TransactionManager {
    fun <T> transaction(
        options: TransactionOptions,
        block: (TransactionSession) -> T,
    ): DriverTransactionResult<T>
}

interface TransactionSession : DriverSession, LockExecutor
```

The names and ownership model are open. The important distinction is that
locking methods exist only on a transaction-capable session instead of being
callable on a root instance that must reject them dynamically.

Schema registration or validation may remain driver-level if it describes the
whole schema graph rather than one connection session.

## Query And Mutation Commands

Prefer structured internal commands over long parameter lists of table names,
column strings, and erased maps:

```kotlin
data class SelectCommand(
    val entity: EntitySchema,
    val projection: Projection,
    val predicates: List<Predicate<*>>,
    val orderBy: List<OrderField<*>>,
    val bounds: QueryBounds?,
)
```

Drivers may still bind and return erased storage values internally. A small
`RowView` abstraction can centralize column lookup and diagnostics while
generated adapters preserve typed entity decoding.

Mutation commands should carry enough phase information for exception
classification without relying on string inference after a failure.

## Optional Feature Contracts

Optional capabilities can be expressed through interfaces or extension lookup:

```kotlin
interface AggregateExecutor
interface QueryExplainExecutor
interface PerParentWindowExecutor
interface JsonStorageAdapter
interface NativeTypeAdapter
interface RelationshipLockExecutor
```

Capability metadata remains the canonical discoverability mechanism. Runtime
type checks alone are insufficient because support can be native, emulated, or
conditional.

## Capability Model

This RFC relies on
[Driver Capability Matrix](driver-capability-matrix.md) for the descriptor.
A useful support model is:

```kotlin
enum class CapabilitySupport {
    Native,
    Emulated,
    Unsupported,
}
```

Conditional requirements carry their conditions explicitly, for example:

```text
row locking: Native, transaction session required
per-parent pagination: Emulated, bounded parent set required
concurrent index creation: Native, transaction prohibited
```

## Transaction Ownership

Root, ordinary-session, and transaction-session lifetimes should be explicit in
types. A transaction session cannot escape its block, and a normal session
cannot claim that `inTransaction` happens to be true.

Nested transactions and savepoints use the transaction-options contract rather
than recursively calling a generic driver method.

Cancellation and commit certainty remain structural results, not generic SQL
exceptions.

## Registration And Schema Validation

Driver compatibility should be checked once against the complete resolved
schema graph when a client is created:

- native column codecs
- typed JSON support
- required locking or eager-window capabilities
- identifier length and dialect constraints
- migration/introspection features when tooling requests them

Session creation should not repeat schema I/O. Registration remains idempotent
and free of I/O when no change is required.

## Migration Strategy

Because EntKt is greenfield, the final SPI need not preserve every existing
method. Still, migrate in narrow steps:

1. Introduce `DriverCapabilities` and make legacy booleans delegate to it.
2. Introduce structured select and mutation commands behind adapters.
3. Separate transaction-only locking operations.
4. Move optional aggregate, explanation, and eager-window features into focused
   contracts.
5. Update the PostgreSQL driver and a minimal conformance fixture.
6. Remove legacy shims after generated code no longer calls them.

## Conformance Tests

Provide a reusable driver test kit covering:

- query predicate and ordering semantics
- null and type decoding
- insert/update/delete outcome contracts
- transaction commit, rollback, cancellation, and uncertainty
- declared capability behavior
- unsupported-operation preflight timing
- schema registration idempotency
- exception classification and sensitive-value redaction

Optional capability suites run only when the driver declares support.

## Non-Goals

- Do not pretend all SQL databases have identical behavior.
- Do not expose driver SPI types as the primary application query API.
- Do not require optional features to implement the core CRUD contract.
- Do not hide an emulated performance cost behind `Supported`.
- Do not combine synchronous JDBC and suspend R2DBC calls in one interface.

## Test Requirements

- PostgreSQL reports one complete capability descriptor
- session types prevent root-level locking calls at compile time
- generated execution fails unsupported requirements before lifecycle work
- native and emulated features are distinguishable in explain diagnostics
- structured commands validate identifiers against registered metadata
- transaction sessions cannot be used after their block
- the driver conformance kit detects capability/implementation disagreement
- legacy shims, while present, exactly reflect the structured descriptor

## Related Features

- [Driver Capability Matrix](driver-capability-matrix.md)
- [Coroutine And R2DBC Driver Track](coroutine-r2dbc-driver.md)
- [Set-Based Eager Graph Loader](../query/set-based-eager-graph-loader.md)
- [Thin Codegen And Runtime Execution Engines](thin-codegen-runtime-engines.md)
