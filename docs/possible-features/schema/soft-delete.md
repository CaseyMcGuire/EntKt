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
(`deleteOrThrow(entity)`, `deleteOrError(entity)`,
`deleteByIdOrError(id)`, `deleteMany(vararg predicates)`) turn
into UPDATE-style soft-delete operations instead of DDL DELETE.
These are the methods that exist today — the legacy
Boolean-returning `delete(entity)` and `deleteById(id)` were
removed by the Result Variants RFC (and the *OrError suffix is
the path for any code that needs the "did anything change?"
signal).

The on-the-wire SQL becomes:

```sql
UPDATE posts SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL
```

The `?` for `deleted_at` is bound to an **application-generated
`Instant.now()`** at the framework boundary (not the SQL `now()`
function). Rationale: the value needs to be visible to the
`afterDelete` hook on the entity it receives, without forcing
either a Postgres-only `UPDATE ... RETURNING` round-trip or a
follow-up `SELECT`. Generating the timestamp in Kotlin keeps
the path driver-agnostic, makes test injection trivial (use
`InstantSource` on `EntClient`), and matches how the rest of
the codegen handles "framework-supplied default values."

The `AND deleted_at IS NULL` clause is what makes
double-soft-delete observable — see the "already-deleted rows"
subsection below.

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
calling `client.posts.deleteOrThrow(post)`, the difference
between hard and soft delete is invisible at the API layer.

The `afterDelete` hook receives the entity **with `deleted_at`
populated** to the application-generated timestamp the UPDATE
just bound. Codegen sets it via a `copy(deletedAt = now)` on
the entity passed in.

UPDATE privacy, update validation, and update hooks do **NOT**
fire on soft-delete. The operation is semantically a delete;
running update rules would defeat the "delete is delete" mental
model and would let an entity author block soft-deletion via an
update-validation rule that has nothing to do with deletion.

### Already-deleted rows

Calling `client.posts.deleteOrThrow(softDeletedPost)` is a
no-op (the `AND deleted_at IS NULL` clause matches zero rows).
The result-variants RFC treats `delete()` as idempotent — the
"row already gone" outcome doesn't surface as an error in the
existing hard-delete contract, and soft-delete follows the same
rule:

| Variant | Outcome on already-soft-deleted row |
|---|---|
| `deleteOrThrow(entity)` | returns Unit (matches the existing hard-delete contract for "row already gone" — no error) |
| `deleteOrError(entity)` | `Ok(Unit)` |
| `deleteByIdOrError(id)` | `Ok(false)` (same as the hard-delete contract for missing rows — see `entkt-result-variants-rfc.md` §"Delete APIs"; the existing `deleteByIdOrError(id): EntResult<Boolean>` returns `Ok(true)` when a row was deleted and `Ok(false)` when no row existed) |

Note the contrast with the hard-delete API: hard-delete's
`Ok(false)` covers "no row physically exists." For soft-delete
the same `Ok(false)` covers both "no row physically exists"
AND "row exists but is already soft-deleted" — from the
live-set perspective, both are "nothing to do" and the caller
gets the same signal. Callers that need to distinguish "the
row's been soft-deleted by someone else" from "this id was
never assigned" should query for the row directly with
`withDeleted()` before deleting.

**No-op pipeline behavior.** DELETE privacy, delete validation,
`beforeDelete`, and `afterDelete` do **NOT** fire when the target
row is already soft-deleted — same rationale as `deleteMany`
(DELETE rules typically inspect the entity's "current state,"
which on a soft-deleted row is "already deleted"; running them on
a no-op is wasteful and surprising). The framework detects the
`deleted_at IS NOT NULL` state via the same internal load that the
ID-based update-root pipeline already performs before hooks fire
and short-circuits to the idempotent no-op result above. This
mirrors the `restoreOrThrow(liveRow)` no-op contract specified
elsewhere — both single-row "nothing to do" paths skip the full
pipeline rather than running rules against a row whose state
already matches the requested outcome.

This keeps soft-delete API-compatible with hard-delete: code
written against the result-variants contract works unchanged
when an entity opts into the mixin.

### `deleteMany`

