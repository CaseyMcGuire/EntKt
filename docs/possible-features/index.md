# Possible Features

This directory tracks feature ideas that may be worth implementing later.
These pages are design notes, not committed API contracts.

## Privacy And Validation

- [Privacy / Validation Explain Mode](privacy-validation/privacy-validation-explain-mode.md)
- [Policy Test Helpers](privacy-validation/policy-test-helpers.md)
- [Query Observability Privacy](privacy-validation/query-observability-privacy.md)
- [Edge-Derived LOAD Privacy](privacy-validation/edge-derived-load-privacy.md)
- [Preflighted Bulk Operations](privacy-validation/preflighted-bulk-operations.md)

## Mutation APIs

- [Structured Mutation Pipeline](mutation/structured-mutation-pipeline.md)
- [Ephemeral Mutation Inputs](mutation/ephemeral-mutation-inputs.md)
- [Compare-And-Set Mutations](mutation/compare-and-set-mutations.md)
- [Edge Mutation API Overview](edge-mutation/00-overview.md)
- [ID-Based Update Roots](edge-mutation/01-id-based-update-roots.md)
- [To-One FK Mutation And Nullability](edge-mutation/02-to-one-assignment-nullability.md)
- [Many-To-Many Schema Modeling](edge-mutation/03-m2m-schema-modeling.md)
- [Transaction And Locking Semantics For Edge Mutations](edge-mutation/04-transaction-locking-semantics.md)
- [Link-Table M2M Mutation Helpers](edge-mutation/05-link-table-helpers.md)
- [Mutation Actions](mutation/mutation-actions.md)
- [Transactional Graph Changesets](mutation/transactional-graph-changesets.md)

## Query APIs

- [Request-Scoped Entity Loading](query/request-scoped-entity-loading.md)
- [Query Observability Diagnostics](query/query-observability-diagnostics.md)
- [Read-Path Interceptors](query/read-path-interceptors.md) — **implemented** (user-facing docs in [Queries → Read-Path Interceptors](../04-queries.md#read-path-interceptors))
- [Cursor Pagination](query/cursor-pagination.md)
- [Projection / Select API](query/projection-select-api.md)
- [Phantom-Typed Query Scopes](query/phantom-typed-query-scopes.md)
- [Aggregations](query/aggregations.md)
- [Indexed Query Helpers](query/indexed-query-helpers.md)

## Model Behavior

- [Schema Nullability Terminology](schema/schema-nullability-terminology.md)
- [Typed Schema Handles](schema/typed-schema-handles.md)
- [Schema Validation Explain](schema/schema-validation-explain.md)
- [Optimistic Locking](schema/optimistic-locking.md)
- [Edge Groups](schema/edge-groups.md)
- [Soft Delete](schema/soft-delete.md)
- [Audit Fields](schema/audit-fields.md)
- [Custom Field Types And Converters](schema/custom-field-types-converters.md)
- [Schema Printer](schema/schema-printer.md)

## Results, Codegen, And Tooling

- [Structured Error Model](tooling/structured-error-model.md)
- [EntKt Result Variants](tooling/entkt-result-variants-rfc.md)
- [Read Result Variants](tooling/read-result-variants.md)
- [Flyway Shadow Migration Workflow](tooling/flyway-shadow-migration-workflow.md)
- [Codegen Plugin Hooks](tooling/codegen-plugin-hooks.md)
- [OpenAPI / JSON Schema Generation](tooling/openapi-json-schema-generation.md)
- [GraphQL Kotlin Type Generation](tooling/graphql-kotlin-generation.md)
- [Generated Test Fixtures](tooling/generated-test-fixtures.md)
- [Remove InMemoryDriver](tooling/remove-in-memory-driver.md) — **implemented**
- [Gradle Developer Experience](tooling/gradle-dx.md)
