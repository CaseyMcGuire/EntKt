# RFC: Soft Delete

## Status

Possible future feature. This is not implemented. The
[Read-Path Interceptors](../query/read-path-interceptors.md)
framework hooks that this feature would build on **are**
implemented — including the reserved `framework:` interceptor
name prefix and the reserved `QueryFlag.withDeleted` /
`QueryFlag.onlyDeleted` enum members — so this RFC can be
specified concretely against the existing surface.

## Summary

Add schema support for soft-deleted entities where delete operations mark a
row as deleted instead of removing it physically, with default reads silently
filtering deleted rows everywhere and explicit opt-ins via `withDeleted()` /
`onlyDeleted()` query-DSL methods.

## Motivation

Many applications need to retain deleted rows for audit, restore, billing, or
compliance workflows.

Soft delete should be generated consistently rather than implemented through
ad hoc hooks in every project — and it should be implemented via the existing
read-path interceptor mechanism, not as a bespoke special case in every
driver.

## Non-Goals

- Do not enable soft delete by default.
- Do not make soft-deleted rows visible unless callers opt in.
- Do not replace hard delete for entities that need it.
- Do not solve archival or retention policies in the first version.

## Proposed Schema API

Mixin opt-in:

```kotlin
override fun mixins() = listOf(softDelete())
```

Generated column on the entity table:

```kotlin
time("deleted_at").nullable()
```

The mixin installs a framework-owned read-path interceptor named
`framework:soft-delete` at codegen time. The `framework:` prefix
is reserved (applications can't register interceptors with that
prefix — enforced at `EntClient` construction), so this name
won't collide with anything user-defined.

## Generated Query API

Every read terminal (and every traversal / eager / edge-predicate
step) routes through the interceptor framework today. The
`framework:soft-delete` interceptor's contract per `QueryFlag`:

| Flag set on the step | Effective filter |
|---|---|
| neither | `deleted_at IS NULL` (interceptor-added predicate; tagged INTERCEPTOR) |
| `withDeleted` | no filter (interceptor is a no-op for this step) |
| `onlyDeleted` | `deleted_at IS NOT NULL` |
| both | construction-time error: the two flags are mutually exclusive |

Flags are public members of the existing `QueryFlag` enum and
attach to a step via DSL on the query builder (the
`withDeleted()` / `onlyDeleted()` methods land with this RFC).
Per the interceptors RFC, public flags **do not propagate
across query steps** — each traversal / eager / edge-predicate
step gets its own flag set, and the soft-delete interceptor
reads `context.flags` per step.

### Per-terminal behavior (with neither flag set)

The interceptor framework already covers every documented read,
so the behavior follows uniformly:

| Terminal family | Soft-delete behavior |
|---|---|
| `allOrThrow` / `allOrError` / `visibleAll` / `visibleAllOrError` | excludes deleted rows |
| `firstOrNull` / `firstOrThrow` / `firstOrError` / `firstVisibleOrNull` | excludes deleted rows |
| `rawCount` / `rawCountOrError` / `visibleCount` / `visibleCountOrError` | counts non-deleted rows only |
| `rawExists` / `rawExistsOrError` / `visibleExists` / `visibleExistsOrError` | true iff a non-deleted row matches |
| `byIdOrNull` / `byIdOrThrow` / `byIdOrError` / `visibleByIdOrNull` | a soft-deleted row returns "not found" (the `deleted_at IS NULL` predicate ANDs with the structural `id = ?`) |
| `queryX()` edge traversals | the source-step EXISTS subquery excludes soft-deleted source rows; the target-side terminal further excludes soft-deleted target rows |
| `with{Edge}` eager loads | excludes soft-deleted target rows |
| `Edge.has { ... }` / `Edge.hasWhere { ... }` predicates (1:1 / 1:N) | excludes soft-deleted target rows in the EXISTS subquery |

The last row covers the read-path interceptors V1 surface for
HasEdge / HasEdgeWith. **The M2M `has` / `hasWhere` path is a
known V1 correctness gap** — the read-path interceptors RFC
documents that `Predicate.HasM2MEdgeFrom` is not walked by the
edge-predicate processor yet, so an M2M `has` block against a
soft-deletable target entity (e.g. `Post.tags.has()` if `Tag`
were soft-deletable) would NOT filter soft-deleted targets in
V1. This RFC must either gate on closing that gap or ship with
the explicit caveat:

> **V1 gap.** M2M edge predicates (`SomeEntity.someM2MEdge.has { }`
> / `.hasWhere { }`) bypass the soft-delete filter on the target.
> The traversal form (`querySomeM2MEdge()`) honors it correctly.
> Tracking with the Read-Path Interceptors RFC's known-gap note.

### Opt-in DSL

