# RFC: Request-Scoped Entity Loading

## Status

Possible future feature. This is not implemented.

## Summary

Add request-scoped batching and caching for generated entity loads.

The goal is to reduce repeated `byId` and edge-load queries without changing
the strict privacy semantics of generated reads.

## Motivation

Application code often loads the same entity more than once in a request:

```kotlin
val post = client.posts.byId(postId)
val author = client.users.byId(post.authorId)
val comments = client.comments.query()
    .where(Comment.postId.eq(post.id))
    .all()
val commentAuthors = comments.map { client.users.byId(it.authorId) }
```

Without request-scoped loading, this can produce duplicate queries and classic
N+1 behavior. A loader layer can batch and memoize common entity reads.

## Non-Goals

- Do not weaken LOAD privacy.
- Do not cache across requests by default.
- Do not hide stale-data semantics behind a global cache.
- Do not require a GraphQL runtime.
- Do not batch arbitrary query shapes in V1.

## Proposed API

Add a request scope:

```kotlin
client.withEntityLoadScope {
    val author = users.load(authorId)
    val editor = users.load(editorId)
}
```

The scope can batch compatible loads:

```sql
SELECT * FROM users WHERE id IN (?, ?)
```

It can also deduplicate repeated loads:

```kotlin
val a = users.load(userId)
val b = users.load(userId)
```

Both calls should return the same visible entity result for the current
privacy context.

## Loader Surfaces

Useful generated helpers:

```kotlin
client.users.load(id)
client.users.loadMany(ids)
client.posts.loadAuthor(post)
client.posts.loadAuthors(posts)
```

Edge-aware batch helpers should reuse generated edge metadata so callers do
not need to manually group foreign keys.

## Privacy Semantics

Every entity returned from a loader must pass LOAD privacy.

The loader may batch storage access, but privacy decisions remain per entity:

- if `load(id)` finds a denied row, it should follow the same behavior as the
  corresponding generated strict read API
- if `loadMany(ids)` contains denied rows, the result shape must make denial
  behavior explicit

Open question:

- Should `loadMany` be strict and throw if any row is denied, or return a
  keyed result that can represent missing and denied entries separately?

This should align with any future read-result variants API.

## Cache Boundaries

The default cache must be request-scoped:

- no process-global entity cache
- no reuse across privacy contexts
- no reuse across transactions unless explicitly scoped to that transaction

Mutation invalidation in V1 can be conservative:

- clear the request-scope cache after any write
- or clear only affected entity types once generated metadata can do that
  safely

## Test Requirements

Before implementation, add tests for:

- repeated loads of the same ID issue one storage query inside a scope
- loads outside a scope keep current behavior
- batched loads still enforce LOAD privacy per entity
- cache entries do not cross privacy contexts
- writes invalidate or bypass stale cached entities
- edge-aware batch helpers preserve result ordering and missing-row behavior
