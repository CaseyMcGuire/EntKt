# RFC: Driver Capability Matrix

## Status

Possible future feature. This is not implemented.

## Summary

Add a structured capability model for drivers and generated features.

The goal is to make backend differences explicit instead of hiding them behind
boolean flags, late SQL errors, or documentation-only caveats.

## Motivation

entkt already has driver-specific behavior:

- JDBC Postgres supports SQL explain output
- owner-row locking requires a supporting driver and active transaction
- migration generation is currently Postgres/Flyway-oriented
- future SQL expressions may require dialect-specific support
- a coroutine/R2DBC driver may not have identical behavior to JDBC

As features grow, users need a clear answer to:

```text
Can this driver run the generated code my schema uses?
```

The answer should be available at generation time where possible and at runtime
before executing unsupported behavior.

## Non-Goals

- Do not pretend all drivers are equivalent.
- Do not require every driver to implement every feature.
- Do not make capabilities a substitute for tests.
- Do not expose driver internals as the public API.
- Do not block custom drivers from existing with partial support.

## Proposed Capability Model

Introduce a structured capability descriptor:

```kotlin
data class DriverCapabilities(
    val dialect: DriverDialect,
    val transactions: TransactionCapabilities,
    val locking: LockingCapabilities,
    val query: QueryCapabilities,
    val schema: SchemaCapabilities,
    val migration: MigrationCapabilities,
)
```

Example nested capabilities:

```kotlin
data class TransactionCapabilities(
    val supported: Boolean,
    val isolationLevels: Set<IsolationLevel>,
    val readOnly: CapabilitySupport,
    val savepoints: CapabilitySupport,
)

data class QueryCapabilities(
    val explainSql: CapabilitySupport,
    val jsonContains: CapabilitySupport,
    val arrayContains: CapabilitySupport,
    val caseInsensitiveLike: CapabilitySupport,
)
```

Use a richer support enum where a boolean would be ambiguous:

```kotlin
enum class CapabilitySupport {
    Native,
    Emulated,
    Unsupported,
}
```

`Emulated` is important for features that work but have different performance
or semantics than native support. `Native` says the driver can provide the
documented operation directly; it does not imply that the operation is cheap.

## Generated Feature Requirements

Generated code and optional features can declare requirements:

```kotlin
data class FeatureRequirement(
    val capability: CapabilityKey,
    val acceptedSupport: Set<CapabilitySupport> = setOf(CapabilitySupport.Native),
    val reason: String,
)
```

Examples:

- pessimistic update consistency requires row-level locking
- SQL JSON containment requires JSON predicate support
- savepoint nesting requires transaction savepoints
- migration generation requires schema introspection and SQL rendering

When a requirement is not met, errors should name both sides:

```text
Feature requires driver capability query.jsonContains.
Driver H2Driver reports Unsupported.
Used by Post.metadata containsJson(...).
```

## Runtime API

Expose capabilities from drivers:

```kotlin
interface Driver {
    val capabilities: DriverCapabilities
}
```

Existing boolean flags can remain as compatibility shims while the capability
model is introduced:

```kotlin
val supportsReadRowForUpdate: Boolean
    get() = capabilities.locking.readRowForUpdate == CapabilitySupport.Native
```

The structured model should become the canonical source.

## Documentation Output

Generate or maintain a documentation table:

| Capability | Postgres JDBC | Postgres R2DBC | Notes |
|---|---:|---:|---|
| transactions | native | planned | |
| savepoints | native | unknown | |
| row lock for update | native | planned | requires active transaction |
| SQL explain | native | unknown | bind values redacted by default |
| JSON containment | planned | planned | Postgres only |
| Flyway migration generation | native | n/a | tooling feature |

This table should be derived from code or tested constants when possible, so
docs do not drift from implementation.

## Tooling

Add a diagnostic task or CLI command:

```bash
./gradlew entktDriverCapabilities
```

Possible output:

```text
PostgresDriver
  transactions: supported
  savepoints: supported
  read row for update: supported
  SQL explain: supported
  JSON containment: planned
```

For generated projects, validation can compare selected features against the
configured driver.

## Test Requirements

Before implementation, add tests for:

- each built-in driver reports a complete capability descriptor
- legacy boolean flags match the structured capability model
- unsupported feature requirements fail before SQL execution
- generated diagnostics include driver name, capability key, and feature source
- documentation output is generated from the same source as runtime
  capabilities
- custom drivers can report partial capabilities without implementing optional
  methods

See [Modular Driver SPI](modular-driver-spi.md) for separating session and
optional-operation contracts after capabilities become canonical.
