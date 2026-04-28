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
    }.save()

    val user = tx.users.create {
        email = "admin@example.com"
        this.org.connect(org)
    }.save()

    tx.memberships.create {
        this.org.connect(org)
        this.user.connect(user)
        role = "owner"
    }.save()
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

One possible shape:

```kotlin
client.graphTransaction { graph ->
    val org = graph.orgs.create {
        name = "Acme"
    }

    val user = graph.users.create {
        email = "admin@example.com"
        this.org.connect(org)
    }

    graph.memberships.create {
        this.org.connect(org)
        this.user.connect(user)
        role = "owner"
    }

    graph.save()
}
```

The important distinction is that `org` and `user` are changeset handles until
`save()` runs. They can be used by later changes in the same graph without
requiring the caller to manually sequence every insert.

## Execution Model

The changeset should compile to an explicit dependency graph:

- scalar-only creates can run first
- creates that depend on generated IDs run after their dependencies
- updates and edge changes run when their referenced rows are known
- validation and privacy run through the normal generated mutation pipeline

In V1, reject cycles instead of trying to solve them:

```kotlin
val a = graph.nodes.create { parent.connect(b) }
val b = graph.nodes.create { parent.connect(a) }
```

## Relationship To Edge Mutations

This feature should build on the edge mutation API. It should not introduce a
second way to express relationships.

Good:

```kotlin
this.author.connect(author)
```

Avoid:

```kotlin
authorId = author.id
```

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
- edge connects can reference earlier changeset handles
- all writes run inside one transaction
- failures roll back earlier graph writes
- validation and privacy errors preserve entity context
- cyclic create dependencies are rejected with a clear error
