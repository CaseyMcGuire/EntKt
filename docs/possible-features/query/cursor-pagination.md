# RFC: Cursor Pagination

## Status

Possible future feature. This is not implemented.

## Summary

Add generated cursor pagination APIs for stable forward and backward paging.

## Motivation

Offset pagination is simple, but it can be unstable when rows are inserted or
deleted between requests. Cursor pagination is better for feeds, timelines,
and API responses.

## Non-Goals

- Do not remove `limit` and `offset`.
- Do not implement Relay compatibility in the first version unless needed.
- Do not generate cursors without a stable ordering contract.
- Do not silently use non-unique order fields without a tie-breaker.

## Proposed API

Example:

```kotlin
val page = client.posts.query {
    orderBy(Post.createdAt.desc())
    first(20)
    after(cursor)
}.page()
```

Return shape:

```kotlin
data class Page<T>(
    val nodes: List<T>,
    val pageInfo: PageInfo,
)

data class PageInfo(
    val startCursor: String?,
    val endCursor: String?,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean,
)
```

## Cursor Encoding

The cursor should encode the ordered field values plus the primary key
tie-breaker:

```text
created_at=2026-01-10T12:00:00Z
id=123
```

The public cursor string can be base64 JSON. The format should be versioned
so it can evolve.

## Privacy Behavior

Cursor pagination must define interaction with strict LOAD privacy. The first
version should follow existing query semantics:

- driver applies cursor and limit
- entkt materializes rows
- LOAD privacy is evaluated on returned rows
- denial throws

This can return fewer pages than expected if callers choose broad predicates
that include denied rows.

## Test Requirements

Before implementation, add tests for:

- forward pagination with stable ordering
- backward pagination if supported
- primary key tie-breaker prevents duplicate or skipped rows
- cursor rejects wrong entity or malformed data
- LOAD privacy denial throws after page materialization

