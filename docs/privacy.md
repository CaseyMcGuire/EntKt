# Privacy

Privacy rules control who can read, create, update, and delete entities.
Rules are declared per-entity via policies and enforced automatically by
the generated code -- no manual checks needed at call sites.

## Quick Example

```kotlin
object UserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(
                // Users can see their own profile
                PrivacyRule { ctx ->
                    val v = ctx.privacy.viewer as? Viewer.User
                        ?: return@PrivacyRule PrivacyDecision.Continue
                    if (v.id == ctx.entity.id) PrivacyDecision.Allow
                    else PrivacyDecision.Continue
                },
            )
            create(
                PrivacyRule { ctx ->
                    if (ctx.privacy.viewer is Viewer.Anonymous)
                        PrivacyDecision.Deny("only system can create users")
                    else PrivacyDecision.Continue
                },
            )
            updateDerivesFromCreate()
            deleteDerivesFromCreate()
        }
    }
}

val client = EntClient(driver) {
    privacyContext { PrivacyContext(Viewer.User(currentUserId())) }
    policies {
        users(UserPolicy)
    }
}
```

## Concepts

### Viewer

`Viewer` represents the identity performing an operation:

```kotlin
sealed interface Viewer {
    data object Anonymous : Viewer   // unauthenticated
    data class User(val id: Any) : Viewer  // authenticated user
    data object System : Viewer      // bypasses checks
}
```

### PrivacyContext

`PrivacyContext` bundles the viewer captured for a generated operation.
Scalar operations capture one context and share it across all privacy
checks in that operation. Bulk convenience methods may invoke the
provider once per item because they delegate to the per-entity create or
delete paths; providers should be stable for the duration of a request
or logical operation.

```kotlin
data class PrivacyContext(val viewer: Viewer)
```

### PrivacyDecision

Each rule returns one of three decisions:

| Decision | Meaning |
|----------|---------|
| `Allow` | Stop evaluation, permit the operation |
| `Continue` | Defer to the next rule |
| `Deny(reason)` | Stop evaluation, reject with a reason |

### PrivacyRule

A rule is a `fun interface` that takes a typed context and returns a
decision:

```kotlin
fun interface PrivacyRule<in C> {
    fun run(ctx: C): PrivacyDecision
}
```