```kotlin
client.posts.query { withDeleted() }.allOrThrow()
client.posts.query { onlyDeleted() }.allOrThrow()
```

Both set the corresponding `QueryFlag` on the query builder's
flags set. The flag is in scope only for that step (not
inherited by `.queryX()` chains or `.with{Edge}` sub-blocks —
each step opts in for itself).

### By-id with deleted rows

V1 has no `byIdOrNull(id) { withDeleted() }` shape because
by-id terminals don't accept a flags block today. Callers who
need to look up a soft-deleted row by id express it as a
query:

```kotlin
client.posts.query {
    withDeleted()
    where(Post.id eq deletedPostId)
}.firstOrNull()
```

A typed `byIdOrNull(id) { withDeleted() }` shape is a possible
follow-up if the workaround proves common.

## Generated Delete API

For soft-deletable entities, the existing delete terminals
(`delete(entity)`, `deleteById(id)`, `deleteOrThrow(entity)`,
`deleteOrError(entity)`, `deleteByIdOrError(id)`, `deleteMany`)
turn into UPDATE-style soft-delete operations instead of DDL
DELETE. The on-the-wire SQL becomes:

```sql
UPDATE posts SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL
```

The `AND deleted_at IS NULL` clause is what makes
double-soft-delete observable (see the "already-deleted rows"
subsection below).

### Hook / privacy / validation pipeline

The current hard-delete pipeline runs in this order (see
`docs/07-validation.md` and `docs/05-hooks.md`):

1. DELETE privacy
2. delete validation
3. `beforeDelete` hooks
4. `driver.delete(...)`
5. `afterDelete` hooks

Soft-delete must use the **same** pipeline: DELETE privacy
governs, delete validation rules run, `beforeDelete` /
`afterDelete` hooks fire around the UPDATE — to a caller
calling `client.posts.delete(post)`, the difference between
hard and soft delete is invisible at the API layer. The
hook context for `afterDelete` still receives the entity
that was deleted (with its post-update `deleted_at`
populated so a hook can read it).

UPDATE privacy, update validation, and update hooks do **NOT**
fire on soft-delete. The operation is semantically a delete;
running update rules would defeat the "delete is delete" mental
model and would let an entity author block soft-deletion via an
update-validation rule that has nothing to do with deletion.

### Already-deleted rows

Calling `client.posts.delete(softDeletedPost)` is a no-op
(the `AND deleted_at IS NULL` clause matches zero rows) and
surfaces per the current `EntError.NoChanges` semantics:

| Variant | Outcome on already-deleted row |
|---|---|
| `delete(entity)` | returns Unit (matches the no-op contract for hard-delete on missing row) |
| `deleteOrThrow(entity)` | throws `EntNoChangesException` |
| `deleteOrError(entity)` | `Err(NoChanges)` |
| `deleteById(id)` | `false` |
| `deleteByIdOrError(id)` | `Err(NotFound)` if no row with that id exists at all; `Err(NoChanges)` if the row exists but is already soft-deleted |

The `Err(NotFound)` vs `Err(NoChanges)` split for
`deleteByIdOrError` requires the generated code to probe the
table before the soft-delete UPDATE (analogous to the existing
preflight in `deleteByIdOrError` for hard delete). Generating a
single conditional UPDATE that distinguishes the two outcomes is
a possible future optimization.

### Hard delete

A separate hard-delete API for the rare case where physical
removal is required:

```kotlin
client.posts.hardDelete(post)
client.posts.hardDeleteOrThrow(post)
client.posts.hardDeleteOrError(post)
client.posts.hardDeleteById(id)
client.posts.hardDeleteByIdOrError(id)
```

These bypass the soft-delete interceptor entirely and run as
true DDL DELETE. They share the same DELETE privacy / validation
/ hook pipeline as `delete` — auditing concerns apply equally.

A schema can opt out of hard-delete by overriding a mixin flag
(`softDelete(allowHard = false)` — the default is `true` because
GDPR-style purges are real). When `allowHard = false`, codegen
omits the `hardDelete*` family.

### Restore

```kotlin
client.posts.restore(deletedPost)
client.posts.restoreOrThrow(deletedPost)
client.posts.restoreOrError(deletedPost)
client.posts.restoreById(id)
client.posts.restoreByIdOrError(id)
```

Restore sets `deleted_at = null`. It runs through the
**UPDATE** pipeline (UPDATE privacy, update validation, update
hooks) since the operation is semantically un-deleting an entity
back into the live working set — and an entity author's update
validation rules (e.g. "title must be ≥ 3 chars") apply to the
row coming back into normal circulation.

