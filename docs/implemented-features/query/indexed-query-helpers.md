# RFC: Indexed Query Helpers

## Status

**Implemented.** Each repo with at least one eligible index exposes an
`indexes` namespace (`client.<repo>.indexes...`) of staged, left-prefix
builders that delegate to the normal generated `{Entity}Query`, so
privacy, read interceptors, and eager-loading behave identically to a
hand-written query. See the user guide:
[Queries → Indexed Query Helpers](../../04-queries.md#indexed-query-helpers).

### What shipped

Runtime (`schema/src/main/kotlin/entkt/query/`):

- `IndexRangeBuilder<E, T : Comparable<T>>` — the receiver for a range
  block (`{ gte(since); lt(until) }`); validates at most one lower and
  one upper bound and at least one bound, with clear errors.

Codegen (`codegen/src/main/kotlin/entkt/codegen/`):

- `IndexHelperGenerator` emits `${Entity}Indexes` (the namespace) plus a
  nested stage class per left prefix and per range block. Eligibility,
  the merged prefix tree, and the helper-name collision check live in
  `indexHelperTree` / `eligibleResolvedIndexes` / `resolveIndexColumns`.
- `RepoGenerator` adds `val indexes: ${Entity}Indexes` (only when ≥1
  helper is eligible); `EntGenerator` emits the `${Entity}Indexes` file
  conditionally.
- `SchemaInspector` explain/printer output lists the generated helper
  paths per index (`ExplainedIndex.helpers`).

### V1 decisions (all settled and implemented)

- generate helpers automatically for eligible btree-compatible indexes
- use the repository namespace `indexes`
- generate every valid left-prefix helper
- support range blocks on the next comparable indexed column after the
  current equality prefix
- let indexed stages call `query { ... }` with the normal query DSL
- skip raw-SQL partial indexes and native/non-btree indexes
- skip unique terminals for nullable unique indexes
- express index-friendly ordering through `query { orderBy(...) }`
- defer generated sort helpers, strict mode, Gradle configuration, and
  diagnostic tasks

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
class User : EntSchema("users") {
    override fun id() = EntId.long()

    val email = string("email").unique()
}

class Post : EntSchema("posts") {
    override fun id() = EntId.long()

    val authorId = long("author_id")
    val createdAt = time("created_at")
    val sequence = long("sequence")

    val byAuthorCreated = index("idx_posts_author_created", authorId, createdAt)
    val byAuthorCreatedSequence =
        index("idx_posts_author_created_sequence", authorId, createdAt, sequence)
}

class Friendship : EntSchema("friendships") {
    override fun id() = EntId.long()

    val requesterId = long("requester_id")
    val recipientId = long("recipient_id")

    val byRequesterRecipient =
        index("idx_friendships_requester_recipient", requesterId, recipientId).unique()
}
```

Potential generated API:

```kotlin
client.users.indexes
    .email("a@example.com")
    .orNull()

client.posts.indexes
    .authorId(userId)
    .query {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
        limit(20)
    }
    .allOrError()

client.posts.indexes
    .authorId(userId)
    .createdAt { gte(since); lt(until) }
    .query {
        orderBy(Post.createdAt.desc())
        limit(20)
    }
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
- Do not support arbitrary non-equality operators in index stages beyond
  range blocks for comparable columns.
- Do not generate order-specific helper methods in V1. Ordering stays in
  the normal query DSL.

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
- records range predicates when the next comparable indexed field is
  bound with a range block
- exposes `query { ... }` returning the normal generated query builder
- exposes methods for the next valid indexed fields
- exposes unique terminals only when the currently-bound prefix is
  itself known to be unique

`query()` with no block remains valid. `query { ... }` accepts the same
DSL block as `client.posts.query { ... }`; additional `where()` calls
are ANDed with the equality predicates seeded by the indexed prefix.
Helper parameters are non-null in V1. Nullable indexed columns can still
generate helper stages for non-null values; callers who need `IS NULL`
should use the normal query DSL.

Example generated shape for a non-unique index on
`(author_id, created_at)`:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .query()

client.posts.indexes
    .authorId(authorId)
    .createdAt(createdAt)
    .query {
        where(Post.published eq true)
    }

client.posts.indexes
    .authorId(authorId)
    .createdAt { gte(since); lt(until) }
    .query {
        orderBy(Post.createdAt.desc())
        limit(20)
    }
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
- `query { ... }` applies the normal query DSL after the indexed-prefix
  predicates have been seeded
- range blocks on comparable indexed columns apply the corresponding
  `gt`, `gte`, `lt`, and `lte` predicates before the normal query DSL
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
(author_id, created_at, sequence)
```

Generate:

```kotlin
client.posts.indexes.authorId(authorId).query()
client.posts.indexes.authorId(authorId).createdAt(createdAt).query()
client.posts.indexes.authorId(authorId).createdAt(createdAt).sequence(sequence).query()
```

Do not generate:

```kotlin
client.posts.indexes.createdAt(createdAt)
client.posts.indexes.sequence(sequence)
client.posts.indexes.authorId(authorId).sequence(sequence)
```

unless separate indexes support those access patterns.

This mirrors how most relational databases use b-tree composite indexes:
left-prefix predicates are index-friendly, while skipping leading
columns usually is not. A query that binds the first column and skips a
later column may still use the index for the bound prefix, but it cannot
seek as precisely as the full left-to-right prefix.

## Range Prefixes

After an equality prefix, V1 should generate range-block overloads for
the next indexed column when that column already supports `gt`, `gte`,
`lt`, and `lte` in the normal query DSL. The equality prefix may be
empty, so a single-column index on a comparable column can generate a
root range helper.

For an index on:

```text
(author_id, created_at, sequence)
```

Generate:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .createdAt { gte(since); lt(until) }
    .query()
```

That should be equivalent to:

```kotlin
client.posts.query {
    where(Post.authorId eq authorId)
    where(Post.createdAt gte since)
    where(Post.createdAt lt until)
}
```

A range stage terminates the indexed chain. It exposes `query()` and
`query { ... }`, but it does not expose later indexed columns or unique
terminals:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .createdAt { gte(since) }
    .sequence(sequence) // not generated
```

This matches common b-tree behavior: equality predicates can narrow a
left prefix, and one range-constrained column can narrow the next
column. Columns after the range can still be filtered in
`query { where(...) }`, but they are no longer part of the precise
indexed seek exposed by the helper chain.

The range block must add at least one bound. It may include at most one
lower bound (`gt` or `gte`) and at most one upper bound (`lt` or `lte`).
An empty range block or duplicate same-side bounds should fail with a
clear generated validation error rather than silently creating an
ambiguous query.

## Index-Friendly Ordering

Ordering stays in the normal query DSL. V1 should document that ordering
by the next indexed column after an equality prefix is an intended
index-friendly pattern:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .query {
        orderBy(Post.createdAt.desc())
        limit(20)
    }
```

This keeps generated APIs compact while still letting the database use
an index like `(author_id, created_at)` for the common "latest rows for
owner" shape. V1 does not generate order-specific helper methods such
as `orderByCreatedAtDesc()`.

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

Unique index helpers should expose unique terminals only when the
currently bound equality prefix exactly equals the full column list of
an eligible unique index, and none of that index's columns are nullable.

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

Range-bound stages never expose unique terminals, even when the range
would happen to match one row at runtime.

If a unique index includes nullable columns, generated docs should call
out database-specific null uniqueness behavior. V1 skips unique
terminals for nullable unique indexes unless the semantics are
explicitly modeled later. Such indexes may still generate `query()`
stages.

Unique terminal error semantics should match query-shaped reads, not
ID-shaped reads:

- `orNull()` returns `null` for no matching row and throws LOAD privacy
  denial
- `visibleOrNull()` returns `null` for no matching row or LOAD privacy
  denial
- `orError()` returns
  `EntResult.Err(EntError.NotFound(entity, EntOperation.QUERY, id = null))`
  for no matching row and maps privacy denial like `firstOrError()`
- `orThrow()` delegates to `orError().getOrThrow()`

## Nullable Indexed Columns

V1 helper parameters are non-null, matching the existing generated
column reference `eq(value)` API. A helper for a nullable indexed column
therefore binds only non-null values:

```kotlin
client.users.indexes
    .nickname("casey")
    .query()
```

That is equivalent to:

```kotlin
client.users.query {
    where(User.nickname eq "casey")
}
```

V1 does not lower `null` helper arguments to `IS NULL`. To query null
keys, callers should use the normal query DSL:

```kotlin
client.users.query {
    where(User.nickname.isNull())
}
```

Nullable indexed columns may still generate `query()` stages for
non-null values. Nullable unique indexes do not generate unique
terminals in V1.

## Non-Unique Indexes

Non-unique index stages should expose `query()` returning the normal
query builder, plus `query { ... }` for the normal query DSL. Stages
for comparable columns should also expose range blocks:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .query {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
        limit(20)
    }
```

The generated query should be equivalent to:

```kotlin
client.posts.query {
    where(Post.authorId eq authorId)
    where(Post.published eq true)
    orderBy(Post.createdAt.desc())
    limit(20)
}
```

Range-bound stages work the same way:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .createdAt { gte(since); lt(until) }
    .query {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
        limit(20)
    }
```

Returning a query builder keeps ordering, pagination, eager loading, and
terminal operation choices available:

```kotlin
client.posts.indexes
    .authorId(authorId)
    .query {
        orderBy(Post.createdAt.desc())
        limit(20)
    }
    .allOrError()
```

## Index Sources

The first version should generate helpers only from btree-compatible
indexes over btree-helper-compatible columns. An index is eligible only
when all native index metadata is absent:

- `where == null`
- `using == null`
- `opclasses == null`
- `with == null`

Metadata checks are not enough by themselves. Every indexed column must
also be compatible with generated btree-style equality helpers. V1
excludes:

- typed JSON fields
- pgvector fields
- any field with native `ColumnStorage.Native`
- `BYTES` fields, unless a later RFC defines byte-array value semantics
  for query helpers explicitly

Range blocks have a narrower requirement: the next indexed column must
map to a generated comparable query column with `gt`, `gte`, `lt`, and
`lte` support. In V1 that means string/text, numeric, and time fields.
Bool, UUID, enum, bytes, JSON, pgvector, and native-storage fields do
not generate range blocks.

Eligible sources are:

- explicit non-partial index property declarations using `index(...)`
- synthesized unique indexes from `field.unique()`
- synthesized unique indexes from `belongsTo(...).unique()`

V1 must not generate helpers for native/non-btree indexes such as
Postgres pgvector HNSW/IVFFlat indexes. Those indexes have different
operator semantics, so equality/range helper generation would be
misleading.

Plain `index(...)` declarations over native fields are also ineligible,
even when the resulting index metadata has no `using`, `opclasses`, or
`with` values. If the schema layer later rejects those declarations
before codegen, the helper generator should still keep this eligibility
check so generated APIs do not depend on driver-specific DDL failure.

## Helper Name Resolution

Generated helper method names should use the generated column-ref
property name. They should not use raw storage column names, index
declaration property names, or index names.

The mapping is:

- `id` maps to `id`
- declared scalar fields map to the generated companion column-ref
  property name, currently `toCamelCase(field.name)`
- implicit `belongsTo(...)` FKs map to the generated FK property name,
  currently `${toCamelCase(edge.name)}Id`
- field-backed FKs map to the captured backing field declaration name,
  matching the existing generated FK property name

For example, `long("author_id")` maps to `authorId`, regardless of the
schema property name holding that field builder. A field-backed FK maps
to the backing field's generated FK property name, not the relationship
edge name.

Equality and range overloads for the same indexed column are intentional:

```kotlin
client.posts.indexes.authorId(authorId).createdAt(createdAt)
client.posts.indexes.authorId(authorId).createdAt { gte(since); lt(until) }
```

Those overloads have different signatures and should coexist. Codegen
should fail with a clear collision error only when helper generation would
produce ambiguous signatures, or when two distinct indexed columns resolve
to the same generated helper name at the same stage.

## Naming

V1 naming:

- repository namespace: `indexes`
- first-stage methods: the generated column-ref property name for the
  first indexed field, such as `authorId(...)`
- subsequent-stage methods: the generated column-ref property name for
  the next indexed field, such as `createdAt(...)`
- range blocks: the generated column-ref property name for the next
  comparable indexed field, such as
  `createdAt { gte(since); lt(until) }`
- query builder entrypoint: `query()`
- query builder DSL entrypoint: `query { ... }`
- unique terminals: `orNull()`, `visibleOrNull()`, `orError()`,
  `orThrow()`

Examples:

```kotlin
client.users.indexes.email(email).orNull()

client.posts.indexes.authorId(authorId).query()

client.posts.indexes
    .authorId(authorId)
    .createdAt(createdAt)
    .query {
        where(Post.published eq true)
    }

client.posts.indexes
    .authorId(authorId)
    .createdAt { gte(since); lt(until) }
    .query {
        orderBy(Post.createdAt.desc())
    }
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

V1 skips helper generation for raw-SQL partial indexes because the
helper cannot safely seed the partial predicate into the generated typed
query. This is a correctness boundary, not a statement that partial
indexes are unimportant. A later version can add helpers for partial
indexes when the partial predicate is represented by typed schema
metadata instead of raw SQL; the current `indexes...query { ... }`
shape can still work because generated stages already seed predicates
before returning the normal query builder.

## Configuration

V1 generates helpers automatically for eligible indexes. A later version
can add a Gradle opt-out if naming conflicts or completion noise become
a practical problem.

Per-index helper naming is intentionally not part of V1. The staged
builder API derives method names from generated column-ref property
names, and conflicts should fail codegen with a clear error.

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

## Deferred Work

- Gradle configuration for opting out of helper generation
- unique terminals for nullable unique indexes, if entkt later models
  database-specific null uniqueness semantics explicitly
- helpers for partial indexes with typed partial predicates
- nullable helper parameters that lower `null` to `IS NULL`, if entkt
  later decides that is clearer than keeping null checks in the normal
  query DSL
- generated sort/order helper methods for queries that order by later
  index columns
- a diagnostic task that lists non-indexed generated query paths

## Test Requirements

Before implementation, add tests for:

- repos with eligible indexes expose an `indexes` namespace
- native/non-btree indexes, including pgvector HNSW/IVFFlat indexes, do
  not generate index helpers
- plain `index(...)` declarations over pgvector/native fields do not
  generate index helpers
- non-helper-compatible column types such as JSON, pgvector, native
  storage, and bytes do not generate equality helper stages
- unique single-column indexes generate unique terminals
- unique composite indexes generate unique terminals only at the full
  unique stage
- unique composite prefixes expose `query()` but not unique terminals
- unique terminals are generated only for full non-nullable unique index
  equality prefixes
- range-bound stages do not expose unique terminals
- non-unique single-column indexes generate query stages
- composite indexes generate left-prefix staged helpers only
- comparable next indexed columns generate range-block stages
- equality and range-block overloads for the same indexed column coexist
- non-comparable indexed columns do not generate range-block stages
- range-block stages expose `query()` and `query { ... }` but no later
  indexed-column methods
- empty range blocks fail with a clear generated validation error
- duplicate same-side range bounds fail with a clear generated
  validation error
- shared prefixes from multiple indexes merge into one staged prefix
  tree
- helper method names use generated column-ref property names, not raw
  storage column names or index declaration names
- scalar fields, implicit FKs, and field-backed FKs use the same names
  as their generated column refs / FK properties
- helpers preserve normal LOAD privacy behavior
- helpers preserve read interceptor behavior
- helper `query()` returns normal query builders
- helper `query { ... }` accepts the normal query DSL
- extra `where()` predicates in `query { ... }` are ANDed with the
  indexed-prefix predicates
- `query { orderBy(...) }` works after equality-prefix helpers and range
  helpers
- storage-key column names do not leak into helper names
- stage/method name collisions are detected with a clear error
- partial raw-SQL indexes are skipped
- nullable non-unique indexes generate non-null helper parameters
- nullable composite prefixes generate non-null helper parameters
- nullable unique indexes do not generate unique terminals in V1
