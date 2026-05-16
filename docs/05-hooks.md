# Lifecycle Hooks

Hooks let you run code before or after entity operations. They receive
the actual generated types (not raw maps), so you get full type safety.

## Registering Hooks

Hooks are registered once at `EntClient` construction time:

```kotlin
val client = EntClient(driver) {
    hooks {
        users {
            beforeSave { it.updatedAt = Instant.now() }
            beforeCreate { it.createdAt = Instant.now() }
            afterCreate { user -> println("Created: ${user.name}") }
        }
        posts {
            beforeSave { it.updatedAt = Instant.now() }
        }
    }
}
```

## Hook Types

| Hook | Receives | When | Use Case |
|------|----------|------|----------|
| `beforeSave` | `UserMutation` | Before both create and update, before validation | Timestamps, computed fields |
| `beforeCreate` | `UserCreate` | Create only, after `beforeSave` | Set creation-only defaults |
| `afterCreate` | `User` | After successful insert | Logging, notifications |
| `beforeUpdate` | `UserUpdateHookContext` | Update only, after `beforeSave` | Audit trails, normalize patch |
| `afterUpdate` | `User` | After successful update | Cache invalidation |
| `beforeDelete` | `User` | Before driver delete | Cleanup, cascading side effects |
| `afterDelete` | `User` | After successful delete | Logging, cascading cleanup |

## The Mutation Interface

`beforeSave` receives a `{Entity}Mutation` interface, which is shared
between the Create and Update builders. This means a single hook works
for both operations:

```kotlin
users {
    beforeSave { mutation ->
        // Works for both create and update
        mutation.updatedAt = Instant.now()
    }
}
```

## The Update Hook Context

The `beforeUpdate` hook receives a `${Entity}UpdateHookContext` with
four fields:

