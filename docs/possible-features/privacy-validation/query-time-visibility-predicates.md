# RFC: Query-Time Visibility Predicates

## Status

Possible future feature. This is not implemented.

## Summary

Add typed, viewer-dependent predicates that define which rows participate in a
query before ordering, pagination, traversal, aggregation, and projection.

Query-time visibility is distinct from LOAD privacy:

- visibility predicates shape the storage query as a set
- LOAD privacy authorizes each entity that is actually materialized

LOAD privacy remains the final authority. EntKt does not attempt to translate
arbitrary Kotlin privacy callbacks into SQL.

## Motivation

The current strict model evaluates LOAD privacy after the storage window has
been selected. This is explicit and fail-closed, but it creates pressure in
ordinary list and API use cases:

- a page fails if any selected row is denied
- a viewer-visible count requires loading full entities in Kotlin
- offset and limit operate on storage rows rather than visible rows
- edge predicates and traversal sources may be influenced by related rows the
  viewer could not load
- projections cannot safely avoid full hydration when arbitrary LOAD rules need
  the complete entity

Many common visibility rules are naturally predicates: tenant ID, owner ID,
soft-delete state, publication state, or membership represented as an edge
existence condition. Those rules should participate in the SQL query directly.

## Design Boundary

Query visibility answers:

```text
Which rows may this query consider for this privacy context?
```

LOAD privacy answers:

```text
May this already-selected entity be materialized and returned?
```

Neither layer silently replaces the other. Predicate visibility improves
pagination correctness and set-based performance; LOAD privacy retains support
for arbitrary Kotlin logic and acts as defense in depth.

## Possible API Shape

The exact naming is open. A schema policy could register a typed predicate
factory:

```kotlin
privacy {
    queryVisibility { ctx ->
        when (val viewer = ctx.viewer) {
            is Viewer.User -> User.tenantId eq viewer.tenantId
            Viewer.Anonymous -> User.published eq true
            is Viewer.PrivacyBypass -> Predicate.alwaysTrue()
        }
    }

    load(UserLoadPrivacyRule { ctx ->
        // Final materialization authority remains available.
        PrivacyDecision.Allow
    })
}
```

The visibility callback returns only a typed predicate for its entity. It does
not receive materialized rows and cannot perform per-row mutation.

`queryVisibility`, `visibleWhere`, and `loadWhere` are candidate names. Avoid a
generic name such as `scope`, which already has several framework meanings.

## Query Semantics

For an ordinary query, the effective predicate is conceptually:

```text
caller predicates
AND read-interceptor predicates
AND query-visibility predicate
AND structural traversal/eager predicates
```

The visibility predicate is structural. Application query code cannot remove
or replace it.

It applies before:

- `orderBy`
- `limit` and `offset`
- cursor boundaries
- count, existence, and other visibility-aware aggregates
- field projection
- root materialization and LOAD privacy

This makes a limit mean the number of rows admitted by query visibility, though
final LOAD privacy can still reject the operation.

## Relationship Queries

Visibility must compose across relationships rather than applying only to the
terminal entity.

- Eager-loaded targets receive their own query-visibility predicate.
- A traversal source receives source visibility before its ordering and bounds
  choose source rows.
- The traversal target receives target visibility before target pagination.
- `has { ... }` and other edge predicates apply the related entity's visibility
  predicate inside the existence subquery.
- Many-to-many junction entities apply visibility only when the junction is a
  domain entity with a declared policy; pure framework link storage follows its
  owning edge contract.

Cycles in relationship predicates must be detected and rejected with the full
policy path rather than recursing indefinitely.

## Counts, Existence, And Projections

Once a query has a predicate-shaped visibility boundary, it can support
visibility-aware set operations without materializing every entity:

```kotlin
client.posts.query { where(Post.published eq true) }.count()
client.posts.query { where(Post.slug eq slug) }.exists()
client.posts.query().select(Post.id, Post.title)
```

These operations prove only query visibility. If an entity also has arbitrary
LOAD rules that can deny rows admitted by the predicate, the API must not call
the result fully LOAD-authorized.

Possible explicit postures are:

- require the schema to declare query visibility complete for the operation
- run a materializing checked variant
- retain a clearly named storage/query-visible result that documents the
  remaining LOAD boundary

The first implementation should choose one rule rather than silently changing
semantics based on which policies happen to be registered.

## Storage-Level Escape Hatch

Trusted authorization and validation code may still need to inspect raw storage
facts. That capability should remain explicit and separately named; it must not
be confused with query-visible counts.

Potential shape:

```kotlin
ctx.client.posts.storageQuery {
    where(Post.aclGroupId eq groupId)
}.exists()
```

Whether this replaces the current `raw*` names belongs to
[Explicit Query Authority And Cost](../query/explicit-query-authority-and-cost.md).

## Privacy Context And Bypass

One captured `PrivacyContext` is shared across visibility predicate creation,
read interceptors, storage execution, LOAD privacy, traversal, and eager work.

`Viewer.PrivacyBypass` should bypass both query visibility and LOAD privacy.
That behavior must be framework-owned and visible in diagnostics. Validation
and system read postures need an equally explicit contract rather than
accidentally inheriting viewer scoping.

If visibility predicate construction throws, the read fails before driver I/O
with the original exception stored in `ReadResult.Failed`.

## Performance And Indexing

Visibility predicates run on every affected query, so schema explanation should
warn when common equality predicates lack supporting indexes. Diagnostics
should attribute each generated predicate to its visibility policy without
logging sensitive viewer values.

The predicate factory runs once per logical query step, not once per row.

## Non-Goals

- Do not compile arbitrary `PrivacyRule` callbacks into SQL.
- Do not remove LOAD privacy.
- Do not infer that a predicate is complete authorization unless the schema
  declares that contract explicitly.
- Do not expose denied row counts, scanned-row counts, or cursor gaps.
- Do not create a second untyped query language for policies.

## Test Requirements

- visibility predicates apply before limit, offset, and cursor boundaries
- visibility-aware count and existence use the same effective predicates as row
  queries
- callers and interceptors cannot remove visibility predicates
- root, eager target, traversal source, traversal target, and `has` subqueries
  apply the correct entity policy
- cyclic relationship visibility fails with an actionable path
- LOAD privacy still runs on materialized results after query visibility
- one privacy context is shared across every phase of the logical read
- bypass and validation/system postures follow their explicit contracts
- predicate construction failure performs no driver call
- explain output attributes visibility predicates without exposing viewer data

## Related Features

- [Privacy-Aware Visible Pagination](../query/privacy-aware-visible-pagination.md)
- [Projection / Select API](../query/projection-select-api.md)
- [Cursor Pagination](../query/cursor-pagination.md)
- [Explicit Query Authority And Cost](../query/explicit-query-authority-and-cost.md)
