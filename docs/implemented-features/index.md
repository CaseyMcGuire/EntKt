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

## Query APIs

- [Read-Path Interceptors](query/read-path-interceptors.md)
- [Phantom-Typed Query Scopes](query/phantom-typed-query-scopes.md)

## Model Behavior

- [Schema Validation Explain](schema/schema-validation-explain.md)
- [Soft Delete](schema/soft-delete.md)

## Results, Codegen, And Tooling

- [EntKt Result Variants](tooling/entkt-result-variants-rfc.md)
- [Flyway Shadow Migration Workflow](tooling/flyway-shadow-migration-workflow.md)
- [Remove InMemoryDriver](tooling/remove-in-memory-driver.md)
