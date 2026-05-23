# RFC: Indexed Query Helpers

## Status

Possible future feature. This is not implemented.

## Summary

Generate type-safe query helper APIs from declared schema indexes so
applications have an obvious path for index-friendly reads.

Index helpers live under an explicit repository namespace:

```kotlin
client.posts.indexes.authorId(authorId)
```

Composite indexes generate staged builders. Each stage exposes only the
next valid indexed columns, which naturally enforces left-prefix index
order.

Example schema:

```kotlin
class User : EntSchema() {
    val email = string("email").unique()
}

class Post : EntSchema() {
    val authorId = long("author_id")
    val createdAt = instant("created_at")

    override fun indexes() = listOf(
        index("idx_posts_author_created", authorId, createdAt),
    )
}

class Friendship : EntSchema() {
    val requesterId = long("requester_id")
    val recipientId = long("recipient_id")

    override fun indexes() = listOf(
        index("idx_friendships_requester_recipient", requesterId, recipientId).unique(),
    )
}
```

Potential generated API:

```kotlin
client.users.indexes
    .email("a@example.com")
    .orNull()

client.posts.indexes
    .authorId(userId)
    .query()
    .orderBy(Post.createdAt.desc())
    .limit(20)
    .allOrError()

client.friendships.indexes
    .requesterId(requesterId)
    .recipientId(recipientId)
    .orError()
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
- keep generated repo completion clean by grouping helpers under
  `indexes`

## Non-Goals

- Do not remove or restrict the general query builder.
- Do not guarantee that every generated helper is optimal for every
  database planner.
- Do not implement a full query optimizer.
- Do not analyze arbitrary user-written predicates in the first version.
- Do not require index helper usage by default.
- Do not generate helpers for expression indexes unless the schema DSL
  can represent them safely.
- Do not support non-equality operators in the first version.

## Proposed API

Each generated repo gets an `indexes` subobject when at least one index
helper is available.

```kotlin
client.posts.indexes
```

The subobject exposes one method for each valid first indexed field:

```kotlin
client.posts.indexes.authorId(authorId)
```

That call returns a generated staged builder. Each stage:

- records equality predicates for the bound index prefix
- exposes `query()` returning the normal generated query builder
- exposes methods for the next valid indexed fields
- exposes unique terminals only when the currently-bound prefix is
  itself known to be unique

Example generated shape for a non-unique index on
`(author_id, created_at)`:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .query()

client.posts.indexes
    .authorId(authorId)
    .createdAt(createdAt)
    .query()
```

Example generated shape for a unique index on
`(requester_id, recipient_id)`:

```kotlin
client.friendships.indexes
    .requesterId(requesterId)
    .query()

client.friendships.indexes
    .requesterId(requesterId)
    .recipientId(recipientId)
    .orNull()

client.friendships.indexes
    .requesterId(requesterId)
    .recipientId(recipientId)
    .orError()

client.friendships.indexes
    .requesterId(requesterId)
    .recipientId(recipientId)
    .orThrow()
```

The helpers should return existing generated query/repo results so
privacy behavior remains unchanged:

- `query()` returns the normal generated query builder
- `orNull()` should match the repo's `byIdOrNull` style: missing row
  returns `null`, LOAD privacy denial throws
- `visibleOrNull()` should collapse missing row and LOAD privacy denial
  to `null`
- `orError()` should return `EntResult<Entity>`
- `orThrow()` should throw structured entkt exceptions
- query terminals such as `visibleCount()`, `rawCount()`,
  `visibleExists()`, and `rawExists()` keep their normal semantics

## Composite Index Prefixes

Composite indexes should generate staged helpers for valid left
prefixes.

For an index on:

```text
(author_id, created_at, id)
```

Generate:

```kotlin
client.posts.indexes.authorId(authorId).query()
client.posts.indexes.authorId(authorId).createdAt(createdAt).query()
client.posts.indexes.authorId(authorId).createdAt(createdAt).id(id).query()
```

Do not generate:

```kotlin
client.posts.indexes.createdAt(createdAt)
client.posts.indexes.id(id)
client.posts.indexes.authorId(authorId).id(id)
```

unless separate indexes support those access patterns.

This mirrors how most relational databases use b-tree composite indexes:
left-prefix predicates are index-friendly, while skipping leading
columns usually is not. A query that binds the first column and skips a
later column may still use the index for the bound prefix, but it cannot
seek as precisely as the full left-to-right prefix.

## Prefix Tree Generation

Generated stages should be built from a prefix tree of eligible indexes,
not from one isolated builder chain per index.

For indexes:

```text
(author_id, created_at)
(author_id, status)
```

Generate one shared first stage:

```kotlin
val byAuthor = client.posts.indexes.authorId(authorId)

byAuthor.createdAt(createdAt).query()
byAuthor.status(status).query()
```

This keeps the public API compact and makes IDE completion show the
valid next indexed columns after each prefix.

## Unique Indexes