`deleteMany(vararg predicates)` builds its candidate set by
running a query for matching rows, then per-entity-deletes each
one. For soft-deletable entities V1 picks the following
contract:

1. **Candidate query runs through the full read-interceptor chain.**
   `client.posts.deleteMany(Post.tenantId eq tenantId)` fetches
   the candidate set by calling the same interceptor `apply(...)`
   pipeline every other read uses, with
   `QueryContext.operation = ReadOperation.DELETE_CANDIDATES`
   (a new `ReadOperation` value introduced for this case in
   [Read-Path Interceptors](../query/read-path-interceptors.md)).
   This means the `framework:soft-delete` interceptor adds
   `deleted_at IS NULL` to the candidate fetch by default, **and**
   any application/global predicate-shaping interceptors (tenant
   scoping, etc.) also apply uniformly — so `deleteMany` cannot
   accidentally enumerate or delete rows that the tenant-scoped
   read path would have hidden.

   Soft-deleted rows are filtered out at the candidate-fetch step,
   so the per-entity DELETE privacy / validation / hooks pipeline
   does NOT fire on rows already in the deleted state. Running
   delete hooks on rows that wouldn't have anything to do is
   wasteful (privacy especially — DELETE privacy rules typically
   inspect the entity's current state, which on a soft-deleted row
   is "already deleted").

   Limit operations on `DELETE_CANDIDATES` are silent no-ops to
   avoid silently truncating a bulk delete (a
   `MaxLimitInterceptor(maxLimit = 500)` should not turn
   `deleteMany(...)` into "delete the first 500 matching rows"
   without the caller knowing). `addPredicate`, `addAnnotation`,
   and `reject` apply normally; an interceptor that wants to gate
   broad deletes uses `addPredicate` to narrow scope or
   `reject(...)` to refuse the candidate fetch.

2. **No `deleteMany` flag-override in V1.** Callers who
   need to act on already-soft-deleted rows must either load
   them via a separate query (`client.posts.query {
   onlyDeleted() }.allOrThrow()`) and feed the resulting ids
   into `hardDelete*`, or wait for a future block-form API. A `deleteMany { withDeleted(); ... }`
   variant is plausible if use cases emerge (re-deleting
   already-soft-deleted rows for audit-trail reasons,
   mass-purging via hard-delete-after-soft, etc.) but is not
   part of the V1 surface.

3. **Returned count is "rows newly soft-deleted by this
   call."** Same shape as hard-delete's `deleteMany` Int
   return — `count++` per matching row whose UPDATE actually
   modified the row. Rows that race into the deleted state
   between the candidate fetch and the per-row UPDATE
   (`WHERE id = ? AND deleted_at IS NULL` matches zero
   concurrently) silently don't count, mirroring the hard-delete
   "row vanished concurrently" no-op.

