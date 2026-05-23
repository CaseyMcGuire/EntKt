# RFC: Edge Mutation API Overview

## Status

Possible future feature. This is not implemented.

## Summary

The original edge mutation RFC has been split into five smaller RFCs. They are
related, but they should be reviewed and implemented independently:

1. [ID-Based Update Roots](../../implemented-features/edge-mutation/01-id-based-update-roots.md)
2. [To-One FK Mutation And Nullability](02-to-one-assignment-nullability.md)
3. [Many-To-Many Schema Modeling](03-m2m-schema-modeling.md)
4. [Transaction And Locking Semantics For Edge Mutations](../../implemented-features/edge-mutation/04-transaction-locking-semantics.md)
5. [Link-Table M2M Mutation Helpers](../../implemented-features/edge-mutation/05-link-table-helpers.md)

## Motivation

The monolithic RFC was covering several design changes with different risk
levels and implementation timelines:

- to-one FK mutation semantics
- relationship nullability defaults
- ID-based update root semantics
- many-to-many schema modeling
- link-table helper APIs
- transaction, locking, hook, privacy, validation, candidate, and return-state
  semantics

Splitting the RFCs makes it possible to land the ID-based update foundation
first, then the to-one behavior, while continuing to refine the more complex M2M
and transaction design.

## Dependency Order

The intended review and implementation order is:

1. [ID-Based Update Roots](../../implemented-features/edge-mutation/01-id-based-update-roots.md)
2. [To-One FK Mutation And Nullability](02-to-one-assignment-nullability.md)
3. [Many-To-Many Schema Modeling](03-m2m-schema-modeling.md)
4. [Transaction And Locking Semantics For Edge Mutations](../../implemented-features/edge-mutation/04-transaction-locking-semantics.md)
5. [Link-Table M2M Mutation Helpers](../../implemented-features/edge-mutation/05-link-table-helpers.md)

The transaction and helper RFCs are tightly related, but they are separated so
the runtime guarantees can be reviewed without also reviewing the full builder
API.
