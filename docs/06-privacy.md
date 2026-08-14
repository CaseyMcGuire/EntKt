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

LOAD privacy is enforced on every read terminal that materializes
entities. Denial is never thrown from the terminal — it is the read's
result:

- `repo.findById(id)` -- `Failed(EntPrivacyDeniedException(Root, ...))`
  when the row exists but is denied; `Success(null)` only for
  authoritative absence
- `query.all()` -- `Failed(EntPrivacyDeniedException(Root, ...))` if any
  entity in the selected window is denied, with one keyed
  `PrivacyDenial` per denied row; never a partial list
- `query.firstOrNull()` -- `Failed(EntPrivacyDeniedException(Root, ...))`
  if the fetched row is denied; `Success(null)` only when no matching
  row exists
- Eager-loaded edges (`withPosts()`, etc.) --
  `Failed(EntPrivacyDeniedException(EagerEdge(path), ...))` if any
  eagerly loaded entity is denied, unless that edge opts into
  `filterVisible()` (see
  [Queries → Eager Privacy](04-queries.md#eager-privacy-and-filtervisible))

`.getOrThrow()` throws the stored exception; `.visibleOrNull()` maps a
singular *root* denial to `Success(null)` for explicit
privacy-as-absence handling. `rawCount()` / `rawExists()` and the raw
aggregates do not materialize entities and are **not** subject to LOAD
privacy.

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

Write privacy is enforced before the database call. If denied, the save
returns `MutationResult.Failed(EntMutationPrivacyDeniedException)` with
`writeState = NotPersisted` — no mutation occurs.

## Operation Contexts

Each operation's rules receive a typed context. The `client` is an
`EntPrivacyReadClient`: a read-only client fixed to the **caller's**
privacy context — rules can query the graph to decide (ownership
walks, parent-visibility checks), and every row those reads
**materialize** passes the viewer's LOAD privacy: a rule cannot load
what its viewer cannot load. Writes, transactions, re-scoping
(`withPrivacyContext` / `bypassPrivacy_DANGEROUS`), and configuration
do not exist on the type, so a rule that tries to mutate does not
compile:

Rule reads evaluate LOAD privacy like any other read: a rule loading a
row its viewer cannot see gets the denial
(`findById` returns `Failed(EntPrivacyDeniedException)`; chaining
`.visibleOrNull()` collapses that root denial to `Success(null)`),
never the row. This is deliberately the opposite posture from
validation contexts, whose `EntValidationReadClient` reads are
privacy-bypass-scoped — invariant checks must see all rows,
authorization checks must not. Both concrete types implement the
shared `EntReadClient` interface, so a helper that works correctly
under either posture can accept `EntReadClient`; a helper that is
specifically part of an authorization decision should accept
`EntPrivacyReadClient` and then cannot be handed a privacy-bypassing
reader by mistake.

The raw terminals (`rawCount` / `rawExists` and the raw aggregates)
skip LOAD privacy by design, which would break that guarantee — so on
`EntPrivacyReadClient` they return
`ReadResult.Failed(IllegalStateException)` instead of silently probing
rows the viewer cannot see. They remain available everywhere else
(application queries, validation rules), where their privacy posture
is deliberate. Use a LOAD-checked terminal inside privacy rules; a
posture-agnostic helper accepting `EntReadClient` must likewise avoid
raw terminals, since it may run under either posture.

LOAD privacy applies to returned entities, not related entities used only to
filter a query. That holds for both application queries and rule reads: a rule
that filters through `has { }` can be influenced by a related row its viewer
could not load directly. When that matters, load the related row explicitly
with `findById` or `firstOrNull` so its LOAD policy runs. See
[Privacy Limitations → Predicate-Based Inference](08-privacy-limitations.md#predicate-based-inference).

### LoadPrivacyContext

```kotlin
data class UserLoadPrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
    val entity: User,       // the entity being loaded
)
```

### CreatePrivacyContext

```kotlin
data class UserCreatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
    val candidate: UserWriteCandidate,  // the values being written
)
```

### UpdatePrivacyContext

```kotlin
data class UserUpdatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
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
has already run, so a dirty + null required field would have failed the
save with `MutationResult.Failed(EntValidationException)` before privacy
fires. Rules can treat `FieldPatch.Set(value)` for required fields as
having a non-null value and `FieldPatch.Unset` as "not in this update".

### DeletePrivacyContext

```kotlin
data class UserDeletePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntPrivacyReadClient,
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
    bypassed.users.create { email = "admin@example.com" }.save().getOrThrow()
}
```

It bypasses only generated privacy checks (LOAD/CREATE/UPDATE/DELETE) — validation,
hooks, query interceptors, transactions, and database constraints still apply.

### withPrivacyContext

Override the privacy context for a block of code — use this for ordinary identity
changes (e.g. acting as `Viewer.User(id)`), not for bypassing privacy:

```kotlin
client.withPrivacyContext(PrivacyContext(Viewer.User(42L))) { scoped ->
    scoped.users.query().all().getOrThrow()
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
    tx.users.create { name = "Alice"; email = "a@b.com" }.save().orRollback()
}
```

## Delete Privacy Behavior

Delete operations enforce DELETE privacy independently of LOAD privacy:

- `deleteById(id)` may delete an entity the viewer cannot load, but only
  when its DELETE rules allow the operation.
- `deleteMany(predicates)` evaluates DELETE privacy for every matching
  entity. The operation is atomic: a denial anywhere fails the whole
  call with `Failed(EntMutationPrivacyDeniedException)` and — because
  the batch runs in one transaction — leaves no committed subset. No
  denied candidate is silently skipped.

For aggregate reads, `rawCount()` deliberately skips LOAD privacy.
There is no privacy-filtered count terminal — a viewer-scoped count is
a strict `all()` over predicates that only match visible rows.

## Limitations

Privacy V1 intentionally keeps enforcement synchronous and row-by-row.
See [Privacy Limitations](08-privacy-limitations.md) for aggregate read,
filtering, pagination, and bulk operation limitations.

## Error Handling

Denial is a typed exception carried by the operation's result, not a
throw from the terminal. A denied **read** is
`ReadResult.Failed(EntPrivacyDeniedException)`:

```kotlin
class EntPrivacyDeniedException(
    val origin: LoadDenialOrigin,       // Root, or EagerEdge(path) for a denied eager target
    val denials: List<PrivacyDenial>,   // non-empty; one entry per denied row, in query order
) : EntException(...), EntPrivacyFailure

data class PrivacyDenial(
    val entityType: String,   // e.g. "User"
    val entityKey: EntityKey, // the row's id field + value — no hydrated fields
    val reason: String,       // the rule-supplied reason
)
```

A denied **write** — whether the mutation itself is rejected pre-write
or `saveAndLoad()` cannot disclose the returned entity — is
`MutationResult.Failed(EntMutationPrivacyDeniedException)`. Its
`operation` names the privacy decision that denied (`CREATE`, `UPDATE`,
`DELETE`, or `LOAD` for returned-entity disclosure) and its
`writeState` independently records the database effect: pre-write
rejection is always `NotPersisted`, while a disclosure denial after a
successful write reports the real state (possibly `Committed` — the
write is not rolled back because its result could not be shown).

Both exception types implement the sealed `EntPrivacyFailure` marker.
Trusted application boundaries can therefore apply one non-disclosure
policy with `exception is EntPrivacyFailure` while retaining each
exception's read- or mutation-specific payload. The marker means an
EntKt privacy rule returned a denial decision; exceptions thrown by
privacy-rule application code remain unexpected operational failures
and do not implement it.

All read operations (`all()`, `firstOrNull()`, `findById()`) and all
write operations (`create`, `update`, `delete`) surface denial this
way. The strict read model ensures unreadable entities never silently
disappear from results — callers handle the `Failed` state explicitly
(exhaustive `when`, or `.getOrThrow()` to rethrow), opt into
privacy-as-absence for singular reads with `.visibleOrNull()`, or
ensure their queries only match entities the viewer is allowed to see.

The denial payloads — entity keys and rule-supplied reasons — are
trusted diagnostic data. A `Failed` denial proves more than
`Success(null)` (some selected row existed), so application boundaries
must not pass that distinction, the keys, or the reasons to untrusted
clients without an explicit mapping.

## Generated Privacy API

For each schema with a policy, entkt provides:

| Public type | Purpose |
|-------------|---------|
| `{Entity}WriteCandidate` | Snapshot of writable fields for write rules |
| `{Entity}LoadPrivacyContext` | Context for LOAD rules |
| `{Entity}CreatePrivacyContext` | Context for CREATE rules |
| `{Entity}UpdatePrivacyContext` | Context for UPDATE rules |
| `{Entity}DeletePrivacyContext` | Context for DELETE rules |
| `{Entity}PrivacyScope` | DSL scope inside `privacy { }` |
| `{Entity}PolicyScope` | Outer scope for `EntityPolicy.configure` (exposes `privacy {}` and `validation {}`) |
| `{Entity}{Op}PrivacyRule` | Typealiases for rule types |
| `EntPrivacyReadClient` | Read client in privacy contexts — viewer-scoped reads (schema-set-level) |
| `EntReadClient` | Shared read-only interface both posture clients implement (schema-set-level) |
