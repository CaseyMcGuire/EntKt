# RFC: Shape-Preserving Edge Traversal

## Status

Ready for implementation. This is not implemented.

## Summary

Generated edge traversal methods should follow the source query as written.

Today this query is lossy:

```kotlin
client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts().allOrThrow()
```

It looks like:

1. Select the 10 newest active users.
2. Return posts for those users.

But the current implementation only preserves the source `where(...)`
predicates at the edge bridge. Source `orderBy(...)`, `limit(...)`, and
`offset(...)` are silently ignored, so the query actually means "posts for all
active users."

This RFC changes traversal so the source query's selected row set is preserved.
The query above should return posts whose author is one of the 10 newest active
users.

## Motivation

`queryPosts()` reads like it follows the source query. If callers put
`orderBy`, `limit`, or `offset` on the source query, they naturally expect those
options to affect which source rows are traversed.

Silently dropping that source shape can turn a narrow query into a much broader
one. That is surprising and can be a correctness, privacy, and performance
problem.

`withPosts()` already gives users an intuitive way to say "load posts for the
users I selected," but it returns users:

```kotlin
val users = client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(10)
}.withPosts().allOrThrow()
```

`queryPosts()` should use the same source selection, while returning a flat
target query:

```kotlin
val posts = client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts().allOrThrow()
```

## Goals

- Make `source.queryX()` traverse from the source rows selected by the source
  query as written.
- Preserve source `where`, `orderBy`, `limit`, and `offset` after source
  read-path interceptors run.
- Keep predicate-only traversal working.
- Support direct edges, many-to-many edges, and traversal chains.
- Keep target query behavior unchanged: a block passed to `queryX { ... }`
  still configures the target query.
- Avoid materializing source entities in Kotlin just to build the target query.

## Non-Goals

- Do not change eager loading APIs such as `withPosts()`.
- Do not change edge predicates such as `User.posts.has { ... }`.
- Do not apply arbitrary Kotlin LOAD privacy to source rows during traversal.
  Source rows are not returned by `queryX()`. Target rows still run through the
  target read path.
- Do not add general joins, projections, or arbitrary SQL composition.
- Do not preserve source ordering as target ordering. Source ordering selects
  the source row set; target ordering is still controlled by the target query.

## API Semantics

No user-facing API change is required.

### Source Shape Is Preserved

This:

```kotlin
val posts = client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts().allOrThrow()
```

means:

```text
Return posts whose author is one of the 10 newest active users.
```

This:

```kotlin
val posts = client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(10)
    offset(20)
}.queryPosts().allOrThrow()
```

means:

```text
Return posts whose author is one of active users 21-30 in createdAt-desc order.
```

### Target Shape Is Still Target Shape

The block passed to a traversal method configures the target query:

```kotlin
val posts = client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts {
    where(Post.published eq true)
    orderBy(Post.createdAt.desc())
    limit(20)
}.allOrThrow()
```

means:

```text
From the 10 newest active users, return 20 published posts total, ordered by
post createdAt.
```

The target `limit(20)` is a total target-row limit. It is not "20 posts per
user." Use `withPosts { limit(20) }` for per-source eager-load shaping.

### Target Rows Are Not Ordered By Source Order

Source ordering chooses which source rows are traversed. It does not define the
order of returned target rows.

```kotlin
client.users.query {
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts().allOrThrow()
```

does not promise that returned posts are grouped or ordered by the users'
`createdAt`. Callers who care about post order must order the target query:

```kotlin
client.users.query {
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts {
    orderBy(Post.createdAt.desc())
}.allOrThrow()
```

### Result Cardinality

Traversal remains a target-row query. A target row appears at most once even if
multiple selected source rows can reach it, as with many-to-many relationships.
This preserves the current `EXISTS`-style behavior and avoids duplicate entities
from join fan-out.

## Lowering

Traversal should lower the source query into a source-id subquery or an
equivalent `EXISTS` shape that preserves source `orderBy`, `limit`, and
`offset`.

For `User.hasMany<Post>("posts")`, where `Post` has `author_id`, this:

```kotlin
client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts().allOrThrow()
```

can lower roughly to:

```sql
SELECT posts.*
FROM posts
WHERE posts.author_id IN (
  SELECT users.id
  FROM users
  WHERE users.active = true
  ORDER BY users.created_at DESC
  LIMIT 10
)
```

For the opposite direction, such as `Post.queryAuthor()`, the source subquery
selects the source FK:

```sql
SELECT users.*
FROM users
WHERE users.id IN (
  SELECT posts.author_id
  FROM posts
  WHERE posts.published = true
  ORDER BY posts.created_at DESC
  LIMIT 10
)
```

For many-to-many traversal, the source subquery feeds the junction table:

```sql
SELECT posts.*
FROM posts
WHERE posts.id IN (
  SELECT post_tags.post_id
  FROM post_tags
  WHERE post_tags.tag_id IN (
    SELECT tags.id
    FROM tags
    WHERE tags.name LIKE 'kotlin%'
    ORDER BY tags.created_at DESC
    LIMIT 10
  )
)
```

The exact SQL shape is driver-owned. `IN`, `EXISTS`, joins, or CTEs are all fine
when they preserve the same rows and do not introduce duplicates. A driver must
not silently fall back to the old broad traversal behavior.

## Runtime Shape

The current traversal bridge only carries source predicates. The new bridge must
carry the source query shape:

- source table
- source selected column (`id` for source-owned rows, or an FK column when
  traversing from child to parent)
- source predicates
- source order fields
- source limit
- source offset
- source public flags

