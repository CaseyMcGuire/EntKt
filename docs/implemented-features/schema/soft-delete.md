# RFC: Soft Delete Convention

## Status

Implemented. The packaged convention is available through:

- `entkt.schema.DeletedAt`, a reusable schema mixin that declares
  a nullable `deleted_at` timestamp
- `entkt.runtime.ExcludeDeleted`, a per-entity read-path interceptor
  that adds `deleted_at IS NULL` by default

This RFC intentionally does **not** propose soft-delete-specific
codegen.

## Summary

Soft delete should be a convention built from ordinary Ent
features:

1. A reusable mixin adds a normal nullable `deleted_at` timestamp.
2. Applications soft-delete by updating that timestamp.
3. Applications restore by clearing that timestamp.
4. A per-entity query interceptor hides rows whose timestamp is
   non-null.
5. Generated `delete*` APIs keep their current meaning: physical
   delete.

If an application wants a shorthand API, it can add extension
functions in its own codebase. The generated repo surface should
not grow `softDelete*`, `restore*`, or `hardDelete*` methods.

## Motivation

Many applications need to retain rows for audit, undo, billing,
or compliance workflows. Ent already has the primitives needed
to model that:

- normal fields for lifecycle state
- normal update APIs for changing lifecycle state
- read interceptors for default visibility rules

Treating soft delete as a special generated schema capability
creates unnecessary coupling. It also turns a simple visibility
policy into a large codegen feature with bespoke builder,
patch, hook, migration, uniqueness, and delete semantics.

The framework should provide the small reusable pieces, then let
applications decide whether and where to install them.

## Non-Goals

- Do not change the meaning of generated `delete*` APIs.
- Do not generate `softDelete*`, `restore*`, or `hardDelete*`
  APIs.
- Do not add framework-only fields or hide `deletedAt` from
  generated create/update drafts.
- Do not make soft delete automatic for every schema.
- Do not automatically rewrite unique indexes as partial unique
  indexes.
- Do not solve archival, retention, purge scheduling, or legal
  hold workflows.

## Schema Mixin

The framework can provide a small reusable mixin:

```kotlin
class DeletedAt(scope: EntMixin.Scope) : EntMixin(scope) {
    val deletedAt = time("deleted_at").nullable()
}
```

Usage:

```kotlin
class Post : EntSchema("posts") {
    val softDelete = include(::DeletedAt)

    val title = string("title")
}
```

`deletedAt` is a normal nullable timestamp field:

- it appears on the generated entity
- it appears on create/update drafts
- it appears in update privacy / validation patches
- it participates in hooks exactly like any other mutable field

That is intentional. Soft deletion is just an update to ordinary
application state.

## Soft Delete And Restore

Applications soft-delete with the generated update API:

```kotlin
client.posts.update(postId) {
    deletedAt = clock.instant()
}.saveOrThrow()
```

Applications restore by clearing the timestamp:

```kotlin
unfilteredClient.posts.update(postId) {
    deletedAt = null
}.saveOrThrow()
```

**Restore should use an unfiltered client.** Technically,
`update(id).save()`'s owner-row load routes through
`driver.byId(...)` directly today — it does not pass through the
read-interceptor chain, so `ExcludeDeleted` does **not** block
restore writes through the filtered client. Restore "works" via
the filtered client by accident.

