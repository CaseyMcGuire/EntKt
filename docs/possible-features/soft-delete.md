# RFC: Soft Delete

## Status

Possible future feature. This is not implemented.

## Summary

Add schema support for soft-deleted entities where delete operations mark a
row as deleted instead of removing it physically.

## Motivation

Many applications need to retain deleted rows for audit, restore, billing, or
compliance workflows.

Soft delete should be generated consistently rather than implemented through
ad hoc hooks in every project.

## Non-Goals

- Do not enable soft delete by default.
- Do not make soft-deleted rows visible unless callers opt in.
- Do not replace hard delete for entities that need it.
- Do not solve archival or retention policies in the first version.

## Proposed Schema API

Possible mixin:

```kotlin
override fun mixins() = listOf(softDelete())
```

Generated field:

```kotlin
time("deleted_at").nullable()
```

## Generated Query API

Default queries exclude deleted rows:

```kotlin
client.posts.query().all()
```

Opt-in helpers:

```kotlin
client.posts.query {
    withDeleted()
}.all()

client.posts.query {
    onlyDeleted()
}.all()
```

## Generated Delete API

For soft-deleted entities:

```kotlin
client.posts.delete(post)
```

would run update-like SQL:

```sql
UPDATE posts SET deleted_at = now() WHERE id = ?
```

A separate hard-delete API could be explicit:

```kotlin
client.posts.hardDelete(post)
```

## Privacy Behavior

DELETE privacy should govern soft delete. Restoring an entity should probably
use UPDATE privacy or a dedicated RESTORE operation if added later.

## Test Requirements

Before implementation, add tests for:

- default queries exclude deleted rows
- `withDeleted()` includes deleted rows
- `onlyDeleted()` returns only deleted rows
- `delete()` sets `deleted_at`
- hard delete remains possible only through explicit API
- uniqueness constraints with soft-deleted rows are documented