| Variant | Outcome on row that isn't soft-deleted |
|---|---|
| `restore(entity)` | returns Unit (no-op) |
| `restoreOrThrow(entity)` | throws `EntNoChangesException` |
| `restoreOrError(entity)` | `Err(NoChanges)` |
| `restoreById(id)` | `false` |
| `restoreByIdOrError(id)` | `Err(NotFound)` if the id doesn't exist; `Err(NoChanges)` if the row exists but is already live |

Restore conflicts with active uniqueness constraints are
discussed in [Uniqueness](#uniqueness) below.

## Uniqueness

Soft delete forces a choice between two uniqueness semantics:

- **Global uniqueness** — the unique index covers every row,
  deleted or not. A unique `email` column then prevents you from
  ever re-creating an entity with the email of a soft-deleted
  one, and restore is always conflict-free.
- **Live-row uniqueness** — the unique index is partial,
  scoped to non-deleted rows only. A unique `email` allows
  re-creating an entity with a soft-deleted user's email
  (because that older row is "not really there" from the
  application's perspective), and **restore can conflict** with
  a newer live row holding the same email.

V1 chooses **live-row uniqueness** as the default for
soft-deletable entities, because:

- The whole point of soft delete is "this row is no longer
  observable to the application," and a unique constraint that
  keeps blocking new writes against an invisible row violates
  that contract.
- Re-creating an account / category / tag with the email or
  name of an old soft-deleted row is a very common operational
  pattern; global uniqueness makes it impossible without
  hard-deleting first.

### Migration changes

The codegen-side migration layer already supports partial
unique indexes via
`NormalizedIndex(columns, unique = true, where = "deleted_at IS NULL")`
(see `migrations/src/main/kotlin/entkt/migrations/NormalizedSchema.kt`),
and Postgres renders the WHERE clause correctly
(`postgres/src/main/kotlin/entkt/postgres/PostgresSqlRenderer.kt`).

For a soft-deletable entity, the mixin rewrites every declared
unique index (single-column `.unique()` constraints AND composite
`.index(... , unique = true)` constraints) to add
`where = "deleted_at IS NULL"`. Non-unique indexes are
unaffected.

Migrating an existing table from hard-delete to soft-delete
produces a migration that drops + recreates each unique index
as a partial unique index. The migration RFC's existing
add/drop sequencing covers this.

### Restore conflicts

When restoring a row would violate a partial unique constraint
(another live row already has the same value), the restore
fails with the constraint violation surface used everywhere
else:

| Variant | Outcome on restore conflict |
|---|---|
| `restoreOrThrow(entity)` | throws `EntConstraintViolationException` |
| `restoreOrError(entity)` | `Err(ConstraintViolation)` |
| `restoreByIdOrError(id)` | `Err(ConstraintViolation)` |

Callers that want global uniqueness can opt out of the partial-
index rewrite with `softDelete(uniqueness = Uniqueness.Global)`
— restore is then conflict-free but re-creation with old keys
is blocked.

## Privacy Behavior

DELETE privacy governs `delete(...)` (the soft-delete path) and
`hardDelete(...)` (the hard-delete path) — neither needs special
privacy treatment since both are semantically deletes from the
caller's perspective.

UPDATE privacy governs `restore(...)`, since the operation
materializes a row back into the live working set and is closer
to "update this row's lifecycle state" than "create a new
entity." A `RESTORE` privacy operation could replace this in a
follow-up if the use case for separate restore-vs-update
authorization emerges (e.g. compliance workflows where only
specific roles can restore but not edit).

## Test Requirements

Before implementation, add tests for:

- default queries (every terminal in the table above) exclude
  deleted rows
- `withDeleted()` makes the corresponding terminal include
  deleted rows
- `onlyDeleted()` makes the corresponding terminal return only
  deleted rows
- both flags on the same step → construction-time error
- `delete(...)` sets `deleted_at` and runs delete pipeline
  (privacy / validation / hooks) — NOT update pipeline
- `delete(...)` on an already-deleted row is a no-op
  (`Err(NoChanges)` for the structured variant)
- hard delete remains possible only through the explicit
  `hardDelete*` API
- partial unique indexes are generated for soft-deletable
  entities (single-column `.unique()` and composite
  `index(unique = true)`)
- migration from hard-delete to soft-delete drops + recreates
  unique indexes as partial
- restore that would violate a partial-unique constraint
  surfaces as `Err(ConstraintViolation)` / throws
  `EntConstraintViolationException`
- eager-load (`with{Edge}`) on a soft-deletable target
  excludes deleted target rows
- non-M2M edge predicates (`SomeEdge.has { }` /
  `.hasWhere { }`) exclude soft-deleted target rows in the
  EXISTS subquery
- **regression test for the M2M known gap** asserting current
  behavior, with a TODO comment pointing at the Read-Path
  Interceptors RFC's M2M-walking follow-up; flip when that
  ships