That accident is brittle: if the framework later routes the
owner-row load through the interceptor chain (a reasonable
consistency fix), filtered restore would silently break. The
robust pattern is to reach for the unfiltered client for restore
workflows — same shape applications already use for
`Viewer.PrivacyBypass`-elevated reads (see [Seeing Deleted Rows](#seeing-deleted-rows)).
Restore is an "I know what I'm doing" path, and the unfiltered
client is the natural affordance.

The same caveat applies to non-id-form reads that *do* go through
interceptors: `client.posts.query { where(...) }.firstOrNull()`
on a soft-deleted row returns null through the filtered client.
A restore workflow that looks up the target by anything other
than primary key must use the unfiltered client.

These operations run the existing update pipeline:

- transaction requirement preflight
- owner-row load
- update hooks
- UPDATE privacy
- update validation
- driver update

There is no separate DELETE or RESTORE pipeline. If an
application wants different authorization for soft delete,
restore, or ordinary update, its update privacy / validation
rules can inspect the requested/effective patch.

## Physical Delete

Generated `delete*` APIs retain their current behavior:

```kotlin
client.posts.deleteOrThrow(post)
client.posts.deleteByIdOrError(postId)
client.posts.deleteMany(Post.authorId eq authorId)
```

These physically delete rows. They do not set `deleted_at`.

This preserves the existing mental model:

- `update { deletedAt = now }` means soft delete
- `update { deletedAt = null }` means restore
- `delete(...)` means physical delete

If an application wants names that make that policy harder to
miss, it can add extension functions:

```kotlin
fun PostRepo.softDeleteByIdOrThrow(
    id: Int,
    now: Instant = Instant.now(),
): Post =
    update(id) {
        deletedAt = now
    }.saveOrThrow()

fun PostRepo.restoreByIdOrThrow(id: Int): Post =
    update(id) {
        deletedAt = null
    }.saveOrThrow()
```

Those helpers are application code, not generated framework API.

## Query Interceptor

The framework can provide a reusable per-entity interceptor that
hides deleted rows:

```kotlin
class ExcludeDeleted<E : Any>(
    private val column: String = "deleted_at",
) : QueryInterceptor<E> {
    override fun intercept(scope: InterceptScope<E>, context: QueryContext) {
        scope.addPredicate(Predicate.Leaf(column, Op.IS_NULL, null))
    }
}
```

Registration uses the existing generated client interceptor DSL:

```kotlin
val client = EntClient(driver) {
    interceptors {
        posts(ExcludeDeleted<Post>(), name = "soft-delete")
        comments(ExcludeDeleted<Comment>(), name = "soft-delete")
    }
}
```

The interceptor is per-entity because global interceptors cannot
add typed predicates. A future helper may reduce boilerplate, but
the underlying behavior is just normal per-entity interceptor
registration.

## Read Behavior

For every read path where read interceptors already fire, the
interceptor adds:

```sql
deleted_at IS NULL
```

This includes the currently implemented read-interceptor
coverage:

- root queries
- first/all terminals
- count/exists terminals
- by-id reads that route through query code
- eager-load subqueries
- non-M2M edge predicates
- edge traversals
- `deleteMany` candidate selection, because it already runs
  through `ReadOperation.DELETE_CANDIDATES`

The convention inherits existing read-interceptor limitations.
In particular, any documented M2M path that does not currently
walk target-entity interceptors will not magically gain
soft-delete filtering from this RFC. Fixing that belongs to the
read-path interceptor implementation, not to soft delete.

## `deleteMany`

`deleteMany(...)` remains physical delete. Because its candidate
selection already runs through read interceptors, a client with
`ExcludeDeleted` installed physically deletes only rows that are
visible through that client by default.

That is consistent with other scoping interceptors: tenant
interceptors also narrow bulk-delete candidates.

Applications that need to purge already-soft-deleted rows should
use a client or code path without the `ExcludeDeleted`
interceptor, or should write an explicit purge helper that uses
the lower-level driver/admin path chosen by that application.

## Seeing Deleted Rows

V1 does not need a generated `withDeleted()` / `onlyDeleted()`
query API.

Applications have simple options:

- use a normal filtered client for user-facing reads
- use a separate unfiltered/admin client for workflows that need
  deleted rows
- register a custom interceptor that follows application-owned
  conventions once the application has a generic way to signal
  per-query intent

The runtime already has `QueryFlag.withDeleted` and
`QueryFlag.onlyDeleted` enum members, but generated query builders
do not currently expose generic flag-setting DSL. Wiring those
flags can be a separate query ergonomics RFC. It is not required
for the soft-delete convention.

**Deferring per-query opt-out is safe.** Adding `withDeleted()` /
`onlyDeleted()` DSL later is purely additive: it doesn't reshape
the `ExcludeDeleted` interceptor or change any of the call-site
patterns this RFC ships. Applications that adopt the two-client
pattern now keep working unchanged when the per-query DSL lands,
and can migrate selectively where the per-query shape reads
better. The design does not paint itself into a corner by
deferring.

## Uniqueness

The framework should not automatically rewrite unique indexes.

If an application wants uniqueness only among live rows, it
declares the partial unique index itself:

```kotlin
val byEmailLive = index("idx_users_email_live", email)
    .unique()
    .where("deleted_at IS NULL")
```

If it wants global uniqueness across live and deleted rows, it
uses the normal unconditional unique index.

This keeps the behavior explicit and avoids hidden migration
rewrites.

## Migration Notes

Moving an existing table to this convention is ordinary schema
work:

1. Add a nullable `deleted_at` column.
2. Install the query interceptor on clients that should hide
   deleted rows.
3. Add or change partial unique indexes manually if the
   application wants live-row-only uniqueness.
4. Backfill `deleted_at` only if the application already has
   historical deletion state to preserve.

No generated migration rewrite is part of this RFC.

## Privacy, Hooks, And Validation

Soft delete and restore are updates. They use existing update
privacy, update validation, and update hooks.

Physical delete remains delete. It uses existing delete privacy,
delete validation, and delete hooks.

The framework does not invent separate lifecycle operations for
soft delete. Applications that need policy distinctions can
encode them in update rules by checking whether `deletedAt` is
being set or cleared.

## Test Requirements

Before packaging the convention, add focused tests for:

- `DeletedAt` mixin adds a nullable `deleted_at` field and the
  generated entity exposes `deletedAt`
- generated create/update drafts treat `deletedAt` like a
  normal mutable nullable timestamp field
- `ExcludeDeleted` appends `Predicate.Leaf("deleted_at",
  Op.IS_NULL, null)`
- a client with the interceptor installed excludes rows with
  non-null `deleted_at` from ordinary reads
- eager-load and non-M2M edge predicate reads inherit the filter
  through existing read-interceptor coverage
- documented M2M gaps remain documented regression tests until
  read-path interceptors close them
- `update { deletedAt = now }` runs the update pipeline, not the
  delete pipeline
- `update { deletedAt = null }` runs the update pipeline
- `deleteOrThrow` / `deleteByIdOrError` still physically delete
  rows
- `deleteMany` on a filtered client physically deletes only
  visible candidates
- an unfiltered/admin client can read rows whose `deleted_at` is
  non-null

## Implementation Shape

The implementation should be small:

1. Add the `DeletedAt` mixin.
2. Add the reusable `ExcludeDeleted` interceptor helper.
3. Add docs showing client registration and extension-function
   examples.
4. Add the focused tests above.

Do not add soft-delete recognition to codegen. Do not add
framework-only fields. Do not change generated delete semantics.

## Naming Decisions

These names are picked deliberately; future readers should not
relitigate them without new motivation.

- **`DeletedAt`** for the mixin (vs `SoftDeleteFields`, `Deletable`).
  Names the field it declares, mirroring how `Timestamps` declares
  `createdAt` / `updatedAt`. Doesn't lie about what the type
  *is* — it's a field bundle, not a capability tag.
  `Deletable` overstates (every entity can be deleted);
  `SoftDeleteFields` is clunky and the "Fields" plural is
  misleading since there's one.
- **`ExcludeDeleted`** for the interceptor (vs `HideDeleted`,
  `LiveRowsOnly`). Names the behavior the interceptor performs
  (predicate-shaping: it *excludes*). Matches existing helpers
  named for what they do (e.g. `MaxLimitInterceptor`), not the
  resulting state. `LiveRowsOnly` reads nicely at the call site
  but doesn't say what "live" means and breaks down when the
  same helper is reused for `archived_at` etc.
- **`column: String = "deleted_at"` default.** The companion
  `DeletedAt` mixin uses exactly that column name, so the
  canonical pairing is zero-argument. A wrong column name fails
  loudly (driver throws on an unknown column), so default-naming
  doesn't create silent-mis-filter risk. Requiring the column at
  every registration would be friction without safety.
