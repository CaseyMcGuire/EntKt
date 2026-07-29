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
                    val v = ctx.privacy.userOrNull()
                        ?: return@PrivacyRule PrivacyDecision.Continue
                    if (v.id == ctx.entity.id) PrivacyDecision.Allow
                    else PrivacyDecision.Continue
                },
            )
            create(
                PrivacyRule { ctx ->
                    // Privacy is fail-closed, so authenticated callers must be
                    // explicitly allowed — a fallthrough Continue would deny.
                    if (ctx.privacy.viewer is Viewer.Anonymous)
                        PrivacyDecision.Deny("only system can create users")
                    else PrivacyDecision.Allow
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
    data class PrivacyBypass(val reason: String) : Viewer
}
```

`Viewer.User.id` is `Any` because apps use different ID types. Use the
runtime helpers instead of handwritten casts:

```kotlin
ctx.privacy.userOrNull()      // Viewer.User?
ctx.privacy.userIdOrNull()    // Any?
ctx.privacy.longIdOrNull()    // Long?
ctx.privacy.intIdOrNull()     // Int?
ctx.privacy.stringIdOrNull()  // String?
ctx.privacy.uuidIdOrNull()    // UUID?
```

Typed helpers are exact type checks. For example, `longIdOrNull()` returns
`null` for an `Int` id; it does not coerce numeric values.

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

**Stock rule — `allowAll`.** The runtime ships `allowAll`, a rule that
permits any operation on any entity. Because `PrivacyRule` is contravariant
in its context, the single value works in every slot on every schema, so a
public or trusted entity doesn't need its own allow-everything rule:

```kotlin
import entkt.runtime.allowAll

privacy {
    load(allowAll)     // anyone can read
    create(allowAll)   // anyone can create
}
```

Under fail-closed privacy this is the explicit opt-in to "no restriction"
for an operation — use it deliberately.

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
[validation](07-validation.md). Implement `EntityPolicy` and register it
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

Privacy is **fail-closed**. Every CRUD operation -- LOAD, CREATE, UPDATE,
DELETE -- requires an explicit `Allow` to proceed. Rules are evaluated in
order and the first non-`Continue` decision wins:

- an explicit `Allow` permits the operation,
- an explicit `Deny(reason)` rejects it,
- if every rule returns `Continue` -- **or the operation has no rules, or
  no policy is registered at all** -- the operation is **denied**.

This is allow-list semantics for all four operations: absent a matching
`Allow`, access is refused. An entity with no policy is fully locked down;
you opt into access explicitly.

```kotlin
load(
    // Users can see their own profile
    PrivacyRule { ctx ->
        val v = ctx.privacy.userOrNull() ?: return@PrivacyRule PrivacyDecision.Continue
        if (v.id == ctx.entity.id) PrivacyDecision.Allow
        else PrivacyDecision.Continue
    },
    // Fallthrough: denied (implicit)
)
```

> **`Viewer.PrivacyBypass(reason)` bypasses all privacy checks** at the framework
> level -- it is the escape hatch for trusted/internal operations (the required
> `reason` says why). You do not need (and cannot write) a rule for it. At
> application call sites prefer the generated `bypassPrivacy_DANGEROUS(reason)`
> client helper (below), whose loud name makes bypasses obvious in review.

LOAD privacy is enforced on:

- `repo.byId(id)` -- throws `PrivacyDeniedException`
- `query.allOrThrow()` -- throws `PrivacyDeniedException` if any entity is denied
- `query.firstOrNull()` -- throws `PrivacyDeniedException` if the entity is denied; returns `null` only when no matching row exists
- Eager-loaded edges (`withPosts()`, etc.) -- throws `PrivacyDeniedException` if any eagerly loaded entity is denied

`exists()` fetches one row and evaluates LOAD privacy on it, throwing
if denied. `visibleCount()` materializes matching rows, evaluates LOAD
privacy on each, and returns the count of allowed entities (denied
entities are silently excluded). `rawCount()` is a raw aggregate that
does not materialize entities and is **not** subject to LOAD privacy.

### Write operations (CREATE, UPDATE, DELETE)

Writes follow the same fail-closed rule as LOAD: a CREATE, UPDATE, or
DELETE proceeds only if a rule explicitly `Allow`s it. A common shape
denies one class of viewer and explicitly allows the rest:

```kotlin
create(
    PrivacyRule { ctx ->
        if (ctx.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("login required")
        else PrivacyDecision.Allow   // explicit Allow — a Continue here would deny
    },
)
```

Write privacy is enforced before the database call. If denied, a
`PrivacyDeniedException` is thrown and no mutation occurs.

## Operation Contexts

Each operation's rules receive a typed context. The `client` is a
read-only `EntReadClient`, fixed to the **caller's** privacy context —
rules can query the graph to decide (ownership walks,
parent-visibility checks), and every row those reads **materialize**
passes the viewer's LOAD privacy: a rule cannot load what its viewer
cannot load. Writes, transactions, re-scoping (`withPrivacyContext` /
`bypassPrivacy_DANGEROUS`), and configuration do not exist on the
type, so a rule that tries to mutate does not compile:

```kotlin
// Generated evaluator wires the viewer-scoped read-only client
val privacyClient = client.asReadClientForInternalUse(privacy)
val ctx = UserLoadPrivacyContext(privacy, privacyClient, entity)
```

Rule reads evaluate LOAD privacy like any other read: a rule loading a
row its viewer cannot see gets the denial (`byIdOrNull` throws
`PrivacyDeniedException`; `visibleByIdOrNull` collapses it to `null`),
never the row. This is deliberately the opposite posture from
validation contexts, whose reads are privacy-bypass-scoped — invariant
checks must see all rows, authorization checks must not.

The raw terminals (`rawCount` / `rawExists` and the raw aggregates)
skip LOAD privacy by design, which would break that guarantee — so on
viewer-scoped rule readers they throw `IllegalStateException` instead
of silently probing rows the viewer cannot see. They remain available
everywhere else (application queries, validation rules), where their
privacy posture is deliberate. Use a LOAD-checked terminal inside
privacy rules.

One caveat is inherited from the entity-level privacy model rather
than introduced by the read client: LOAD privacy is evaluated on
**materialized** rows only. Rows a query references purely
structurally — matched inside an `Edge.has { ... }` predicate (an
`EXISTS` subquery) or folded in as the source side of a `queryX()`
traversal — are never loaded as entities and therefore never
LOAD-checked. That holds identically for application queries and rule
reads: a rule that keys a decision on a *related* row's attributes
through `has { }` can be influenced by rows its viewer could not load
directly. When that matters, materialize the related row explicitly
(`byIdOrNull` / `firstOrNull` on the related repo) so it passes its
own LOAD check. See
[Privacy Limitations → Predicate-Based Inference](08-privacy-limitations.md#predicate-based-inference).

### LoadPrivacyContext

```kotlin
data class UserLoadPrivacyContext(
    val privacy: PrivacyContext,
    val client: EntReadClient,
    val entity: User,       // the entity being loaded
)
```

### CreatePrivacyContext

```kotlin
data class UserCreatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntReadClient,
    val candidate: UserWriteCandidate,  // the values being written
)
```

### UpdatePrivacyContext

```kotlin
data class UserUpdatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntReadClient,
    val before: User,                   // current state of the entity (loaded by save())
    val requestedPatch: UserUpdatePatch, // caller/hook intent — FieldPatch entries
    val effectivePatch: UserUpdatePatch, // after framework update defaults (e.g. updatedAt)
    val candidate: UserWriteCandidate,  // full after-state = before + effectivePatch
    val edgeChanges: UserEdgeChangesView, // per-edge intent + computed delta
)
```

`requestedPatch` and `effectivePatch` carry per-field `FieldPatch<T>` entries
(`Unset` or `Set(value)`). Use them when a rule needs to know *what changed*;
use `candidate` for the *full after-state* including unchanged fields. Compare
`requestedPatch` vs `effectivePatch` to distinguish caller intent from
framework-added defaults.

`edgeChanges` is a per-entity aggregator with one `EdgeChanges<TargetIdType>`
field per helper-eligible `throughLink` M2M edge on the schema. Each carries
`requestedSet?` / `requestedAdds` / `requestedRemoves` (caller intent —
deduplicated; `requestedAdds` and `requestedRemoves` are disjoint by
construction since the mutator rejects same-id mixed-direction at the
call site) and the computed database delta `added` / `removed` (after
diffing intent against the current junction rows). Schemas without
helper-eligible M2M edges still get an empty `${Entity}EdgeChangesView`
so the context shape is uniform. Rule patterns:

- *Authorize the database effect:* read `edgeChanges.tags.added` and
  `edgeChanges.tags.removed` — the actual junction row inserts and deletes
  that will fire after this rule allows.
- *Reject intent regardless of effect:* read `edgeChanges.tags.requestedRemoves`
  — a `remove(unknownId)` shows up here even though `removed` may be empty.

See [Edges → Link-table M2M mutators](03-edges.md#link-table-m2m-mutators)
for the mutator API and [Hooks → The Update Hook Context](05-hooks.md#the-update-hook-context)
for `ctx.pendingEdges` (the before-hook intent surface that the
`edgeChanges` delta is computed from).

By the time rules see the patches, the post-hook required-not-null check
has already run, so a dirty + null required field would have thrown
`IllegalStateException` before privacy fires. Rules can treat
`FieldPatch.Set(value)` for required fields as having a non-null value
and `FieldPatch.Unset` as "not in this update".

### DeletePrivacyContext

```kotlin
data class UserDeletePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntReadClient,
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
fallback (using a `CreatePrivacyContext` built from the candidate). If
the create rules also fail to `Allow`, the operation is denied
(fail-closed).

## Scoped Context

### bypassPrivacy_DANGEROUS

Run a block with privacy checks bypassed. The loud name + required `reason` make
escape-hatch call sites obvious and easy to grep for:

```kotlin
client.bypassPrivacy_DANGEROUS(reason = "migration backfill") { bypassed ->
    bypassed.users.create { email = "admin@example.com" }.saveOrThrow()
}
```

It bypasses only generated privacy checks (LOAD/CREATE/UPDATE/DELETE) — validation,
hooks, query interceptors, transactions, and database constraints still apply.

### withPrivacyContext

Override the privacy context for a block of code — use this for ordinary identity
changes (e.g. acting as `Viewer.User(id)`), not for bypassing privacy:

```kotlin
client.withPrivacyContext(PrivacyContext(Viewer.User(42L))) { scoped ->
    scoped.users.query().allOrThrow()
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
See [Privacy Limitations](08-privacy-limitations.md) for aggregate read,
filtering, pagination, and bulk operation limitations.

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
