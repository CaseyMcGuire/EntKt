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
| `beforeUpdate` | `UserUpdate` | Update only, after `beforeSave` | Audit trails |
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

## Inspecting Current State in Updates

The `beforeUpdate` hook receives `UserUpdate`, which exposes the
current entity state via `.entity`:

```kotlin
users {
    beforeUpdate { update ->
        if (update.name != update.entity.name) {
            println("Name changed from ${update.entity.name} to ${update.name}")
        }
    }
}
```

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

1. `beforeSave` (receives `UserMutation`)
2. `beforeUpdate` (receives `UserUpdate`)
3. Compute final values (dirty tracking)
4. Field validation
5. Build `WriteCandidate`
6. Privacy update check
7. Entity validation update
8. `driver.update(...)`
9. `afterUpdate` (receives `User`)
10. Load privacy on returned entity

For an **upsert**:

1. `beforeSave` (receives `UserMutation`)
2. `beforeCreate` (receives `UserCreate`)
3. Field validation
4. Build `WriteCandidate`
5. Privacy preflight check
6. Entity validation create (update validation rules do not run)
7. `driver.upsert(...)` — the database decides insert vs update
8. If the row was **inserted**: `afterCreate` (receives `User`)
9. If the row was **updated** (conflict): `afterUpdate` (receives `User`)
10. Load privacy on returned entity

Because upsert uses the create builder, `beforeSave` and `beforeCreate` hooks
always run. The "after" hook is chosen based on what the database actually did.
Immutable fields (e.g. `created_at`) are included in the insert but excluded
from the conflict-update set, so they are preserved on subsequent upserts.

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
