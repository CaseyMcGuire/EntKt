# RFC: Transactional Graph Changesets

## Status

Possible future feature. This is not implemented.

## Summary

Add an ergonomic way to express a small graph of related creates and updates
inside one transaction without adding fully automatic nested persistence.

## Motivation

Many application mutations naturally touch more than one entity:

```kotlin
client.withTransaction { tx ->
    val org = tx.orgs.create {
        name = "Acme"
    }.saveAndLoad(viewerContext).orRollback()

    val user = tx.users.create {
        email = "admin@example.com"
        orgId = org.id
    }.saveAndLoad(viewerContext).orRollback()

    tx.memberships.create {
        orgId = org.id
        userId = user.id
        role = "owner"
    }.saveAndLoad(viewerContext).orRollback()
}
```

That is explicit and correct, but verbose. A changeset API could keep the
same storage model while improving readability for common graph mutations.

## Non-Goals

- Do not infer arbitrary object graphs from nested Kotlin objects.
- Do not bypass generated mutation builders.
- Do not hide transaction boundaries.
- Do not promise database atomicity outside a transaction.
- Do not support cyclic create graphs in the first version.

## Proposed API

A proposed `graphTransaction` would collect typed create/update nodes with an
explicit `ViewerContext`, resolve their dependencies, and execute them in one
transaction. It should return the canonical transaction result.

The exact DSL for assigning a future generated ID remains open. Ordinary
builders currently accept scalar FK values such as `orgId = org.id`; they do
not expose `org.connect(...)` or accept pending mutation objects as IDs. A
changeset needs an explicit typed deferred-reference adapter rather than
pretending an unsaved handle is already an entity.

Until execution, each node is a changeset handle. The graph resolves its ID
before configuring dependent writes. Users should not need to manually
sequence every insert, but the dependency and transaction boundaries stay
explicit.

## Execution Model

The changeset should compile to an explicit dependency graph:

- scalar-only creates can run first
- creates that depend on generated IDs run after their dependencies
- updates and edge changes run when their referenced rows are known
- validation and privacy run through the normal generated mutation pipeline

In V1, reject cycles such as `A.parent -> B` and `B.parent -> A`, with a
readable dependency path.

## Relationship To Edge Mutations

Current FK assignments use scalar fields:

```kotlin
authorId = author.id
```

Generated changeset adapters should eventually resolve a typed deferred
reference into that same assignment. Preserve existing field-backed FK names,
nullability, and relationship mutation validation; do not create a second
relationship model or infer persistence from arbitrary Kotlin object graphs.

## Validation And Privacy

Each generated mutation still owns its own checks:

- create privacy for created entities
- update privacy for updated entities
- edge mutation validation for relationship changes
- returned LOAD privacy for returned entities

The graph layer only coordinates ordering and transaction scope.

## Open Questions

- Should graph saves preflight every candidate before the first write, or use
  normal generated mutation ordering inside the transaction?
- Should changeset handles expose generated IDs before save for client-assigned
  ID schemas?
- Should graph saves return every created entity, or only explicitly requested
  values?

## Test Requirements

Before implementation, add tests for:

- dependent creates are ordered correctly
- typed deferred references resolve IDs from earlier changeset handles
- all writes run inside one transaction
- failures roll back earlier graph writes
- validation and privacy errors preserve entity context
- cyclic create dependencies are rejected with a clear error
