# Implemented Features

This directory tracks RFCs whose main implementation has landed.
These pages are historical design notes and implementation records;
the user-facing guides remain the primary API documentation.

Open or speculative RFCs remain in the
[Possible Features Index](../possible-features/index.md).

## Mutation APIs

- [ID-Based Update Roots](edge-mutation/01-id-based-update-roots.md)
- [To-One FK Mutation And Nullability](edge-mutation/02-to-one-assignment-nullability.md)
- [Many-To-Many Schema Modeling](edge-mutation/03-m2m-schema-modeling.md)
- [Transaction And Locking Semantics For Edge Mutations](edge-mutation/04-transaction-locking-semantics.md)
- [Link-Table M2M Mutation Helpers](edge-mutation/05-link-table-helpers.md)
- [Field-Backed FK Declaration Names](edge-mutation/06-field-backed-fk-declaration-names.md)
- [Generated Member Name Collisions](edge-mutation/07-generated-member-name-collisions.md)
- [Create Hook Mutation View Adapter](edge-mutation/08-create-hook-mutation-view-adapter.md)
- [Through-Entity Nullable M2M Traversal](edge-mutation/09-through-entity-nullable-m2m-traversal.md)
- [Symmetric Link-Table Edges](edge-mutation/10-symmetric-link-table-writes.md)

## Query APIs

- [Read-Path Interceptors](query/read-path-interceptors.md)
- [Phantom-Typed Query Scopes](query/phantom-typed-query-scopes.md)
- [Aggregations](query/aggregations.md)
- [Indexed Query Helpers](query/indexed-query-helpers.md)
- [Edge Traversal Query Blocks](query/edge-traversal-query-blocks.md)
- [Edge Traversal Source Shape](query/edge-traversal-source-shape.md)
- [Loaded Edge State](query/loaded-edge-state.md)

## Privacy And Validation

- [Read-Only Validation Client](privacy-validation/read-only-validation-client.md)
- [Read-Only Privacy Rule Client](privacy-validation/read-only-privacy-client.md)
- [Posture-Specific Read Client Types](privacy-validation/posture-specific-read-clients.md)

## Model Behavior

- [Schema Validation Explain](schema/schema-validation-explain.md)
- [Soft Delete](schema/soft-delete.md)
- [Schema Nullability Terminology](schema/schema-nullability-terminology.md)
- [Native Database Column Types (Postgres pgvector)](schema/native-database-column-types.md)
- [Typed JSON Fields (Postgres jsonb)](schema/typed-json-fields.md)
- [Pluggable JSON Mappers (Jackson Option)](schema/pluggable-json-mappers.md)

## Results, Codegen, And Tooling

- [EntKt Result Variants](tooling/entkt-result-variants-rfc.md)
- [Flyway Shadow Migration Workflow](tooling/flyway-shadow-migration-workflow.md)
- [Ent Viewer](tooling/ent-viewer.md)
- [Remove InMemoryDriver](tooling/remove-in-memory-driver.md)
