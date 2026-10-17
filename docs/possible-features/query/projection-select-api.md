# RFC: Projection / Select API

## Status

Possible future feature. This is not implemented.

## Summary

Add query APIs for selecting specific columns instead of hydrating complete
entities.

## Motivation

Some screens and reports need only a few fields. Hydrating full entities can
be wasteful when:

- rows have large text fields
- callers need only IDs
- aggregate-like views need simple tuples
- GraphQL resolvers need to avoid overfetching

## Non-Goals

- Do not weaken privacy by default.
- Do not expose untyped maps as the primary API.
- Do not support arbitrary SQL expressions in the first version.
- Do not replace full entity queries.
- Do not traverse edges in the first version. A projection binds to fields of
  the root entity only; the FK column a `belongsTo` edge creates is a field.

## Proposed API

Simple typed projections:

```kotlin
val rows = client.posts.query {
    where(Post.published eq true)
}.select(viewerContext, Post.id, Post.title)
```

Generated result type options:

1. Kotlin tuples are not ideal because the standard library has only `Pair`
   and `Triple`.
2. Generate named projection classes.
3. Return a generated `SelectedRow` with typed accessors.

Potential first version:

```kotlin
val rows: ReadResult<List<SelectedRow2<Long, String>>> =
    client.posts.query().select(viewerContext, Post.id, Post.title)
```

Named projections are preferable when a projection is reused across
application boundaries. A named projection is an app-owned data structure
whose properties bind by name and type to fields of the root entity:

```kotlin
val rows: ReadResult<List<PostSummary>> = client.posts.query {
    where(Post.published eq true)
}.select(viewerContext, PostSummary::class)
```

The public result must provide named typed properties rather than an untyped
map.

## Privacy Behavior

Projection reads still reveal entity existence and selected field values. They
must define privacy behavior explicitly.

The privacy model for projections is field-level read privacy, specified in
[Field-Level Read Privacy](../privacy-validation/field-level-read-privacy.md).
In brief:

- query-time visibility predicates apply before projection, ordering, and bounds
- an empty LOAD rule list remains fail-closed; absence of rules is not permission
- entity LOAD rules run for every returned row, exactly as for full-entity
  reads; a projection never weakens the entity-level claim
- a projection asserts the viewability of exactly the fields it returns.
  Policies may attach LOAD rules to individual fields; field rules evaluate
  for the returned fields only, and a denied field fails that row's entry in
  the result — fields are never silently dropped or masked
- a field with no field rules inherits the entity LOAD decision; a field with
  rules needs an explicit `Allow` from its own rules
- v1 hydrates full rows before mapping. Fewer storage columns may be fetched
  only under the column-pruning contract in the field-privacy RFC: the
  projected fields' rules are all context-only and the policy declares its
  rule dependencies
- never change the terminal's authorization claim merely because a policy list
  happens to be empty at runtime

Projection terminals return `ReadResult`; they do not introduce throwing and
structured-result twins.

## Relationship To Pagination

Projection composes with cursor pages only when the selected SQL shape retains
every effective ordering and cursor field internally. Those fields need not be
exposed on the public projection, but they must be selected and decoded for
cursor construction.

See [Cursor Pagination](cursor-pagination.md) and
[Query-Time Visibility Predicates](../privacy-validation/query-time-visibility-predicates.md).

## Test Requirements

Before implementation, add tests for:

- selecting one, two, and three fields
- selected field types are preserved
- nullable fields stay nullable
- LOAD privacy is enforced before projection
- empty privacy rule lists deny rather than authorize projection
- explicitly authorized projection can omit unused storage columns
- policy-required fields are available without appearing in the public result
- failures use the canonical `ReadResult` contract
- cursor projection selects hidden ordering fields without exposing them
- columns from another entity are rejected at compile time
- field rules are enforced for the fields a projection returns
- a field with no field rules inherits the entity LOAD decision
- a field with rules and no matching `Allow` fails the row's result entry,
  with the denial naming the field and never carrying its value
- a context-only field denial fails the read before any rows are fetched
- a projection structure property that binds to no field fails as an
  operational error, not a privacy denial
- a `belongsTo` FK column is selectable in a projection
- a projection structure property naming an edge is rejected
