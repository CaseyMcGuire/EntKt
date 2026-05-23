# RFC: Edge Mutation API Overview

## Status

Planning index. The original edge-mutation baseline RFCs have been
implemented and moved to
[Implemented Features](../../implemented-features/index.md). Remaining work is
tracked as smaller follow-up RFCs in this folder.

## Summary

The original edge mutation RFC was split into five baseline RFCs:

1. [ID-Based Update Roots](../../implemented-features/edge-mutation/01-id-based-update-roots.md)
2. [To-One FK Mutation And Nullability](../../implemented-features/edge-mutation/02-to-one-assignment-nullability.md)
3. [Many-To-Many Schema Modeling](../../implemented-features/edge-mutation/03-m2m-schema-modeling.md)
4. [Transaction And Locking Semantics For Edge Mutations](../../implemented-features/edge-mutation/04-transaction-locking-semantics.md)
5. [Link-Table M2M Mutation Helpers](../../implemented-features/edge-mutation/05-link-table-helpers.md)
6. [Field-Backed FK Declaration Names](../../implemented-features/edge-mutation/06-field-backed-fk-declaration-names.md)
7. [Generated Member Name Collisions](../../implemented-features/edge-mutation/07-generated-member-name-collisions.md)

Those baseline contracts are implemented. The remaining work is intentionally
smaller and can be reviewed independently:

8. [Create Hook Mutation View Adapter](08-create-hook-mutation-view-adapter.md)
9. [Through-Entity Nullable M2M Traversal](09-through-entity-nullable-m2m-traversal.md)
10. [Through-Link Inverse Read Traversal](10-through-link-inverse-read-traversal.md)

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

Splitting the RFCs made it possible to land the ID-based update foundation,
to-one behavior, M2M modeling, transaction semantics, and link-table helpers
independently. The follow-up RFCs preserve that smaller review surface for the
remaining naming, collision, hook-view, and M2M traversal refinements.

## Current Follow-Up Order

The recommended implementation order is:

1. ~~[Generated Member Name Collisions](../../implemented-features/edge-mutation/07-generated-member-name-collisions.md)~~ (**implemented**, V1)
2. ~~[Field-Backed FK Declaration Names](../../implemented-features/edge-mutation/06-field-backed-fk-declaration-names.md)~~ (**implemented**, V1)
3. [Create Hook Mutation View Adapter](08-create-hook-mutation-view-adapter.md)
4. [Through-Entity Nullable M2M Traversal](09-through-entity-nullable-m2m-traversal.md)
5. [Through-Link Inverse Read Traversal](10-through-link-inverse-read-traversal.md)

The collision work landed first (RFC 07 V1) so that declaration-name
capture (RFC 06 V1) emits actionable diagnostics rather than Kotlin compile
errors when new generated names overlap existing schema members. Both
shipped together against `master`.
