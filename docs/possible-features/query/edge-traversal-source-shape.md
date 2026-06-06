# RFC: Edge Traversal Source Shape

## Status

Possible future feature. This is not implemented.

## Summary

Make generated edge traversal APIs fail fast or preserve source query shape
when a traversal would otherwise drop source `orderBy`, `limit`, or `offset`.

Today this kind of query is documented as lossy:

```kotlin
client.users.query {
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts().allOrThrow()
```

It means "posts for users matching the source predicates", not "posts for the
10 most recently created users." The source `orderBy` and `limit` do not affect
the traversal bridge.

That is surprising enough that the API should either preserve the source shape
or reject the traversal with a clear error.

## Motivation

`queryPosts()` reads like it follows the source query as written. If the source
query includes ordering or bounds, callers naturally expect those constraints
to apply to the set of source rows whose posts are traversed.

Silently dropping source shape can produce a much broader target read than the
caller intended. That is a correctness risk and, depending on the source
predicates, a privacy and performance risk.

## Goals

- Prevent lossy traversal semantics from surprising callers.
- Preserve the intuitive meaning of traversing from a source query.
- Keep simple predicate-only traversal working.
- Make unsupported source shapes fail with an actionable message.

## Non-Goals

- Do not remove edge traversal APIs.
- Do not change edge predicates such as `User.posts.has { ... }`.
- Do not add arbitrary joins or full SQL composition in this RFC.
- Do not hide unsupported shapes by materializing source rows implicitly.

## Proposed API

### V1: Fail Fast On Lossy Source Shape

Generated traversal should reject source queries that have source-local shape
which the bridge cannot preserve:

```kotlin
client.users.query {
    orderBy(User.createdAt.desc())
    limit(10)
}.queryPosts()
```

throws:

```text
EntUnsupportedTraversalException:
User.queryPosts() cannot preserve source orderBy/limit/offset yet.
Materialize the source query first, or remove the source shape.
```

Predicate-only source queries continue to work:

```kotlin
client.users.query {
    where(User.active eq true)
}.queryPosts().allOrThrow()
```

### V2: Preserve Source Shape

A later implementation can support source-shaped traversal by lowering through a
source subquery or CTE:

```sql
WITH source AS (
  SELECT users.id
  FROM users
  WHERE ...
  ORDER BY users.created_at DESC
  LIMIT 10
)
SELECT posts.*
FROM posts
WHERE posts.author_id IN (SELECT id FROM source)
```

For M2M traversal, the bridge would join the junction table against the shaped
source set.

## Explicit Escape Hatch

If callers want the old broad traversal, they should express that explicitly by
removing source-local shape before traversing:

```kotlin
client.users.query {
    where(User.active eq true)
}.queryPosts().allOrThrow()
```

If callers want "targets for this materialized source page", they can do the
two-step form:

```kotlin
val users = client.users.query {
    orderBy(User.createdAt.desc())
    limit(10)
}.allOrThrow()

val posts = client.posts.query {
    where(Post.authorId `in` users.map { it.id })
}.allOrThrow()
```

This is verbose, but it is explicit and correct.

## Interceptor Interaction

Traversal should consider the effective source shape after read-path
interceptors run.

If an interceptor adds or clamps a source limit before traversal, that limit is
part of the source shape and must either be preserved or rejected. Otherwise an
interceptor-installed safety bound could be silently lost at the bridge.

## Privacy Semantics

This RFC does not change LOAD privacy. Target rows still run through the target
entity's normal read path.

If V2 preserves source shape through a subquery, the source query must still run
through source interceptors before the bridge shape is built.

## Error Shape

The fail-fast error should be a programming/configuration error, not a nullable
or `EntResult` data outcome. Possible options:

- `EntUnsupportedTraversalException`
- `IllegalStateException` with a generated, entity-specific message
- a future structured `EntError.QueryRejected` only if the rejection comes from
  an interceptor rather than the traversal API itself

Prefer a dedicated exception if this becomes a public contract.

## Test Requirements

Before implementation, add tests for:

- predicate-only traversal still works
- source `limit` causes traversal construction or terminal execution to fail
- source `offset` causes traversal to fail
- source `orderBy` causes traversal to fail
- interceptor-added source limits are detected
- error messages name the source entity, edge, and unsupported shape
- M2M traversal applies the same checks
- future shape-preserving lowering honors `orderBy`, `limit`, and `offset`
