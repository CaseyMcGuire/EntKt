# RFC: Indexed Query Helpers

## Status

Possible future feature. This is not implemented.

## Summary

Generate type-safe query helper APIs from declared schema indexes so
applications have an obvious path for index-friendly reads.

Example schema:

```kotlin
override fun indexes() = listOf(
    index(User.email).unique(),
    index(Post.authorId, Post.createdAt),
    index(Friendship.requesterId, Friendship.recipientId).unique(),
)
```

Potential generated API:

```kotlin
client.users.findByEmail("a@example.com")

client.posts
    .queryByAuthorId(userId)
    .orderBy(Post.createdAt.desc())
    .limit(20)
    .all()

client.friendships.findByRequesterIdAndRecipientId(requesterId, recipientId)
```

## Motivation

General-purpose query builders are flexible, but they make it easy to
write queries that do not line up with declared indexes.

entkt already knows each entity's indexes. Generated helper methods can
make the efficient path discoverable without removing the flexible
`query { where(...) }` API.

This would help users:

- understand which access patterns are expected by the schema
- avoid accidental table scans in normal application code
- discover composite indexes from IDE completion
- write less repetitive equality-predicate query code
- keep privacy enforcement by returning normal repo/query types

## Non-Goals

- Do not remove or restrict the general query builder.
- Do not guarantee that every generated helper is optimal for every
  database planner.
- Do not implement a full query optimizer.
- Do not analyze arbitrary user-written predicates in the first version.
- Do not require index helper usage by default.
- Do not generate helpers for expression indexes unless the schema DSL
  can represent them safely.

## Proposed API

Generate repository methods from declared indexes.

For unique indexes:

```kotlin
fun findByEmail(email: String): User?

fun findByRequesterIdAndRecipientId(
    requesterId: Long,
    recipientId: Long,
): Friendship?
```

For non-unique indexes:

```kotlin
fun queryByAuthorId(authorId: Long): PostQuery

fun queryByAuthorIdAndCreatedAt(
    authorId: Long,
    createdAt: Instant,
): PostQuery
```

The helpers should return existing generated query/repo results so
privacy behavior remains unchanged:

- `findBy...` should use normal read privacy
- `queryBy...(...).all()` should use normal LOAD privacy
- `visibleCount()`, `rawCount()`, and `exists()` keep their normal
  semantics

## Composite Index Prefixes

Composite indexes should generate helpers for valid left prefixes.

For an index on:

```text
(author_id, created_at, id)
```

Generate:

```kotlin
queryByAuthorId(authorId)
queryByAuthorIdAndCreatedAt(authorId, createdAt)
queryByAuthorIdAndCreatedAtAndId(authorId, createdAt, id)
```

Do not generate:

```kotlin
queryByCreatedAt(createdAt)
queryById(id)
```

unless separate indexes support those access patterns.

This mirrors how most relational databases use b-tree composite indexes:
left-prefix predicates are index-friendly, while skipping leading
columns usually is not.

## Unique Indexes

Unique indexes should generate `findBy...` methods returning nullable
entities:

```kotlin
fun findByEmail(email: String): User?
```

For composite unique indexes:

```kotlin
fun findByRequesterIdAndRecipientId(
    requesterId: Long,
    recipientId: Long,
): Friendship?
```

If a unique index includes nullable columns, generated docs should call
out database-specific null uniqueness behavior. The first version may
choose not to generate `findBy...` helpers for nullable unique indexes
unless the semantics are clear.

## Non-Unique Indexes

Non-unique indexes should generate query helpers returning the normal
query builder:

```kotlin
fun queryByAuthorId(authorId: Long): PostQuery =
    query {
        where(Post.authorId eq authorId)
    }
```

Returning a query builder keeps ordering, pagination, eager loading, and
terminal operation choices available:

```kotlin
client.posts
    .queryByAuthorId(authorId)
    .orderBy(Post.createdAt.desc())
    .limit(20)
    .all()
```

## Naming

Recommended naming:

- `findBy...` for unique index helpers
- `queryBy...` for non-unique index helpers

Examples:

```kotlin
findByEmail(email)
findByOrgIdAndSlug(orgId, slug)
queryByAuthorId(authorId)
queryByAuthorIdAndCreatedAt(authorId, createdAt)
```

Names should use Kotlin property names, not raw storage column names.

## Partial Indexes

Partial indexes need special handling because the index only applies
when the predicate is true.

For example:

```kotlin
index(User.email)
    .unique()
    .where("deleted_at IS NULL")
```

Possible approaches:

1. Do not generate helpers for partial indexes in the first version.
2. Generate helpers with names that include the predicate meaning when
   the schema provides a semantic name.
3. Generate helpers only when the partial predicate is represented by
   typed schema metadata instead of raw SQL.

The recommended first version is to skip helper generation for raw-SQL
partial indexes unless the index has an explicit helper name.

## Configuration

Potential Gradle configuration:

```kotlin
entkt {
    queries {
        generateIndexHelpers.set(true)
    }
}
```

Potential per-index configuration:

```kotlin
index(Post.authorId, Post.createdAt)
    .queryHelper("queryByAuthor")
```

The first implementation can generate helpers automatically for simple
indexes and provide an opt-out later if naming conflicts appear.

## Strict Mode

Index helpers should be introduced as guidance, not as a hard
restriction.

A later strict mode could warn or fail when application code uses query
patterns that do not begin with an indexed predicate:

```kotlin
entkt {
    queries {
        requireIndexedPredicates.set(true)
    }
}
```

Strict mode is not part of the first version. It is difficult to enforce
reliably with arbitrary predicates, dynamic query construction, admin
tools, and small tables.

## Relationship To Privacy

Indexed helpers should not bypass privacy.

Generated helpers should build normal query objects or call normal repo
methods:

```kotlin
fun queryByAuthorId(authorId: Long): PostQuery =
    query {
        where(Post.authorId eq authorId)
    }
```

This ensures:

- LOAD privacy still runs for returned entities
- eager-load privacy still runs
- `visibleCount()` and `exists()` keep their privacy semantics
- `rawCount()` remains explicitly raw

## Relationship To Schema Printer

The schema printer can show available indexes and the helper methods they
generate.

Example:

```text
posts
  indexes
    posts_author_created_idx (author_id, created_at)
      helpers:
        queryByAuthorId(authorId)
        queryByAuthorIdAndCreatedAt(authorId, createdAt)
```

This would make the generated access patterns easier to discover.

## Open Questions

- Should helper generation be on by default?
- Should unique nullable indexes generate `findBy...` helpers?
- Should partial indexes be skipped unless explicitly named?
- Should helper names be configurable per index?
- Should helpers generate overloads for composite index prefixes?
- Should helpers support sort/order hints when the query orders by later
  index columns?
- Should there be a diagnostic task that lists non-indexed generated
  query paths?

## Test Requirements

Before implementation, add tests for:

- unique single-column indexes generate `findBy...`
- unique composite indexes generate `findBy...And...`
- non-unique single-column indexes generate `queryBy...`
- composite indexes generate left-prefix helpers only
- helpers use Kotlin property names
- helpers preserve normal LOAD privacy behavior
- helpers return normal query builders for non-unique indexes
- storage-key column names do not leak into helper names
- helper name collisions are detected with a clear error
- partial raw-SQL indexes are skipped or handled explicitly