Unique index helpers should expose unique terminals only for prefixes
that are known to be unique.

For a single-column unique index:

```kotlin
client.users.indexes.email(email).orNull()
client.users.indexes.email(email).orError()
client.users.indexes.email(email).orThrow()
client.users.indexes.email(email).visibleOrNull()
```

For a composite unique index:

```kotlin
client.friendships.indexes
    .requesterId(requesterId)
    .query()

client.friendships.indexes
    .requesterId(requesterId)
    .recipientId(recipientId)
    .orNull()
```

The prefix `requesterId(...)` is not unique just because the full
`(requester_id, recipient_id)` index is unique, so it should expose
`query()` but not `orNull()` / `orError()` / `orThrow()`.

If a unique index includes nullable columns, generated docs should call
out database-specific null uniqueness behavior. The recommended first
version is to skip unique terminals for nullable unique indexes unless
the semantics are explicitly modeled later. Such indexes may still
generate `query()` stages.

## Non-Unique Indexes

Non-unique index stages should expose `query()` returning the normal
query builder:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .query()
```

The generated query should be equivalent to:

```kotlin
client.posts.query {
    where(Post.authorId eq authorId)
}
```

Returning a query builder keeps ordering, pagination, eager loading, and
terminal operation choices available:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .query()
    .orderBy(Post.createdAt.desc())
    .limit(20)
    .allOrError()
```

## Index Sources

The first version should generate helpers from:

- explicit non-partial indexes declared by `indexes()`
- synthesized unique indexes from `field.unique()`
- synthesized unique indexes from `belongsTo(...).unique()`

Generated helpers should use Kotlin property names, not raw storage
column names.

## Naming

Recommended naming:

- repository namespace: `indexes`
- first-stage methods: the Kotlin property name for the first indexed
  field, such as `authorId(...)`
- subsequent-stage methods: the Kotlin property name for the next
  indexed field, such as `createdAt(...)`
- query terminal: `query()`
- unique terminals: `orNull()`, `visibleOrNull()`, `orError()`,
  `orThrow()`

Examples:

```kotlin
client.users.indexes.email(email).orNull()

client.posts.indexes.authorId(authorId).query()

client.posts.indexes
    .authorId(authorId)
    .createdAt(createdAt)
    .query()
```

Inside the `indexes` namespace, names should not include `by` or
`queryBy`; the namespace already communicates that these are indexed
access paths.

## Partial Indexes

Partial indexes need special handling because the index only applies
when the predicate is true.

For example:

```kotlin
index("idx_users_email_live", email)
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
partial indexes.

## Configuration

Potential Gradle configuration:

```kotlin
entkt {
    queries {
        generateIndexHelpers.set(true)
    }
}
```

The first implementation can generate helpers automatically for
eligible indexes and provide an opt-out later if naming conflicts or
completion noise become a problem.

Per-index helper naming is intentionally not part of V1. The staged
builder API derives method names from Kotlin property names, and
conflicts should fail codegen with a clear error.

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

Generated stages should build normal query objects or call normal query
terminals:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .query()
```

This ensures:

- LOAD privacy still runs for returned entities
- eager-load privacy still runs
- read interceptors still run
- `visibleCount()` and `visibleExists()` keep their privacy semantics
- `rawCount()` and `rawExists()` remain explicitly raw (skip
  LOAD privacy)

## Relationship To Schema Printer

The schema printer can show available indexes and the helper paths they
generate.

Example:

```text
posts
  indexes
    posts_author_created_idx (author_id, created_at)
      helpers:
        indexes.authorId(authorId).query()
        indexes.authorId(authorId).createdAt(createdAt).query()
```

This would make the generated access paths easier to discover.

## Open Questions

- Should helper generation be on by default?
- Should the namespace be called `indexes` or `indices`? The
  recommendation is `indexes` to match the existing schema API.
- Should unique nullable indexes ever generate unique terminals?
- Should partial indexes be skipped until typed partial predicates
  exist?
- Should helper generation include every left prefix or stop after a
  configurable maximum depth?
- Should helpers support sort/order hints when the query orders by later
  index columns?
- Should there be a diagnostic task that lists non-indexed generated
  query paths?

## Test Requirements

Before implementation, add tests for:

- repos with eligible indexes expose an `indexes` namespace
- unique single-column indexes generate unique terminals
- unique composite indexes generate unique terminals only at the full
  unique stage
- unique composite prefixes expose `query()` but not unique terminals
- non-unique single-column indexes generate query stages
- composite indexes generate left-prefix staged helpers only
- shared prefixes from multiple indexes merge into one staged prefix
  tree
- helpers use Kotlin property names
- helpers preserve normal LOAD privacy behavior
- helpers preserve read interceptor behavior
- helper `query()` returns normal query builders
- storage-key column names do not leak into helper names
- stage/method name collisions are detected with a clear error
- partial raw-SQL indexes are skipped
- nullable unique indexes do not generate unique terminals in V1
