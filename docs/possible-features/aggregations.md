# RFC: Aggregations

## Status

Possible future feature. This is not implemented.

## Summary

Add typed aggregation helpers for count, min, max, sum, average, and group-by
queries.

## Motivation

Applications often need reporting queries:

- count posts by author
- find latest post date
- average age by account status
- sum order totals by day

Today users must drop to driver-specific SQL or build custom repository
helpers.

## Non-Goals

- Do not build a full SQL expression DSL in the first version.
- Do not support arbitrary joins in the first version.
- Do not hide privacy implications of aggregate queries.
- Do not replace raw driver escape hatches.

## Proposed API

Example:

```kotlin
val stats = client.posts.query {
    where(Post.published eq true)
}.aggregate {
    count()
    max(Post.createdAt)
    groupBy(Post.authorId)
}
```

Possible result:

```kotlin
data class PostAggregateRow(
    val authorId: Long,
    val count: Long,
    val maxCreatedAt: Instant?,
)
```

## Privacy Behavior

Aggregations are observability-sensitive. V1 should make the contract explicit
in method names or docs.

Options:

- raw aggregations use driver aggregate paths and do not evaluate LOAD
  privacy
- visible aggregations materialize rows, enforce LOAD privacy, and aggregate
  in memory
- checked aggregations throw if any matched row is denied

The first implementation should probably start with raw aggregations only and
name them clearly.

## Test Requirements

Before implementation, add tests for:

- count, min, max, sum, and avg on supported field types
- group-by one field
- group-by multiple fields if supported
- null handling matches database semantics
- privacy behavior is covered for every aggregation mode

