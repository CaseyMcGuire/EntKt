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
                PrivacyRule { context, item ->
                    val v = context.privacy.userOrNull()
                        ?: return@PrivacyRule PrivacyDecision.Continue
                    if (v.id == item.entity.id) PrivacyDecision.Allow
                    else PrivacyDecision.Continue
                },
            )
            create(
                PrivacyRule { context, _ ->
                    // Privacy is fail-closed, so authenticated callers must be
                    // explicitly allowed — a fallthrough Continue would deny.
                    if (context.privacy.viewer is Viewer.Anonymous)
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
context.privacy.userOrNull()      // Viewer.User?
context.privacy.userIdOrNull()    // Any?
context.privacy.longIdOrNull()    // Long?
context.privacy.intIdOrNull()     // Int?
context.privacy.stringIdOrNull()  // String?
context.privacy.uuidIdOrNull()    // UUID?
```

Typed helpers are exact type checks. For example, `longIdOrNull()` returns
`null` for an `Int` id; it does not coerce numeric values.

### PrivacyContext

`PrivacyContext` bundles the viewer captured for a generated operation.
One logical operation captures at most one context and shares that exact value
across every privacy-consuming phase. For a read, that includes interceptors,
traversal and eager subqueries, and root/eager LOAD checks. For `createMany`,
CREATE privacy and returned LOAD privacy share one context; `deleteMany`
shares one across candidate interceptors and DELETE privacy. An empty operation
or a failure before the first privacy-consuming phase need not call the
provider.

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

A rule receives phase-shared context separately from one generated item.
It is also a batch rule: the default adapter visits items serially in encounter
order.

```kotlin
class PrivacyRuleContext<out Client>(
    val privacy: PrivacyContext,
    val client: Client,
)

fun interface PrivacyRule<in Client, in Item> :
    BatchPrivacyRule<Client, Item> {

    fun run(
        context: PrivacyRuleContext<Client>,
        item: Item,
    ): PrivacyDecision

    override fun runBatch(
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision> =
        batch.decideEach { run(context, it) }
}
```

Each operation gets its own item type (see [Operation Items](#operation-items)
below), so rules are type-safe for the operation they guard. The same item type
is used by scalar and batch rules.

### BatchPrivacyRule

Use an explicit batch rule when one callback should inspect all items or
perform one set-based lookup:

```kotlin
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions

interface BatchPrivacyRule<in Client, in Item> {
    fun runBatch(
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision>
}

fun <Client, Item> batchPrivacyRule(
    block: (
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ) -> RuleDecisions<PrivacyDecision>,
): BatchPrivacyRule<Client, Item>

val allowReadablePosts: PostLoadBatchPrivacyRule =
    batchPrivacyRule { context, batch ->
        val readableIds = loadReadablePostIds(
            client = context.client,
            viewer = context.privacy.viewer,
            postIds = batch.map { it.entity.id },
        )
        batch.decideEach { item ->
            if (item.entity.id in readableIds) PrivacyDecision.Allow
            else PrivacyDecision.Deny("post is not visible")
        }
    }

privacy {
    load(allowReadablePosts)
}
```

`RuleBatch` is an immutable, read-only `List`, so a rule can inspect, group, or
sort its items while preparing a set-based read. It must build its result with
`batch.decideEach { ... }` or
`batch.decideEachIndexed { index, item -> ... }`.
Those methods return read-only `RuleDecisions` tied to that exact batch and
invoke the decision block in encounter order. This works for ID-less CREATE
items and preserves distinct decisions for duplicate or equal items;
`decideEachIndexed` exposes a stable index within the current callback batch when
the item value alone is not a sufficient key. Later privacy rules receive
only still-unresolved items, so that index is not operation-global.

This removes the error-prone API that accepted an arbitrary positional list;
it does not prove that application code computed the semantically correct
decision. Use the item supplied to the `decideEach` block rather than consuming
a separately reordered decision iterator. `RuleDecisions` is readable as a
`List`, and `RuleBatch.from(items)` creates a copied batch for direct decision
tests. A complete generated-rule test must also supply the matching shared
context and read client. A rule decorator must transform delegated decisions with
`result.mapDecisions { ... }`; that operation preserves the delegated result's
batch identity, so a stale cached result remains rejectable. Do not copy a
delegated decision list back through the current batch. Decisions from a test
batch remain bound to it.

Returning decisions created by another batch is an operational
`EntBatchRuleContractException`, not a denial. Java or unchecked code that
returns `null` instead of `RuleDecisions`, or returns a null/invalid decision,
receives the same contract error. Scalar and batch rules register
under the existing `load`, `create`, `update`, and `delete` names in Kotlin and
share one registration order. Generated batch overloads use JVM names such as
`loadBatchRule` and `createBatchRule` so Java lambdas remain unambiguous. A
batch rule receives a singleton `RuleBatch` for a scalar operation and is not
invoked for an empty phase.

**Stock rule — `allowAll`.** The runtime ships `allowAll`, a rule that
permits any operation on any entity. Because `PrivacyRule` is contravariant
in its client and item types, the single value works in every slot on every
schema, so a public or trusted entity doesn't need its own allow-everything
rule:

```kotlin
import entkt.runtime.privacy.allowAll

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
four operations: `load()`, `create()`, `update()`, `delete()`. Each keeps its
scalar-rule `vararg` overload and also accepts one `BatchPrivacyRule`. Register
multiple batch rules with repeated calls; there are no parallel `loadBatch` or
`createBatch` methods in Kotlin. Java calls the explicitly named batch JVM
overloads such as `loadBatchRule` and `createBatchRule`.

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
    PrivacyRule { context, item ->
        val v = context.privacy.userOrNull()
            ?: return@PrivacyRule PrivacyDecision.Continue
        if (v.id == item.entity.id) PrivacyDecision.Allow
        else PrivacyDecision.Continue
    },
    // Fallthrough: denied (implicit)
)
```

For a multi-item phase, evaluation is rule-major. The first rule receives all
active items in encounter order. Items it allows or denies are finalized; only
items that return `Continue` reach the next rule. Thus a later rule never sees
an item whose outcome is already known, and unresolved items after the final
rule are denied fail-closed. For each item, the first non-`Continue` decision
still wins. A scalar rule uses its adapter to run once per active item, while an
explicit batch rule is invoked once with that active list. Rules are not run
concurrently.

This ordering means an exception from a later item in an earlier registered
rule can surface before a denial that an earlier item would have received from
a later rule. Rules should not rely on cross-item side-effect ordering.

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
- Eager-loaded edges (`loadPosts()`, etc.) --
  `Failed(EntPrivacyDeniedException(SelectedEdgePath(steps), ...))` if any
  eagerly loaded entity is denied, unless that edge opts into
  `filterVisible()` (see
  [Queries → Eager Privacy](04-queries.md#eager-privacy-and-filtervisible))

Collection terminals pass the ordered materialized root list to LOAD rules as
one batch. Each eager query does the same for its ordered, deduplicated targets
that remain in at least one parent's requested window, in effective target
order (the caller's ordering plus the framework's primary-key tie-breaker).
Strict loading projects the first eager denial after that batch evaluation;
`filterVisible()` removes every denied target from the relevant parent groups.
Nested eager loads repeat this contract once per logical edge step: the nested
batch holds the ordered distinct union of every parent group's retained
targets, never one batch per group.

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
    PrivacyRule { context, _ ->
        if (context.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("login required")
        else PrivacyDecision.Allow   // explicit Allow — a Continue here would deny
    },
)
```

Write privacy is enforced before the database call. If denied, the save
returns `MutationResult.Failed(EntMutationPrivacyDeniedException)` with
`writeState = NotPersisted` — no mutation occurs.

## Operation Contexts

Every privacy rule receives one shared `PrivacyRuleContext` parameter in
addition to its item or item batch. Its `client` is an
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
validation rule contexts, whose `EntValidationReadClient` reads are
privacy-bypass-scoped — invariant checks can materialize every row,
while authorization reads materialize only viewer-visible rows. Both
concrete types implement the shared `EntReadClient` interface, so a
helper that works correctly under either posture can accept
`EntReadClient`; a helper that is
specifically part of an authorization decision should accept
`EntPrivacyReadClient` and then cannot be handed a privacy-bypassing
reader by mistake.

The raw terminals (`rawCount` / `rawExists` and the raw aggregates) are
available on every read client and have one explicit meaning: they query
storage without materializing entities or evaluating LOAD privacy. They still
run read interceptors under the operation's captured privacy context. Privacy
rules are trusted authorization code and may use raw terminals for facts such
as ACL membership or existence; doing so can also avoid recursive LOAD-policy
evaluation. Use `findById`, `firstOrNull`, or `all` instead when the referenced
entity's visibility must participate in the decision. Raw results must not be
mistaken for proof that the viewer could load the matching entities.

LOAD privacy applies to returned entities, not related entities used only to
filter a query. That holds for both application queries and rule reads: a rule
that filters through `has { }` can be influenced by a related row its viewer
could not load directly. When that matters, load the related row explicitly
with `findById` or `firstOrNull` so its LOAD policy runs. See
[Privacy Limitations → Predicate-Based Inference](08-privacy-limitations.md#predicate-based-inference).

## Operation Items

Shared `privacy` and `client` values live on `PrivacyRuleContext`; generated
operation items contain only values that differ per entity or candidate. One
phase constructs one rule context and passes that exact instance to every
reached rule; each rule still receives fresh defensive item snapshots.

### LoadPrivacyItem

```kotlin
data class UserLoadPrivacyItem(
    val entity: User,       // the entity being loaded
)
```

### CreatePrivacyItem

```kotlin
data class UserCreatePrivacyItem(
    val candidate: UserWriteCandidate,  // the values being written
)
```

### UpdatePrivacyItem

```kotlin
data class UserUpdatePrivacyItem(
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
so the item shape is uniform. Rule patterns:

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

### DeletePrivacyItem

```kotlin
data class UserDeletePrivacyItem(
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

Each rule receives its own snapshot. Generated `bytes()` values are copied
directly, and typed JSON values are round-tripped through the driver's
configured JSON mapper, including values inside update patches. Mutating a
`ByteArray` or a mutable collection nested in JSON from one rule item cannot
change the pending database write or another rule's input.

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
fallback (using a `CreatePrivacyItem` built from the candidate). If
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
  entity as one rule-major batch. A denial anywhere fails the whole call with
  `Failed(EntMutationPrivacyDeniedException)` before the delete statement, so
  no denied candidate is silently skipped.

`deleteMany` captures its context before running candidate read interceptors,
then freezes the complete effective predicate list produced by the caller and
those interceptors. After DELETE privacy, validation, and `beforeDelete`, the
write combines the approved IDs with those same predicates. It does not rerun
interceptors. A row that stops matching before the write is not deleted or
counted; a row that starts matching after selection was never approved and is
also not deleted.

For aggregate reads, `rawCount()` deliberately skips LOAD privacy.
There is no privacy-filtered count terminal — a viewer-scoped count is
a strict `all()` over predicates that only match visible rows.

## Limitations

Privacy evaluation remains synchronous and does not rewrite arbitrary callback
queries. A scalar rule that performs one read per item still produces N reads;
use an explicit batch rule and a set-based query when that matters. See
[Privacy Limitations](08-privacy-limitations.md) for aggregate read, filtering,
and pagination limitations.

## Error Handling

Denial is a typed exception carried by the operation's result, not a
throw from the terminal. A denied **read** is
`ReadResult.Failed(EntPrivacyDeniedException)`:

```kotlin
class EntPrivacyDeniedException(
    val origin: LoadDenialOrigin,       // Root, or SelectedEdgePath(steps) for a denied target
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

## Application Boundary Handling

HTTP, GraphQL, RPC, and job-runner boundaries choose their own response
formats, but they must preserve EntKt's distinction between absence, denial,
operational failure, and an uncertain write outcome. In particular, do not
recursively unwrap EntKt exceptions before checking whether a transaction may
have committed.

### Singular reads: opt into nondisclosure

When a missing and an invisible entity should be indistinguishable, apply
`visibleOrNull()` before projecting the result:

```kotlin
fun findNote(id: Long): Note? =
    client.notes
        .findById(id)
        .visibleOrNull()
        .getOrThrow()
```

`visibleOrNull()` converts only
`Failed(EntPrivacyDeniedException(LoadDenialOrigin.Root, ...))` to
`Success(null)`. It does not swallow operational failures, ordinary exceptions
thrown by privacy-rule code, or eager-edge privacy failures. It performs no
additional query or privacy evaluation.

### Collections remain strict

`all()` returns `ReadResult<List<T>>`, so `visibleOrNull()` is deliberately not
available. If any root in the selected window is denied, the result is
`Failed(EntPrivacyDeniedException)` rather than a partial list. A caller that
maps such a failure to an empty list is discarding the entire selected window,
including any rows that were visible; that coarse policy must be explicit:

```kotlin
fun notesForUser(userId: Long): List<Note> {
    val result = client.notes.query {
        where(Note.userId eq userId)
    }.all()

    return when (result) {
        is ReadResult.Success -> result.value
        is ReadResult.Failed -> {
            val failure = result.exception
            if (
                failure is EntPrivacyDeniedException &&
                failure.origin is LoadDenialOrigin.Root
            ) {
                emptyList()
            } else {
                throw failure
            }
        }
    }
}
```

Most viewer-scoped collection endpoints should instead construct predicates
whose selected roots are all visible and let an unexpected denial fail loudly.
`filterVisible()` is a separate opt-in for one eagerly loaded edge; it does not
filter the roots returned by `all()`.

### Direct mutations: interpret `writeState`

A direct mutation failure remains ordinarily catchable:

```kotlin
try {
    client.notes.update(id) {
        title = newTitle
    }.saveAndLoad().getOrThrow()
} catch (e: EntMutationPrivacyDeniedException) {
    // Choose the boundary response using both the denial and e.writeState.
}
```

The exception type explains why the terminal failed; `writeState` explains
what EntKt knows about persistence:

| `MutationWriteState` | Boundary meaning |
|----------------------|------------------|
| `NotPersisted` | No write survived. It may be treated as an ordinary rejection, though retrying the same deterministic denial will not make it succeed. |
| `TransactionPending` | The mutation belonged to an enclosing transaction. Resolve that transaction before choosing the external response. |
| `Committed` | The write happened even though later work, such as returned-entity LOAD disclosure, failed. Do not present it as an unperformed write or blindly retry it. |
| `PersistenceUnknown` | EntKt cannot establish whether persistence happened. Reconcile or use a deliberately idempotent retry strategy. |

For example, `saveAndLoad()` can commit its write and then fail LOAD privacy for
the returned entity. Mapping every `EntMutationPrivacyDeniedException` to a
normal not-found response without considering `writeState` can cause a caller
to repeat a write that already happened.

`createMany()` always has this returned-disclosure phase because its success
value is the entity list. An EntKt-owned transaction attempts commit after a
returned-LOAD denial or ordinary rule failure; only a confirmed commit reports
`Committed`. If disclosure work aborts the database transaction, a confirmed
rollback reports `NotPersisted`, while an uncertain boundary reports
`PersistenceUnknown`. Inside a caller-owned transaction the failure is
`TransactionPending` and marks that transaction rollback-only. CREATE privacy
and returned LOAD privacy use the same context capture, but they remain
distinct authorization phases.

### Transactions: uncertainty is not a normal rejection

`TransactionResult.getOrThrow()` deliberately has two failure shapes:

```text
NotCommitted  -> rethrow the exact stored exception
OutcomeUnknown -> throw EntTransactionOutcomeUnknownException
```

A boundary should handle transaction uncertainty before ordinary typed
failures and must not unwrap it into validation, privacy, or not-found:

```kotlin
try {
    val note = client.withTransaction { tx ->
        val note = tx.notes.create {
            userId = currentUserId
            content = input.content
        }.saveAndLoad().orRollback()

        tx.noteAssets.create {
            noteId = note.id
            assetId = input.assetId
        }.save().orRollback()

        note
    }.getOrThrow()

    CreateNoteSuccess(note)
} catch (e: EntTransactionOutcomeUnknownException) {
    log.error("Create-note transaction outcome is unknown", e)
    UnexpectedError("The operation outcome could not be confirmed")
} catch (e: EntMutationPrivacyDeniedException) {
    NotFoundError("Not found")
} catch (e: EntValidationException) {
    ValidationError(e.violations.first().message)
} catch (e: NotFoundException) {
    NotFoundError(e.message ?: "Not found")
}
```

After confirmed rollback, the original exception is safe to classify for the
managed transaction. `EntTransactionOutcomeUnknownException`, by contrast,
means that transaction may have committed. Its `exception` and `cause` retain
the underlying failure for diagnostics, not normalization into an ordinary
client error. Do not blindly retry unless the complete operation—including
external side effects—is deliberately idempotent.

`NotCommitted` describes only the managed transaction. EntKt rejects use of
that transaction's captured root client before callbacks or database I/O, but
writes made through a different root driver, another database connection, or
an external service remain outside the guarantee and may already have
completed.

## Generated Privacy API

For each schema with a policy, entkt provides:

| Public type | Purpose |
|-------------|---------|
| `{Entity}WriteCandidate` | Snapshot of writable fields for write rules |
| `PrivacyRuleContext<Client>` | Shared captured privacy and viewer-scoped read client |
| `{Entity}LoadPrivacyItem` | Per-entity input for LOAD rules |
| `{Entity}CreatePrivacyItem` | Per-candidate input for CREATE rules |
| `{Entity}UpdatePrivacyItem` | Per-entity input for UPDATE rules |
| `{Entity}DeletePrivacyItem` | Per-entity input for DELETE rules |
| `{Entity}PrivacyScope` | DSL scope inside `privacy { }` |
| `{Entity}PolicyScope` | Outer scope for `EntityPolicy.configure` (exposes `privacy {}` and `validation {}`) |
| `{Entity}{Op}PrivacyRule` | Typealiases for rule types |
| `{Entity}{Op}BatchPrivacyRule` | Typealiases for explicit batch-rule types |
| `EntPrivacyReadClient` | Read client in `PrivacyRuleContext` — viewer-scoped reads (schema-set-level) |
| `EntReadClient` | Shared read-only interface both posture clients implement (schema-set-level) |