`hardDeleteMany`'s candidate query goes through the same
`DELETE_CANDIDATES` interceptor chain as `deleteMany` — tenant
scoping and other predicate-shaping interceptors all still apply,
so a bulk hard-delete cannot escape read-side scoping that the
soft-delete path respected. Per the
[Read-Path Interceptors](../query/read-path-interceptors.md)
limit-by-read-shape rules, limit operations on `DELETE_CANDIDATES`
are silent no-ops, so a `MaxLimitInterceptor` does **not** clamp or
truncate the bulk hard-delete; its `reject(...)` path (and any
other interceptor's `reject(...)`) still fires. The framework
`soft-delete` filter is the one interceptor suppressed:
hardDeleteMany's candidate query does NOT add `deleted_at IS NULL`,
so every matching row (live or soft-deleted) becomes a
physical-delete candidate, and the count is rows actually deleted.

### Hard delete

A separate hard-delete API for the rare case where physical
removal is required:

```kotlin
client.posts.hardDeleteOrThrow(post)
client.posts.hardDeleteOrError(post)
client.posts.hardDeleteByIdOrError(id)
client.posts.hardDeleteMany(vararg predicates)
```

These bypass **only the framework `soft-delete` interceptor** (so
`deleted_at IS NULL` is not added to whatever read the variant
performs) and run as true DDL DELETE. They all share the same
DELETE privacy / validation / hook pipeline as the soft-delete
path, and their result-shape contract matches the result-variants
RFC exactly (`Ok(false)` for missing rows, etc.). What changes
between variants is **which read step the bypass attaches to**:

- **`hardDeleteOrThrow(entity)` / `hardDeleteOrError(entity)`** —
  the caller already holds the entity (loaded through some earlier
  query whose interceptors fired at the load step, including any
  application/global predicate-shaping interceptors). No new
  candidate fetch is issued. The DELETE pipeline runs against the
  already-loaded entity; the bypass is a no-op for these variants
  because there is no read step to attach it to. Note that whatever
  read originally loaded the entity DID respect every interceptor —
  so a tenant-scoped read couldn't have produced an entity from
  another tenant in the first place.
- **`hardDeleteByIdOrError(id)`** — performs an internal `BY_ID`
  load to populate the entity for the DELETE pipeline. That load
  routes through interceptors with `context.operation == BY_ID`,
  with `bypassSoftDeleteFilter` set so soft-deleted rows are
  reachable. Every other interceptor (tenant scoping, etc.) still
  fires; cross-tenant rows return `Ok(false)` exactly like a tenant
  read of the same id would have returned not-found.
- **`hardDeleteMany(vararg predicates)`** — performs the candidate
  fetch routed through interceptors with
  `context.operation == DELETE_CANDIDATES`, again with
  `bypassSoftDeleteFilter` set. Predicate-shaping interceptors fire;
  limit operations are silent no-ops per the
  [Read-Path Interceptors](../query/read-path-interceptors.md)
  limit-by-read-shape rules (a `MaxLimitInterceptor` cannot clamp a
  bulk hard-delete to N rows, though its `reject(...)` path still
  fires).

Mechanically, both `hardDeleteByIdOrError` and `hardDeleteMany` set
a framework-internal `bypassSoftDeleteFilter` capability on the
read step's `QueryContext` that **only** the generated
`soft-delete` interceptor honors — exactly parallel to the
`internalSystemQuery` opt-in model in
[Read-Path Interceptors](../query/read-path-interceptors.md). No
application interceptor can opt into the bypass. The entity-form
variants don't issue an internal read, so they don't need the
flag — their soft-delete bypass is structural: a `DELETE WHERE id
= ?` without `AND deleted_at IS NULL` deletes whatever row is
there. No "legacy" `hardDelete(entity)` / `hardDeleteById(id)`
Boolean variants — symmetric with the soft-delete surface and
consistent with the result-variants RFC's removal of those names.

A schema can opt out of hard-delete by overriding a mixin flag
(`softDelete(allowHard = false)` — the default is `true` because
GDPR-style purges are real). When `allowHard = false`, codegen
omits the `hardDelete*` family.

### Restore

```kotlin
client.posts.restoreOrThrow(deletedPost)
client.posts.restoreOrError(deletedPost)
client.posts.restoreByIdOrError(id)
```

Restore sets `deleted_at = null`. The on-the-wire SQL becomes:

```sql
UPDATE posts SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL
```

It runs through the **UPDATE** pipeline (UPDATE privacy, update
validation, update hooks) since the operation is semantically
un-deleting an entity back into the live working set — an
entity author's update validation rules (e.g. "title must be ≥
3 chars") apply to the row coming back into normal circulation.

The `afterUpdate` hook receives the entity with `deleted_at = null`.

| Variant | Outcome on row that isn't currently soft-deleted |
|---|---|
| `restoreOrThrow(entity)` | returns Unit (no-op, mirroring deleteOrThrow on already-deleted) |
| `restoreOrError(entity)` | `Ok(Unit)` |
| `restoreByIdOrError(id)` | `Ok(false)` (mirroring deleteByIdOrError on missing — same signal whether the id is unassigned or the row is already live) |

**No-op pipeline behavior.** When the row is already live, the
restore is a no-op (the `WHERE deleted_at IS NOT NULL` clause
matches zero rows). UPDATE privacy / update validation /
`beforeUpdate` / `afterUpdate` **do NOT fire on the no-op
path** — the framework probes for the row's current state
first and short-circuits the pipeline before any hook runs.
Same shape as `deleteOrThrow` on an already-soft-deleted row:
no observable change → no hook firing. This avoids running an
entity author's update validation rule (e.g. "title must be ≥
3 chars") against a row that's already in the target state.

For the `restoreByIdOrError(id)` variant, the same short-
circuit applies: a missing id returns `Ok(false)` without
running any pipeline, an already-live row returns `Ok(false)`
without running the update pipeline.

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

**Single-column `.unique()` metadata rewrite.** `ColumnMetadata.unique`
is a bare `Boolean` with no `where` slot, and `PostgresDriver`'s
`createIndexesSql` emits an unconditional `CREATE UNIQUE INDEX` for any
column with `unique = true`. To make the partial-unique guarantee
representable, schema finalization for a soft-deletable entity must:

1. **Clear `ColumnMetadata.unique`** for every column that declared a
   bare `.unique()`. The column-level boolean no longer participates in
   DDL emission for soft-deletable entities.
2. **Synthesize an `IndexMetadata`** equivalent to the cleared
   constraint, with the partial-unique predicate baked in:
   ```kotlin
   IndexMetadata(
       name = "idx_${table}_${columnName}_unique",
       columns = listOf(columnName),
       unique = true,
       where = "deleted_at IS NULL",
   )
   ```

This routes all live-row uniqueness through the index path (which has a
`where` field), so no DDL surface emits an unconditional global unique
constraint for a soft-deletable entity. Composite
`.index(..., unique = true)` already goes through `IndexMetadata`, so
only the `where` field needs to be set there — no metadata rewrite is
needed for the composite case beyond predicate injection.

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
- `deleteOrThrow(entity)` sets `deleted_at` to an application-
  generated `Instant.now()` and runs the delete pipeline
  (DELETE privacy / delete validation / `beforeDelete` /
  `afterDelete` hooks) — NOT update pipeline
- `afterDelete` hook receives the entity with `deleted_at`
  populated to the bound timestamp (no `RETURNING` round-trip
  needed)
- `deleteOrThrow(entity)` on an already-soft-deleted row is a
  silent no-op (no `EntNoChangesException`), matching the
  hard-delete idempotency contract
- `deleteByIdOrError(id)` returns `Ok(true)` on a successful
  soft-delete, `Ok(false)` for both missing ids and already-
  soft-deleted rows (matches the hard-delete
  `EntResult<Boolean>` contract)
- `InstantSource` on `EntClient` overrides the `deleted_at`
  timestamp source (test injection)
- hard delete remains possible only through the explicit
  `hardDelete*` API (no `hardDelete(entity)` / `hardDeleteById(id)`
  Boolean variants — symmetric with the post-result-variants
  delete surface)
- partial unique indexes are generated for soft-deletable
  entities (single-column `.unique()` and composite
  `index(unique = true)`)
- migration from hard-delete to soft-delete drops + recreates
  unique indexes as partial
- restore that would violate a partial-unique constraint
  surfaces as `Err(ConstraintViolation)` / throws
  `EntConstraintViolationException`
- `restoreOrThrow(entity)` on an already-live row is a no-op:
  UPDATE privacy / update validation / `beforeUpdate` /
  `afterUpdate` do NOT fire (short-circuited before pipeline)
- `restoreByIdOrError(id)` returns `Ok(false)` for both
  missing ids and already-live rows, no pipeline runs in
  either case
- `deleteMany(vararg predicates)` skips already-soft-deleted
  rows at candidate-fetch (no delete pipeline runs for them);
  the returned count is "rows newly soft-deleted by this call"
- `hardDeleteMany(vararg predicates)` does NOT filter
  `deleted_at` — both live and soft-deleted matches are
  physically removed
- eager-load (`with{Edge}`) on a soft-deletable target
  excludes deleted target rows
- non-M2M edge predicates (`SomeEdge.has { }` /
  `.hasWhere { }`) exclude soft-deleted target rows in the
  EXISTS subquery
- **regression test for the M2M known gap** asserting current
  behavior, with a TODO comment pointing at the Read-Path
  Interceptors RFC's M2M-walking follow-up; flip when that
  ships
