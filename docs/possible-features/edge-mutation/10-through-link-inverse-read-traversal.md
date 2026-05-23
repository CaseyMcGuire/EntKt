# RFC: Through-Link Inverse Read Traversal

## Status

Possible future feature. This is not implemented.

Extracted from the implemented
[Many-To-Many Schema Modeling](../../implemented-features/edge-mutation/03-m2m-schema-modeling.md)
and [Link-Table M2M Mutation Helpers](../../implemented-features/edge-mutation/05-link-table-helpers.md)
RFCs.

## Summary

Add an explicit read-only inverse marker for `throughLink(...)` relationships so
callers can traverse from the opposite endpoint without declaring a second
write-oriented link-table edge.

Working name:

```kotlin
throughLinkInverse(Post::tags)
```

## Motivation

`throughLink(...)` intentionally permits only one write orientation for a pure
link table. This avoids ambiguous helper ownership and locking semantics for
`add`, `remove`, and exact `set`.

The tradeoff is that the opposite endpoint has no generated traversal unless
callers model the junction as `throughEntity(...)`. Some schemas need read-only
reverse traversal over pure link tables without reverse write helpers.

## Proposed API

```kotlin
class Post : EntSchema("posts") {
    val tags = manyToMany<Tag>("tags")
        .throughLink<PostTag>(PostTag::post, PostTag::tag)
}

class Tag : EntSchema("tags") {
    val posts = manyToMany<Post>("posts")
        .throughLinkInverse(Post::tags)
}
```

`Tag.posts` generates normal read surfaces:

- entity edge refs
- `TagQuery.queryPosts()`
- `withPosts { ... }`
- predicate handles

It does not generate:

- update builder `posts` mutator
- `pendingEdges.posts`
- link-table helper write operations

## Validation

Codegen should reject:

- `throughLinkInverse(...)` without a corresponding write-oriented
  `throughLink(...)`
- a `throughLinkInverse(...)` whose declaring schema is not the write edge's
  target schema
- declaring both a reverse `throughLink(...)` and `throughLinkInverse(...)` for
  the same canonical relationship identity
- multiple inverse declarations for the same write edge

The inverse edge inherits junction metadata from the write side. It should not
repeat source/target junction refs.

## Non-Goals

- Do not add bidirectional write helpers.
- Do not synthesize reverse edges automatically.
- Do not change `throughEntity(...)`; pair-swapped declarations remain the
  bidirectional pattern for domain junctions.

## Acceptance Criteria

- Reverse read traversal works for query, eager loading, and predicates.
- No reverse write helpers are generated.
- Conflict rules reject ambiguous write orientation.
- Existing `throughLink(...)` schemas continue to compile unchanged.
