# RFC: Optimistic Locking

## Status

Possible future feature. This is not implemented.

## Summary

Add first-class support for version-based optimistic concurrency control.

Entities can opt into a generated version field. Updates and deletes then
fail when the row has changed since the entity was loaded.

## Motivation

Many applications need to reject stale writes without holding database locks
across user workflows. Typical examples:

- editing a post in two browser tabs
- updating account settings after another request changed them
- approving an object whose state has already moved on

Without optimistic locking, the last write wins silently.

## Non-Goals

- Do not implement pessimistic locking.
- Do not require every entity to be versioned.
- Do not replace transaction isolation.
- Do not infer version fields by name without explicit schema opt-in.

## Proposed Schema API

Possible mixin:

```kotlin
class Versioned(scope: EntMixin.Scope) : EntMixin(scope) {
    val version by long("version").default(0)
}
```

Include it with the existing declaration API:

```kotlin
val versioning = include(::Versioned)
```

A column bundle alone does not enable concurrency control. An explicit version
marker or dedicated helper still needs design; a field named `version` must
not silently acquire locking behavior.

## Generated Behavior

For updates:

```sql
UPDATE posts
SET title = ?, version = version + 1
WHERE id = ? AND version = ?
```

For deletes:

```sql
DELETE FROM posts
WHERE id = ? AND version = ?
```

A stale expectation returns `MutationResult.Failed` with a typed conflict
exception. `getOrThrow()` throws that exception. Absence and conflicts need
explicit classification as described in [Delete Consistency](../mutation/delete-consistency.md).

## API Shape

A proposed expectation method could extend today's ID-based update API:

```kotlin
client.posts.update(post.id) {
    title = "New title"
}.expectVersion(post.version)
 .saveAndLoad(viewerContext).getOrThrow()
```

The generated `Post` returned from the update should contain the incremented
version.

## Test Requirements

Before implementation, add tests for:

- update increments version
- stale update fails without changing the row
- stale delete fails without deleting the row
- unversioned entities keep current behavior
- transactions preserve optimistic-locking behavior
- structured error includes entity, id, expected version, and actual outcome

