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
| `beforeDelete` | `User` | Before driver delete | Cleanup, authorization |
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

For a **create** operation, hooks run in this order:

1. `beforeSave` (receives `UserMutation`)
2. `beforeCreate` (receives `UserCreate`)
3. Field validation (generated from schema validators)
4. `driver.insert(...)`
5. `afterCreate` (receives `User`)

For an **update**:

1. `beforeSave` (receives `UserMutation`)
2. `beforeUpdate` (receives `UserUpdate`)
3. Field validation
4. `driver.update(...)`
5. `afterUpdate` (receives `User`)

For a **delete**:

1. `beforeDelete` (receives `User`)
2. `driver.delete(...)`
3. `afterDelete` (receives `User`)

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
