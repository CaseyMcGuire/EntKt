# Design: Immutable Query Values

## Status

Runtime, generator, and call-site migration implemented. `EntityQueryBuilder`
now holds an immutable `EntityQuery`, and generated configuration blocks use
separate query scopes. Both consumer modules have been regenerated into their
build directories, current usage documentation is updated, and the integration
and Spring example suites pass.

This refactor precedes query-level `forUpdate()` implementation. It does not add
locking, change transaction policy, or change read results.

## Contract

A returned public query is an immutable description of what to read. Fluent
configuration returns a new query; it never changes the receiver or another
query derived from it.

```kotlin
val base = client.users.query {
    where(User.active.eq(true))
    orderBy(User.id.asc())
}

val firstPage = base.limit(10)
val secondPage = firstPage.offset(10)

// base: no limit or offset
// firstPage: limit 10, no offset
// secondPage: limit 10, offset 10
```

Construction, derivation, and traversal perform no database I/O. Terminals
execute fresh reads using the query's description and its bound client; they
do not consume the query, cache results, or change its configuration.

Immutable configuration does not make a transaction client safe for concurrent
execution or extend its lifetime beyond its transaction.

## Mutable Construction Scope

Keep the statement-style DSL by giving its block a separate, temporary
configuration receiver, such as `UserQueryScope`:

```kotlin
val query = client.users.query {
    where(User.active.eq(true))
    limit(10)
    loadPosts {
        orderBy(Post.id.asc())
        limit(3)
    }.filterVisible()
}
```

Configuration methods accumulate changes in that scope. Finishing construction
produces a query backed by the existing immutable `EntityQuery` representation.
An escaped scope or edge-load handle must never be able to alter that returned
query, including its nested selections or traversal source.

The scope exposes configuration, not terminals, traversal, or future locking
methods. Generated code supplies schema-specific edge methods and typed query
construction; reusable state management stays in the runtime.

For block-style refinement of an existing query, provide `configure { ... }`
with the same temporary-scope contract. It starts from the existing description
and returns a new query. Kotlin's `apply { ... }` is not a substitute: immutable
fluent methods return values that a statement-style `apply` block discards.

Keep `load<Edge> { ... }.filterVisible()` on configuration scopes. The current
public edge-load handle mutates its parent, so it cannot retain that behavior on
an immutable query. Existing direct `query.loadPosts()` calls should migrate to
`query.configure { loadPosts() }`; ignoring a result must not secretly mutate
the original query.

## Runtime Boundary

- Reuse `EntityQuery`; do not introduce a parallel query-state model.
- Detach supported mutable predicate and ordering operands at the construction
  boundary, along with caller-owned lists. Do not repeatedly copy an entire
  already-owned query graph merely to change a limit or offset.
- Preserve source queries and selected target queries as immutable values.
- Preserve predicate/order declaration order, schema-declaration edge order,
  duplicate-selection rejection, and each edge's visibility policy.
- Preserve non-negative bound validation and capacity checks before copying
  oversized bind operands. New predicate operands are checked and detached when
  a fluent method returns or a configuration block finishes. An oversized input
  therefore throws during construction, rather than becoming a terminal read
  failure. Execution-time interceptor and driver failures still use read results.
- Keep execution-time interceptors, privacy, client guards, and result/failure
  handling in the existing runtime read pipeline.

No persistent-collection dependency is needed for this design. The important
constraint is ownership: mutable construction data cannot remain an alias
through which a published query can change.

## Migration And Verification

1. Completed: pin the immutable representation's ownership guarantees with
   runtime tests.
2. Implemented: migrate the shared public query implementation and generated
   configuration scopes together, including repository/read-only entry points,
   eager loading, traversal, edge predicates, and index helpers. Thin generated
   block overloads preserve the repository's existing generic arity. Runtime and
   generated-code tests cover the new contracts.
3. Completed: regenerate consumer output, migrate call sites, update current
   usage documentation, and verify integration behavior.

Public API tests must cover independent fluent branches, accumulation inside
blocks, escaped scopes/handles, nested edge configuration, traversal source
isolation, repeated terminals, invalid bounds, and no I/O during construction.
Compile tests must prove that configuration scopes do not expose execution or
traversal methods. Existing privacy, pagination, and bind-capacity tests remain
behavioral constraints during migration.

The eventual locking wrapper can retain an immutable query value. There is no
longer a choice between following later changes to a mutable builder and taking
a separate locking snapshot. Acquiring database locks still happens only when
a locking terminal executes.