- **`before`** — the loaded current owner row. `update(id)` performs
  an internal current-row load before any hook runs (bypassing LOAD
  privacy), so `before` is always present and reflects database state
  at the moment of the load. With the default
  `UpdateConsistency.ReadCurrent`, the load is a plain `byId`. With
  `update(id, consistency = UpdateConsistency.Pessimistic) { ... }`
  inside `withTransaction`, the load is a row-locking read
  (`SELECT ... FOR UPDATE` on Postgres) so concurrent writers block
  until commit — see [Drivers — Transactions](10-drivers.md#transactions) for the
  capability requirements. For schemas with helper-eligible
  `throughLink` M2M edges, the load primitive may also switch to
  `readRowForUpdate` (or `serializeOwnerEdgeAndRead`) when the
  caller has staged M2M ops — see [Edges → Link-table M2M mutators](03-edges.md#link-table-m2m-mutators).
- **`patch`** — a snapshot of the requested patch as accumulated *up
  to this hook*. It's a `${Entity}UpdatePatch` whose fields are
  `FieldPatch<T>` (`Unset` or `Set(value)`). The snapshot is taken
  at hook entry; writes through `mutation` during this hook do not
  change the snapshot, but the next hook (and the canonical patch
  built after the loop) sees them through fresh state. One caveat:
  an explicit `name = null` for a *required* field shows as
  `FieldPatch.Unset` in `patch` (the type can't carry `Set(null)`
  for a non-nullable `T`); the bad value is observable via
  `ctx.mutation.name` — see "Repairing invalid input in hooks" below.
- **`pendingEdges`** — a read-only `${Entity}PendingEdgeOps`
  aggregator with one `PendingEdgeOps<TargetIdType>` field per
  helper-eligible `throughLink` M2M edge. Captured *once* between
  the owner-row read and the first before-hook, then shared across
  every hook invocation in this save (hooks cannot mutate the
  underlying op log — the mutator surface is deliberately absent
  from `ctx.mutation`). Each per-edge `PendingEdgeOps` carries
  `requestedSet?`, `requestedAdds`, `requestedRemoves` — the
  caller's intent fields, with same-id paired add+remove
  *preserved* on both sets (literal call log). Schemas without
  helper-eligible M2M edges still get an empty
  `${Entity}PendingEdgeOps` so the context shape is uniform.
  Privacy and validation rules see the post-junction-read
  computed delta (added / removed) via `ctx.edgeChanges` — see
  [Privacy](06-privacy.md) and [Validation](07-validation.md).
- **`mutation`** — a restricted writable view (`${Entity}UpdateMutationView`).
  Hooks call `mutation.foo = "x"` to set a value, `mutation.foo = null`
  to explicitly clear a nullable field, or `mutation.unsetFoo()` to
  remove the entry from the patch entirely. The view does not expose
  `save()`, the loaded `before` row, the owner `id`, the public
  `tags.add(...)` / `tags.remove(...)` / `tags.set(...)` M2M mutators,
  or other internal builder state. The view's `mutation.pendingEdges`
  property mirrors `ctx.pendingEdges` for convenience.

```kotlin
import entkt.runtime.orElse  // folds FieldPatch.Unset → fallback, Set → value

users {
    beforeUpdate { ctx ->
        val current = ctx.before.name
        val pending = ctx.patch.name.orElse(current)
        if (pending != current) {
            println("Name changing from $current to $pending")
        }
    }
}
```

Reading pending state always goes through `ctx.patch` — the
property getters on `ctx.mutation` *throw* on untouched fields,
because a default-null getter would conflate `Unset` and explicit
`Set(null)`. Use `mutation` for writing, `patch` for reading.

`unset{Field}()` is hook-only and update-specific. It removes the
entry from the patch entirely, distinct from `mutation.foo = null`
(an explicit clear that survives as `FieldPatch.Set(null)` for
nullable fields). The methods live on a private adapter that hooks
reach through `ctx.mutation`; they are not callable from the public
update DSL block:

```kotlin
client.users.update(id) {
    name = "x"
    unsetName()        // ✗ won't compile — unset is hook-only
}
```

`unset{Field}()` is also absent from the shared `Mutation` interface
that `beforeSave` receives — creates have no patch model to remove
from. If a `beforeSave` hook needs to clear a pending update entry,
move that work to a `beforeUpdate` hook.

### Repairing invalid input in hooks

A `beforeUpdate` hook can fix a builder assignment that would
otherwise fail. For example, if the caller assigned `null` to a
required field:

```kotlin
client.users.update(id) {
    name = null         // required field, would fail if left like this
}.save()
```

A `beforeUpdate` hook can repair it before the post-hook required-not-null
check runs:

```kotlin
users {
    beforeUpdate { ctx ->
        // ctx.mutation.name is observable as null (the field IS in
        // dirtyFields, the getter only throws on untouched).
        if (ctx.mutation.name == null) {
            ctx.mutation.unsetName()        // remove from patch, OR
            // ctx.mutation.name = "Anonymous"  // assign a real value
        }
    }
}
```

`ctx.patch.name` shows `FieldPatch.Unset` in this scenario rather
than the null — `FieldPatch<String>` for a required field can't
represent `Set(null)` by construction. The actual null is observable
through `ctx.mutation.name`. If no hook repairs the assignment, the
post-hook required-not-null check throws
`IllegalStateException("name is required")` before privacy,
validation, or the driver write runs.

## Execution Order

For a **create** operation, the full execution order is:

1. **TransactionRequirement preflight** (RFC #4) — if the client is
   configured with `RequiredForAllWrites`, `save()` throws
   `TransactionRequiredException` when called outside `withTransaction`
   before any hook runs. See [Drivers — Transactions](10-drivers.md#transactions).
2. `beforeSave` (receives `UserMutation`)
3. `beforeCreate` (receives `UserCreate`)
4. Field extraction + defaults
5. Field validation (generated from schema validators)
6. Build `WriteCandidate`
7. Privacy create check
8. Entity validation create (see [Validation](07-validation.md))
9. `driver.insert(...)`
10. `afterCreate` (receives `User`)
11. Load privacy on returned entity

For an **update**:

 1. **Syntactically empty check.** If `update(id) { }` was called with
    no field assignments AND no link-table M2M ops were staged,
    `save()` throws `EntNoChangesException` *before* loading the
    owner row — request shape, not database state, classifies the
    no-op (avoids leaking whether the id exists). An M2M-only update
    (`update(id) { tags.add(x) }` with no scalar fields touched)
    proceeds past this check since the M2M ops are real changes.
 2. **TransactionRequirement preflight** (RFC #4) — same as create. If
    the per-save `consistency = UpdateConsistency.Pessimistic` mode was
    requested, this also asserts the call site is inside
    `withTransaction` *and* the driver advertises
    `supportsReadRowForUpdate`; otherwise it throws
    `TransactionRequiredException` /
    `UnsupportedDriverCapabilityException`.
 3. **M2M preflight** (RFC #5, only on schemas with helper-eligible
    `throughLink` edges, and only when ops are staged). Asserts the
    save is inside `withTransaction` *and* the driver advertises
    either `supportsReadRowForUpdate` or `supportsOwnerEdgeSerialization`,
    then runs a defense-in-depth check that rejects mixed
    replacement+delta state. Order matters: missing transaction
    surfaces `TransactionRequiredException` first, missing capability
    surfaces `UnsupportedDriverCapabilityException` second, mixed-mode
    last.
 4. **Internal current-row load** (bypasses LOAD privacy). The
    primitive depends on per-save mode + driver capability + whether
    M2M ops are pending:
    - `Pessimistic` → `driver.readRowForUpdate(id)` (true row lock)
    - `ReadCurrent` + M2M pending + `supportsReadRowForUpdate` →
      `driver.readRowForUpdate(id)`
    - `ReadCurrent` + M2M pending + `supportsOwnerEdgeSerialization`
      only → `driver.serializeOwnerEdgeAndRead(id)` (cooperative)
    - `ReadCurrent` (no M2M pending) → `driver.byId(id)` (no lock)

    Missing row → `save()` returns `null` (or `saveOrThrow()` throws
    `EntNotFoundException`)
 5. **Pending edge ops snapshot** (RFC #5, M2M-capable schemas only) —
    captured once between the owner-row read and the first before
    hook, immutable for the rest of the save. Surfaced as
    `ctx.pendingEdges` on the update hook context.
 6. `beforeSave` (receives `UserMutation`)
 7. `beforeUpdate` (receives `UserUpdateHookContext` — each hook gets a
    fresh `patch` snapshot built from the current dirty state, plus
    the shared `pendingEdges` snapshot; hooks may write through
    `ctx.mutation` or call `unsetFoo()` to remove entries but cannot
    mutate the M2M op log)
 8. **Required-not-null check** on dirty fields (after hooks, so a
    hook can repair an explicit `name = null` via `unsetName()` or by
    reassigning a value)
 9. Build the canonical requested patch
10. **Compute EdgeChanges** (M2M-capable schemas only) — read current
    junction rows for each edge with pending ops, normalize intent
    into per-edge `added` / `removed` sets, assemble the
    `${Entity}EdgeChangesView` sidecar
11. **Hook-cleared empty path:** if all dirty fields were unset by
    hooks AND no M2M ops are pending, run UPDATE privacy on the
    unchanged candidate (with the empty `edgeChanges` sidecar), then
    throw `EntNoChangesException` (skip update defaults, validation,
    the driver write, `afterUpdate`, and returned LOAD privacy)
12. Apply update defaults (e.g. `updatedAt = updateDefaultNow()`) to
    produce the effective patch
13. Field validators run on the effective patch's `Set` entries
14. Build the database write set from the effective patch — only
    `Set` entries are sent to `driver.update`; untouched columns are
    not round-tripped
15. Build the full after-state `WriteCandidate` by folding the
    effective patch over `before`
16. Privacy update check (receives `edgeChanges` sidecar)
17. Entity validation update (receives `edgeChanges` sidecar)
18. **Owner-row UPDATE** — `driver.update(...)`. M2M-capable schemas
    skip this when the values map is empty (edge-only update with no
    update defaults), in which case the loaded `before` row stands
    in as the after-state
19. **Junction writes** (M2M-capable schemas only) — per-edge
    `driver.insert` for `added` ids and `driver.deleteMany` for
    `removed` ids
20. `afterUpdate` (receives the persisted `User`)
21. Load privacy on returned entity

For a **delete**:

1. **TransactionRequirement preflight** (RFC #4) — same as create/update.
   `RequiredForAllWrites` is enforced here so it covers the
   `deleteById(id)` helper path that would otherwise hit `byId` first.
2. **Internal current-row load** via `driver.byId(id)` (delete-by-id
   only; `delete(entity)` already has the loaded row)
3. Build `WriteCandidate`
4. Privacy delete check
5. Entity validation delete
6. `beforeDelete` (receives `User`)
7. `driver.delete(...)`
8. `afterDelete` (receives `User`)

Hooks are for side effects (setting timestamps, logging, notifications),
not for authorization or invariant enforcement. Use
[privacy](06-privacy.md) for authorization and
[validation](07-validation.md) for data model invariants.

## Hooks and Transactions

Hooks are automatically inherited by transaction-scoped clients:

```kotlin
val client = EntClient(driver) {
    hooks {
        users {
            beforeSave { it.updatedAt = Instant.now() }
        }
    }
}

client.withTransaction { tx ->
    // The beforeSave hook fires here too -- no re-registration needed
    tx.users.create { name = "Alice"; email = "a@b.com" }.save()
}
```

## Multiple Hooks

You can register multiple hooks of the same type. They run in
registration order:

```kotlin
users {
    beforeSave { it.updatedAt = Instant.now() }
    beforeSave { println("Saving user: ${it.name}") }
    // Both run, in order
}
```

## Bulk Operations and Hooks

Bulk operations (`createMany`, `deleteMany`) **fire lifecycle hooks**
for every row. `createMany` delegates to `create { }.save()` per entry,
and `deleteMany` queries for matching entities then calls `delete(entity)`
for each one.

```kotlin
// Hooks fire for every row
client.users.createMany({ name = "Alice" }, { name = "Bob" })  // beforeSave, beforeCreate, afterCreate × 2
client.users.deleteMany(User.active eq false)                   // beforeDelete, afterDelete per match
```
