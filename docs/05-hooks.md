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
| `beforeDelete` | `User` | Before deletion | Cleanup, cascading side effects |
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

- **`before`** — the current stored entity, loaded before any update hooks run.
  This load is not blocked by LOAD privacy. The default
  `UpdateConsistency.ReadCurrent` provides a current snapshot.
  `UpdateConsistency.Pessimistic` must be used inside `withTransaction` and
  prevents concurrent updates to the row until the transaction completes.
  See [Drivers — Transactions](10-drivers.md#transactions) for capability
  requirements.
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
  value with one `PendingEdgeOps<TargetIdType>` field per
  helper-eligible `throughLink` M2M edge. Every hook for the save sees the
  same snapshot, and hooks cannot change it. Each per-edge value carries
  `requestedSet?`, `requestedAdds`, `requestedRemoves` — the
  caller's intent fields. `requestedAdds` and `requestedRemoves`
  are disjoint by construction (the mutator rejects same-id
  mixed-direction at the call site, so `add(x); remove(x)` throws
  before either set could receive `x`). Schemas without
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
  `tags.add(...)` / `tags.remove(...)` / `tags.set(...)` M2M mutators.
  The view's `mutation.pendingEdges`
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
post-hook required-not-null check fails the save with
`MutationResult.Failed(EntValidationException)` carrying a
field-named "name is required" violation, before privacy, entity
validation, or persistence.

## Execution Order

For a **create** operation, the full execution order is:

1. Enforce the configured transaction requirement.
2. Run `beforeSave`, then `beforeCreate`.
3. Apply defaults and field validators.
4. Run CREATE privacy and entity validation.
5. Persist the entity.
6. Run `afterCreate`.
7. For `saveAndLoad()`, apply LOAD privacy to the returned entity.
   `save()` discloses no entity and skips this step.

For an **update**:

1. Enforce transaction and consistency requirements.
2. Load the current entity. An absent target is
   `MutationResult.Failed(EntTargetAbsentException)` (write state
   `NotPersisted`) from both `save()` and `saveAndLoad()`.
3. Capture pending edge intent, then run `beforeSave` and `beforeUpdate`.
   Each `beforeUpdate` hook receives a fresh `patch` snapshot and the same
   read-only `pendingEdges` snapshot.
4. Check required fields, apply update defaults, and run field validators.
5. Calculate `edgeChanges`, then run UPDATE privacy and entity validation.
6. Persist scalar and edge changes.
7. Run `afterUpdate`.
8. For `saveAndLoad()`, apply LOAD privacy to the returned entity.

An assignment-free update — an empty request, or one whose hooks removed
every change — is not an error. It still establishes that the target
exists and runs every pre-write phase (hooks, required-field checks,
UPDATE privacy, entity validation), but skips persistence and
`afterUpdate`, then completes as `Success`: `save()` returns `Unit`,
`saveAndLoad()` returns the current entity under the ordinary LOAD
contract.

For a **delete** (`delete(entity)` treats the supplied entity as an ID
handle; `deleteById(id)` runs the same pipeline):

1. Enforce the configured transaction requirement.
2. Reload the current row by id. An absent row is a success —
   `delete` returns `Success(Unit)`, `deleteById` returns
   `Success(false)` — and none of the later steps run.
3. Run DELETE privacy and entity validation against the reloaded row.
4. Run `beforeDelete`.
5. Delete the entity.
6. Run `afterDelete` — only when the final delete actually removed the
   row (a row that disappears after reload skips it).

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
    tx.users.create { name = "Alice"; email = "a@b.com" }.save().orRollback()
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
for every row, and each is atomic: the whole batch shares one
transaction (the caller's, or an EntKt-owned one when the caller has
none), so a hook that throws aborts the operation with no committed
subset.

```kotlin
// Hooks fire for every row
client.users.createMany({ name = "Alice" }, { name = "Bob" })  // beforeSave, beforeCreate, afterCreate × 2
client.users.deleteMany(User.active eq false)                   // beforeDelete, afterDelete per match
```
