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

## Proposed API

Simple typed projections:

```kotlin
val rows = client.posts.query {
    where(Post.published eq true)
}.select(Post.id, Post.title)
```

Generated result type options:

1. Kotlin tuples are not ideal because the standard library has only `Pair`
   and `Triple`.
2. Generate named projection classes.
3. Return a generated `SelectedRow` with typed accessors.

Potential first version:

```kotlin
val rows: ReadResult<List<SelectedRow2<Long, String>>> =
    client.posts.query().select(Post.id, Post.title)
```

Named generated projections are preferable when a projection is reused across
application boundaries:

```kotlin
val rows: ReadResult<List<PostSummary>> = client.posts.query {
    where(Post.published eq true)
}.select(PostSummary::class)
```

The exact declaration mechanism is open. The public result must provide named
typed properties rather than an untyped map.

## Privacy Behavior

Projection reads still reveal entity existence and selected field values. They
must define privacy behavior explicitly.

Recommended behavior:

- query-time visibility predicates apply before projection, ordering, and bounds
- if no LOAD rules exist, query only the storage columns required by the
  projection and effective ordering
- if LOAD rules exist, fetch every field required to evaluate those rules,
  enforce LOAD privacy, then map the successful entity to the projection
- never change the terminal's authorization claim merely because a policy list
  happens to be empty at runtime

This is slower but preserves the existing rule model.

A future policy-dependency declaration may let EntKt fetch fewer than all
entity columns while still evaluating LOAD rules. V1 should prefer full,
obviously correct policy input over an inferred field set.

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
- generated SQL only selects requested columns when no privacy rules exist
- policy-required fields are available without appearing in the public result
- failures use the canonical `ReadResult` contract
- cursor projection selects hidden ordering fields without exposing them
- columns from another entity are rejected at compile time
