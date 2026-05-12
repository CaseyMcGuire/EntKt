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
val rows: List<SelectedRow2<Long, String>> =
    client.posts.query().select(Post.id, Post.title)
```

## Privacy Behavior

Projection reads still reveal entity existence and selected field values. They
must define privacy behavior explicitly.

Recommended V1 behavior:

- if no LOAD rules exist, query selected columns directly
- if LOAD rules exist, hydrate full entities, enforce LOAD privacy, then map
  selected fields

This is slower but preserves the existing rule model.

## Test Requirements

Before implementation, add tests for:

- selecting one, two, and three fields
- selected field types are preserved
- nullable fields stay nullable
- LOAD privacy is enforced before projection
- generated SQL only selects requested columns when no privacy rules exist

