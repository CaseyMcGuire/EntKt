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

fun page(): ReadResult<Page<Post>>
```

The terminal follows the canonical read-result contract. Query rejection,
driver failure, malformed cursor data, and LOAD privacy denial are
`ReadResult.Failed`; an empty page is `Success(Page(nodes = emptyList(), ...))`.

## Ordering Contract

`page()` requires an explicit deterministic order. If the caller's order is
not unique, EntKt appends the entity primary key in the same effective
direction as a tie-breaker. The cursor encodes every effective order value,
including that key.

The terminal rejects:

- no explicit order
- an order expression that cannot be encoded and compared by the driver
- a cursor created for another entity or effective ordering
- mixed forward/backward options whose meaning is ambiguous

EntKt must not silently fall back to offset pagination.

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

When query-time visibility predicates exist, they apply before the cursor
boundary and page limit. Strict LOAD privacy then evaluates the materialized
page:

- driver applies query visibility, cursor, and limit
- entkt materializes rows
- LOAD privacy is evaluated on returned rows
- denial returns `ReadResult.Failed(EntPrivacyDeniedException)`; callers using
  `getOrThrow()` receive that stored exception

Arbitrary LOAD denial remains a failed read rather than silently shortening the
page. A separate visible-scan API may fill pages under arbitrary post-load
filtering, but its scan budget and cursor semantics must be explicit.

See
[Query-Time Visibility Predicates](../privacy-validation/query-time-visibility-predicates.md)
and [Privacy-Aware Visible Pagination](privacy-aware-visible-pagination.md).

## Test Requirements

Before implementation, add tests for:

- forward pagination with stable ordering
- backward pagination if supported
- primary key tie-breaker prevents duplicate or skipped rows
- cursor rejects wrong entity or malformed data
- cursor rejects a different effective order or unsupported order expression
- no-order page fails before driver execution
- query visibility applies before cursor selection
- LOAD privacy denial throws after page materialization
- terminal failures use `ReadResult.Failed`
