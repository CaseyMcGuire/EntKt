# Possible Features

This directory tracks feature ideas that may be worth implementing later.
These pages are design notes, not committed API contracts.

Implemented RFCs have moved to the
[Implemented Features Index](../implemented-features/index.md).

For the cross-cutting repository audit and links to its focused design notes,
see [EntKt ORM Design Improvements](orm-design-improvements.md).

## Privacy And Validation

- [Query-Time Visibility Predicates](privacy-validation/query-time-visibility-predicates.md)
- [Privacy Rule Primitives And Viewer Flavors](privacy-validation/privacy-rule-primitives-and-viewer-flavors.md)
- [Privacy / Validation Explain Mode](privacy-validation/privacy-validation-explain-mode.md)
- [Policy Test Helpers](privacy-validation/policy-test-helpers.md)
- [Edge-Derived LOAD Privacy](privacy-validation/edge-derived-load-privacy.md)

## Mutation APIs

- [Structured Mutation Pipeline](mutation/structured-mutation-pipeline.md)
- [Coherent Write Concurrency Model](mutation/coherent-write-concurrency.md)
- [Ephemeral Mutation Inputs](mutation/ephemeral-mutation-inputs.md)
- [Compare-And-Set Mutations](mutation/compare-and-set-mutations.md)
- [Delete Consistency](mutation/delete-consistency.md)
- [Transaction Options And Savepoints](mutation/transaction-options-savepoints.md)
- [Mutation Actions](mutation/mutation-actions.md)
- [Transactional Graph Changesets](mutation/transactional-graph-changesets.md)

## Query APIs

- [Eager Loading: Native M2M Windows And Chunking](query/set-based-eager-graph-loader.md)
- [Junction Interceptors: Traversal And Edge Predicates](query/junction-read-interceptors.md)
- [Request-Scoped Entity Loading](query/request-scoped-entity-loading.md)
- [Privacy-Aware Visible Pagination](query/privacy-aware-visible-pagination.md)
- [Query `forUpdate()` Row Locking](query/for-update-query-locking.md)
- [Query Observability Diagnostics](query/query-observability-diagnostics.md)
- [Cursor Pagination](query/cursor-pagination.md)
- [Projection / Select API](query/projection-select-api.md)
- [Typed SQL DSL Escape Hatch](query/typed-sql-dsl-escape-hatch.md)

## Model Behavior

- [Optimistic Locking](schema/optimistic-locking.md)
- [Edge Groups](schema/edge-groups.md)
- [Audit Fields](schema/audit-fields.md)
- [Custom Scalar Converters](schema/custom-scalar-converters.md)
- [Enum Value CHECK Constraints](schema/enum-value-check-constraints.md)
- [Validator-Derived CHECK Constraints](schema/validator-check-constraints.md) — superseded;
  future CHECK support should be an explicit schema feature, independent of client validation

## Codegen And Tooling

- [Runtime Engines: Remaining Verification And Documentation](tooling/thin-codegen-runtime-engines.md)
- [Modular Driver SPI](tooling/modular-driver-spi.md)
- [Same-Module Schema Processing](tooling/same-module-schema-processing.md)
- [Migration Risk And Online DDL](tooling/migration-risk-and-online-ddl.md)
- [Codegen Plugin Hooks](tooling/codegen-plugin-hooks.md)
- [OpenAPI / JSON Schema Generation](tooling/openapi-json-schema-generation.md)
- [GraphQL Kotlin Type Generation](tooling/graphql-kotlin-generation.md)
- [Generated Test Fixtures](tooling/generated-test-fixtures.md)
- [Gradle Developer Experience](tooling/gradle-dx.md)
- [Coroutine And R2DBC Driver Track](tooling/coroutine-r2dbc-driver.md)
- [Migration Diagnostics](tooling/migration-diagnostics.md)
- [Driver Capability Matrix](tooling/driver-capability-matrix.md)