Because `Predicate` lives in `:schema` while `FrozenQuerySpec` lives in
`:runtime`, do not make `Predicate` depend on `FrozenQuerySpec`. Add this small
driver-facing query shape type in `entkt.query`:

```kotlin
data class TraversalSourceShape<E : Any>(
    val table: String,
    val selectedColumn: String,
    val predicates: List<Predicate<E>>,
    val orderBy: List<OrderField<E>>,
    val limit: Int?,
    val offset: Int?,
    val flags: Set<QueryFlag>,
)
```

`QueryFlag` currently lives in `entkt.runtime.query`, so move `QueryFlag` into
`entkt.query` and update `QueryContext` / `QuerySpecBuilder` to import that
type. Flags are already part of the generated public query DSL, and keeping the
flag type in `:schema` lets traversal predicates stay self-contained without
making `:schema` depend on `:runtime`.

Generated traversal predicates should carry this shape directly. Do not copy
the source fields into multiple ad hoc predicate constructor parameters; that
would make direct-edge and M2M lowering drift as traversal shape evolves.

Add shaped traversal predicate variants in `entkt.query`:

```kotlin
class HasEdgeFromShape<E : Any, Source : Any> @EntktInternal constructor(
    val edge: String,
    val source: TraversalSourceShape<Source>,
) : Predicate<E>()

class HasM2MEdgeFromShape<E : Any, Source : Any> @EntktInternal constructor(
    val edgeName: String,
    val source: TraversalSourceShape<Source>,
) : Predicate<E>()
```

Direct `queryX()` traversal uses `HasEdgeFromShape`. Many-to-many `queryX()`
traversal uses `HasM2MEdgeFromShape`. Existing `HasEdge`, `HasEdgeWith`, and
`HasM2MEdgeFrom` stay in place for edge predicates and for any predicate-only
internal paths that do not need source ordering or bounds.

The generated traversal code still runs the source read interceptors at terminal
time, as it does today, so `*OrError` terminals can still convert interceptor
rejections into `Err(QueryRejected)`.

## Interceptor Semantics

Source read-path interceptors must run before the source shape is embedded in
the bridge. The embedded source shape is the post-interceptor shape.

This matters for safety limits:

```kotlin
interceptors.global {
    setDefaultLimitIfAbsent(100)
}
```

Once traversal can preserve source limits, limit operations should apply to
`ReadOperation.EDGE_TRAVERSAL`. They should no longer be silent no-ops for that
operation.

Predicate additions from source interceptors continue to narrow the source set.
Target interceptors continue to run on the target query.

## Privacy Semantics

This RFC does not apply source LOAD privacy during `queryX()` traversal. Source
rows are used to define the target query; they are not returned.

Target rows still follow normal target read semantics:

- strict terminals such as `allOrThrow()` throw if a returned target row fails
  target LOAD privacy
- visible terminals such as `visibleAll()` filter target rows according to
  target LOAD privacy
- eager-loaded target edges keep their existing privacy behavior

If callers need source LOAD privacy to decide which source rows are traversed,
they should materialize the source query first and then query by id:

```kotlin
val users = client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(10)
}.visibleAll()

val posts = client.posts.query {
    where(Post.authorId `in` users.map { it.id })
}.allOrThrow()
```

## Difference From `withX`

`withPosts()` and `queryPosts()` should honor the same source query shape, but
they return different shapes and have different source LOAD privacy behavior.

```kotlin
client.users.query {
    orderBy(User.createdAt.desc())
    limit(10)
}.withPosts().allOrThrow()
```

returns:

```kotlin
List<User>
```

with posts attached to each returned user.

Because this returns `User` entities, the source query's terminal applies
source LOAD privacy.

```kotlin
client.users.query {
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts().allOrThrow()
```

returns:

```kotlin
List<Post>
```

as one flat target list.

Because this does not return `User` entities, traversal uses the shaped source
rows only to constrain the target query. It does not apply source LOAD privacy.

`withPosts { limit(5) }` means up to 5 posts per selected user. `queryPosts {
limit(5) }` means 5 posts total from the selected users.

## Implementation Plan

1. Move `QueryFlag` into `entkt.query`, update runtime imports, and add
   `TraversalSourceShape` in `entkt.query`.
2. Add `Predicate.HasEdgeFromShape` and `Predicate.HasM2MEdgeFromShape`.
3. Change generated `queryX()` traversal code to embed the source's post-
   interceptor predicates, order, limit, offset, and public flags in the bridge.
4. Update direct-edge Postgres lowering to use the shaped source query.
5. Update many-to-many Postgres lowering to use the shaped source query through
   the junction table.
6. Change `limitOpsApply(ReadOperation.EDGE_TRAVERSAL)` so interceptor limit
   mutators apply instead of no-oping.
7. Update query explanations to show the shaped traversal source.
8. Replace docs that describe source shape as silently dropped.

## Test Requirements

Before implementation, add tests for:

- source `where` + `orderBy` + `limit` constrains direct traversal
- source `offset` is honored
- source shape works when traversing child-to-parent
- source shape works for many-to-many traversal
- target `where`, `orderBy`, and `limit` still apply to target rows
- target `limit` is total target rows, not per source row
- target rows are not duplicated by many-to-many fan-out
- source read interceptors can add predicates before traversal
- source read interceptors can set or clamp limits before traversal
- `*OrError` terminals still map source interceptor rejection to
  `EntResult.Err(QueryRejected)`
- source LOAD privacy is not applied implicitly
- target LOAD privacy still applies
- traversal chains preserve shape at each hop
- generated direct traversal constructs `HasEdgeFromShape`
- generated many-to-many traversal constructs `HasM2MEdgeFromShape`
- generated docs/examples no longer describe silent dropping as expected behavior
