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
three fields:

- **`before`** — the loaded current owner row. `update(id)` performs
  an internal `byId` load before any hook runs (bypassing LOAD
  privacy), so `before` is always present and reflects database state
  at the moment of the load.
- **`patch`** — a snapshot of the requested patch as accumulated *up
  to this hook*. It's a `${Entity}UpdatePatch` whose fields are
  `FieldPatch<T>` (`Unset` or `Set(value)`). The snapshot is taken
  at hook entry; writes through `mutation` during this hook do not
  change the snapshot, but the next hook (and the canonical patch
  built after the loop) sees them through fresh state.
- **`mutation`** — a restricted writable view (`${Entity}UpdateMutationView`).
  Hooks call `mutation.foo = "x"` to set a value, `mutation.foo = null`
  to explicitly clear a nullable field, or `mutation.unsetFoo()` to
  remove the entry from the patch entirely. The view does not expose
  `save()`, the loaded `before` row, the owner `id`, or other
  internal builder state.

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

1. `beforeSave` (receives `UserMutation`)
2. `beforeCreate` (receives `UserCreate`)
3. Field extraction + defaults
4. Field validation (generated from schema validators)
5. Build `WriteCandidate`
6. Privacy create check
7. Entity validation create (see [Validation](validation.md))
8. `driver.insert(...)`
9. `afterCreate` (receives `User`)
10. Load privacy on returned entity

For an **update**:

 1. **Syntactically empty check.** If `update(id) { }` was called with
    no field assignments, `save()` throws `EntNoChangesException`
    *before* loading the owner row — request shape, not database state,
    classifies the no-op (avoids leaking whether the id exists)
 2. **Internal current-row load** via `driver.byId(id)` (bypasses LOAD
    privacy). Missing row → `save()` returns `null` (or `saveOrThrow()`
    throws `EntNotFoundException`)
 3. `beforeSave` (receives `UserMutation`)
 4. `beforeUpdate` (receives `UserUpdateHookContext` — each hook gets a
    fresh `patch` snapshot built from the current dirty state; hooks
    may write through `ctx.mutation` or call `unsetFoo()` to remove
    entries)
 5. **Required-not-null check** on dirty fields (after hooks, so a
    hook can repair an explicit `name = null` via `unsetName()` or by
    reassigning a value)
 6. Build the canonical requested patch
 7. **Hook-cleared empty path:** if all dirty fields were unset by
    hooks, run UPDATE privacy on the unchanged candidate, then throw
    `EntNoChangesException` (skip update defaults, validation, the
    driver write, `afterUpdate`, and returned LOAD privacy)
 8. Apply update defaults (e.g. `updatedAt = updateDefaultNow()`) to
    produce the effective patch
 9. Field validators run on the effective patch's `Set` entries
10. Build the database write set from the effective patch — only
    `Set` entries are sent to `driver.update`; untouched columns are
    not round-tripped
11. Build the full after-state `WriteCandidate` by folding the
    effective patch over `before`
12. Privacy update check
13. Entity validation update
14. `driver.update(...)` — returns the persisted row
15. `afterUpdate` (receives the persisted `User`)
16. Load privacy on returned entity

For a **delete**:

1. Build `WriteCandidate`
2. Privacy delete check
3. Entity validation delete
4. `beforeDelete` (receives `User`)
5. `driver.delete(...)`
6. `afterDelete` (receives `User`)

Hooks are for side effects (setting timestamps, logging, notifications),
not for authorization or invariant enforcement. Use
[privacy](privacy.md) for authorization and
[validation](validation.md) for data model invariants.

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