Each operation gets its own context type (see [Operation Contexts](#operation-contexts)
below), so rules are type-safe for the operation they guard.

## Setting Up Privacy

### Privacy Context Provider

Tell the client how to determine the current viewer. This lambda is
called at operation time:

```kotlin
val client = EntClient(driver) {
    privacyContext { PrivacyContext(Viewer.User(getCurrentUserId())) }
}
```

If no provider is configured, the default is `Viewer.Anonymous`.

### Policies

Policies group rules for entity operations — both privacy and
[validation](validation.md). Implement `EntityPolicy` and register it
in the client config:

```kotlin
object PostPolicy : EntityPolicy<Post, PostPolicyScope> {
    override fun configure(scope: PostPolicyScope) = scope.run {
        privacy {
            load(/* rules */)
            create(/* rules */)
            update(/* rules */)
            delete(/* rules */)
        }
    }
}

val client = EntClient(driver) {
    policies {
        posts(PostPolicy)
    }
}
```

Each entity's `privacy { }` block exposes four methods matching the
four operations: `load()`, `create()`, `update()`, `delete()`. Each
takes a `vararg` of rules that are evaluated in order.

## Evaluation Semantics

### LOAD -- allow-list

LOAD rules use **allow-list** semantics: if every rule returns
`Continue`, the entity is **denied**. At least one rule must explicitly
`Allow`.

```kotlin
load(
    // Users can see their own profile
    PrivacyRule { ctx ->
        val v = ctx.privacy.viewer as? Viewer.User ?: return@PrivacyRule PrivacyDecision.Continue
        if (v.id == ctx.entity.id) PrivacyDecision.Allow
        else PrivacyDecision.Continue
    },
    // Fallthrough: denied (implicit)
)
```

> **Note:** `Viewer.System` automatically bypasses all privacy checks at
> the framework level — you do not need a rule for it.

LOAD privacy is enforced on:

- `repo.byId(id)` -- throws `PrivacyDeniedException`
- `query.all()` -- throws `PrivacyDeniedException` if any entity is denied
- `query.firstOrNull()` -- throws `PrivacyDeniedException` if the entity is denied; returns `null` only when no matching row exists
- Eager-loaded edges (`withPosts()`, etc.) -- throws `PrivacyDeniedException` if any eagerly loaded entity is denied

`exists()` fetches one row and evaluates LOAD privacy on it, throwing
if denied. `visibleCount()` materializes matching rows, evaluates LOAD
privacy on each, and returns the count of allowed entities (denied
entities are silently excluded). `rawCount()` is a raw aggregate that
does not materialize entities and is **not** subject to LOAD privacy.

### Write operations -- deny-list

CREATE, UPDATE, and DELETE rules use **deny-list** semantics: if every
rule returns `Continue`, the operation is **allowed**. A rule must
explicitly `Deny` to block the operation.

```kotlin
create(
    // Block anonymous users
    PrivacyRule { ctx ->
        if (ctx.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("login required")
        else PrivacyDecision.Continue
    },
)
```

Write privacy is enforced before the database call. If denied, a
`PrivacyDeniedException` is thrown and no mutation occurs.

## Operation Contexts

Each operation's rules receive a typed context:

### LoadPrivacyContext

```kotlin
data class UserLoadPrivacyContext(
    val privacy: PrivacyContext,
    val client: EntClient,
    val entity: User,       // the entity being loaded
)
```

### CreatePrivacyContext

```kotlin
data class UserCreatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntClient,
    val candidate: UserWriteCandidate,  // the values being written
)
```

### UpdatePrivacyContext

```kotlin
data class UserUpdatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntClient,
    val before: User,                   // current state of the entity
    val candidate: UserWriteCandidate,  // the values after update
)
```

### DeletePrivacyContext

```kotlin
data class UserDeletePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntClient,
    val entity: User,                   // the entity being deleted
    val candidate: UserWriteCandidate,  // snapshot of its writable fields
)
```

### WriteCandidate

`WriteCandidate` is a data class containing all non-ID fields and edge
FK fields. It provides a uniform view of the data being written,
regardless of the operation type:

```kotlin
data class UserWriteCandidate(
    val name: String,
    val email: String,
    val age: Int?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

## Rule Derivation

When write rules are the same across operations, use derivation to
avoid duplication:

```kotlin
privacy {
    create(/* rules */)
    updateDerivesFromCreate()  // reuse create rules for update
    deleteDerivesFromCreate()  // reuse create rules for delete
}
```

When derivation is active, the operation's own rules are evaluated
first. If all return `Continue`, the create rules are evaluated as a
fallback (using a `CreatePrivacyContext` built from the candidate).

## Scoped Context

### withPrivacyContext

Override the privacy context for a block of code:

```kotlin
client.withPrivacyContext(PrivacyContext(Viewer.System)) { systemClient ->
    // All operations through systemClient use Viewer.System
    systemClient.users.query().all()
}
```

This creates a scoped client that inherits hooks and privacy rules but
uses the provided context.

### Transactions

Privacy context and rules are automatically inherited by transaction
clients:

```kotlin
client.withTransaction { tx ->
    // tx has the same privacy context provider and rules as client
    tx.users.create { name = "Alice"; email = "a@b.com" }.save()
}
```

## Internal Bypass

Some operations need to bypass LOAD privacy internally:

- `deleteById(id)` fetches the entity via the driver directly
  (bypassing LOAD privacy) then delegates to `delete(entity)` which
  enforces DELETE privacy.
- `deleteMany(predicates)` queries the driver directly without LOAD
  filtering, hydrates entities, then calls `delete(entity)` per row
  for DELETE privacy enforcement.

Raw entity-loading bypasses are generated inside repo internals only
and are not exposed to application code. The one public aggregate
escape hatch is `rawCount()`, which returns a row count without
materializing or privacy-checking entities.

## Limitations

Privacy V1 intentionally keeps enforcement synchronous and row-by-row.
See [Privacy Limitations](privacy-limitations.md) for aggregate read,
filtering, pagination, bulk operation, and upsert limitations.

## Error Handling

When privacy is denied, a `PrivacyDeniedException` is thrown:

```kotlin
class PrivacyDeniedException(
    val entity: String,        // e.g. "User"
    val operation: PrivacyOperation,  // LOAD, CREATE, UPDATE, DELETE
    val reason: String,
) : RuntimeException("$operation denied on $entity: $reason")
```

All read operations (`all()`, `firstOrNull()`, `byId()`) and all write
operations (`create`, `update`, `delete`) throw on denial. This strict
read model ensures that unreadable entities never silently disappear
from results — callers must handle `PrivacyDeniedException` or ensure
their queries only match entities the viewer is allowed to see.

## What Gets Generated

For each schema with a policy registered, the codegen emits:

| Generated type | Purpose |
|----------------|---------|
| `{Entity}WriteCandidate` | Snapshot of writable fields for write rules |
| `{Entity}LoadPrivacyContext` | Context for LOAD rules |
| `{Entity}CreatePrivacyContext` | Context for CREATE rules |
| `{Entity}UpdatePrivacyContext` | Context for UPDATE rules |
| `{Entity}DeletePrivacyContext` | Context for DELETE rules |
| `{Entity}PrivacyConfig` | Internal storage for rule lists |
| `{Entity}PrivacyScope` | DSL scope inside `privacy { }` |
| `{Entity}PolicyScope` | Outer scope for `EntityPolicy.configure` (exposes `privacy {}` and `validation {}`) |
| `{Entity}{Op}PrivacyRule` | Typealiases for rule types |
