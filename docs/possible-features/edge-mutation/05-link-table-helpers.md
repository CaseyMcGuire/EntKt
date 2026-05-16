# RFC: Link-Table M2M Mutation Helpers

## Status

**V1 landed in commits `a0483a8..b3646c5` (7 phases).** Generated
update builders for helper-eligible `throughLink` edges now expose
`add(id)` / `remove(id)` / `set(ids)`, with the full pipeline
(transaction + capability preflight, mixed-mode defense, three-way
owner-row read primitive choice, junction reads, EdgeChanges through
privacy / validation, junction writes, edge-only owner-UPDATE
suppression) wired in. User-facing docs for the new surface live in
[Edges → Link-table M2M mutators](../../03-edges.md#link-table-m2m-mutators)
and the per-context updates in
[Hooks](../../05-hooks.md#the-update-hook-context) /
[Privacy](../../06-privacy.md#updateprivacycontext) /
[Validation](../../07-validation.md#updatevalidationcontext).
End-to-end coverage is in
`integration-tests/src/test/kotlin/entkt/integrationtest/LinkTableM2MIntegrationTest.kt`
(17 scenarios against `LockSupportInMemoryDriver`).

Implementation decisions (the design questions called out in the
phase plan):

- **Decision A** — hybrid runtime types. Generic `PendingEdgeOps<ID>`
  and `EdgeChanges<ID>` in `entkt.runtime`, wrapped in generated
  per-entity `${Entity}PendingEdgeOps` / `${Entity}EdgeChangesView`
  aggregators. Empty aggregator class for schemas with zero
  helper-eligible M2M edges so the hook / privacy / validation
  context shape is uniform across all entities.
- **Decision B** — sidecar (B2). `EdgeChanges` is delivered through
  a distinct `edgeChanges` parameter on update privacy and
  validation contexts. The `WriteCandidate` stays scalar/FK-only,
  unchanged across create / update / delete pipelines.
- **Decision C** — mutator name follows the source edge. `tags` →
  `TagsEdgeMutator` (not `TagEdgeMutator`). Two M2M edges to the
  same target type on one source schema don't collide.
- **Decision D** — public nested class with `internal` constructor.
  `PostUpdate.TagsEdgeMutator` is reachable as
  `update { tags.add(...) }`, but the constructor is module-private.
- **Decision E** — generated `_hasPendingLinkTableM2MOps()` helper
  on the update class, ORing each mutator's `hasOps()` flag.
- **Decision F** — `LockSupportInMemoryDriver` extracted to
  `integration-tests/src/test/kotlin/entkt/integrationtest/support/`
  and shared between `UpdateConsistencyIntegrationTest` (RFC #4) and
  `LinkTableM2MIntegrationTest` (RFC #5).

Two notes on intent vs effect semantics that came up during
implementation:

- The mutator stores `_adds` and `_removes` as separate
  `MutableList<ID>` collections (not as a single interleaved op log).
  `EdgeChanges` cancellation is therefore **set-based**: same-id
  paired `add(x); remove(x)` cancels at the database layer
  regardless of call order, including the case where `x` was not
  previously linked. A strictly ordered op-log walk would distinguish
  `remove(x); add(x)` on an unlinked `x` as a net add, but the RFC's
  test list explicitly allows "potentially empty when operations
  cancel" for either ordering, so the set-based interpretation is
  conforming. Intent fields (`requestedAdds` / `requestedRemoves`)
  preserve the literal call log on both sides.
- The defense-in-depth mixed-mode check at save preflight catches
  state that bypassed the per-call mutator throw (e.g. reflection
  writing the op lists directly). It throws the same
  `IllegalStateException` shape as the per-call check, but only
  fires **after** the transaction and capability preflights — so a
  malformed save outside a transaction surfaces
  `TransactionRequiredException` first, not `IllegalStateException`.

Deferred to follow-ups (out of V1 scope):

- **Postgres concurrency test** against the real
  `pg_advisory_xact_lock` / `SELECT ... FOR UPDATE` primitives.
  Belongs alongside RFC #4's mixed-mode race coverage in
  `postgres/src/test/`.
- **Create-time M2M** (RFC §Update-Only V1). Owner id may not be
  known for `AUTO_*` strategies until after the insert; specifying
  the create-time write ordering for multi-write helpers is a
  separate design.
- **Target-side locking** (RFC §Open Questions). V1 declined; the
  endpoint-delete race on requested-present targets is handled by
  application-level cooperation (see the §Target Loading And
  Existence section).
- **Reverse traversal via `throughLinkInverse(...)`** (referenced
  by RFC #3). Separate RFC.

Split out from [Edge Mutation API](00-overview.md).

## Summary

Generate collection-style mutation helpers for helper-eligible `throughLink(...)`
many-to-many edges. The public API is **id-only**, mirroring the to-one
FK philosophy from [To-One FK Mutation And Nullability](02-to-one-assignment-nullability.md)
(`belongsTo` writes go through `authorId = alice.id`, not `author = alice`):

- `tags.add(tagId)`
- `tags.remove(tagId)`
- `tags.set(tagIds)`

`tagId` is typed as the target's id type (e.g., `UUID` for
`Tag` with `EntId.uuid()`, `Long` for `EntId.long()`), so calls are
compile-time-checked by id scalar type — a `tags.add(...)` call is
rejected only when the argument's scalar type differs from the
target's id scalar type. Ids are plain scalars (`Long`, `UUID`,
`Int`, `String`), not entity-specific wrappers, so an id from a
different entity with the same scalar type still compiles. The
helpers do not accept loaded `Tag` entities; callers with a target
entity in hand pass `tag.id` explicitly. This matches the
`authorId = alice.id` pattern from RFC #2 — relationship writes never
mix with loaded-entity state, and there's a single uniform write
surface across to-one and M2M.

This RFC covers helper API shape, normalization, hook-facing pending edge views,
privacy/validation candidate shape, target loading semantics, edge-only update
return state, and create-time deferral.

The schema marker and link-table eligibility rules live in
[Many-To-Many Schema Modeling](03-m2m-schema-modeling.md). Transaction
and locking requirements live in
[Transaction And Locking Semantics](04-transaction-locking-semantics.md).

## Proposed API

For link-table many-to-many edges, generate collection-style id-only
add/remove/set methods on the edge property:

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post.id) {
        tags.add(kotlinTagId)
    }.save()
}
```

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post.id) {
        tags.remove(oldTagId)
    }.save()
}
```

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post.id) {
        tags.set(listOf(kotlinTagId, ormTagId))
    }.save()
}
```

Callers that have a target entity in hand pass `tag.id` explicitly:

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post.id) {
        tags.add(kotlinTag.id)
        tags.remove(oldTag.id)
    }.save()
}
```

The id type is the target's id type (e.g., `UUID` for `Tag` with
`EntId.uuid()`, `Long` for `EntId.long()`). The compiler rejects
arguments whose scalar type differs from the target's id scalar type.
Because ids are plain scalars, not entity-specific wrappers, a `Post`
id with the same scalar type as `Tag`'s id still compiles —
type-checking is by id scalar type, not by source entity. No
entity-argument overloads exist; the only way to call `tags.add(...)`
is with a target id.

All generated link-table M2M helpers require a transaction-scoped client and use
the same owner-edge serialization discipline. `set(...)` is an exact
replacement: generated code serializes the owner-edge relationship before
reading or mutating junction rows. After the generated junction writes complete
inside that serialized section, the relationship set equals the requested set
**among generated M2M helpers**. Endpoint deletes are not part of that
discipline — they cascade through the junction FK (`OnDelete.CASCADE`, required
by [Many-To-Many Schema Modeling](03-m2m-schema-modeling.md)) and can interleave
between the helper's junction read and write, producing a final link set
smaller than the requested set. See "Target Loading And Existence" below for
the race and the available mitigations.

## Target Loading And Existence

Link-table M2M helpers operate on target ids only. `tags.add(...)`,
`tags.remove(...)`, and `tags.set(...)` never load target rows because
there is no target entity to load — only an id. Target LOAD privacy is
not evaluated just because a target id appears in a link-table M2M
mutation.

Target existence for inserted links is enforced by junction-table foreign-key
constraints unless a validation rule explicitly checks it earlier. Removals do
not prove target existence: `remove(nonexistentId)` may be a no-op if no
matching link exists. Applications that want unknown removal ids rejected can
add a validation rule that inspects `EdgeChanges.requestedRemoves` (the
caller's intent, present even when the id does not produce a database delete) —
see Candidate Shape.

The "never load target rows" choice has a concurrency consequence on `set(...)`
and `add(...)`. Because helpers do not lock target rows, an endpoint delete on a
requested-present target can fire between the helper's junction read and the
helper's junction write. The endpoint delete cascades through the junction FK
and silently removes the corresponding link row, and the helper — having
already computed the add/remove sets from the pre-delete junction read — does
not re-insert it, so the final link set is smaller than the requested set.
Owner-edge serialization (cooperative or true row lock, per
[Transaction And Locking Semantics](04-transaction-locking-semantics.md))
protects the owner row, not the target rows. Callers that need stronger
guarantees can serialize endpoint deletes against M2M saves themselves (for
example by locking the target row before deleting it through application code).
V1 does not add target-side locking inside the M2M helpers; see Open Questions.

## Generated Builder Shape

For link-table many-to-many edges, generated builders should expose an edge
mutator property:

```kotlin
class PostUpdate {
    val tags: TagEdgeMutator = TagEdgeMutator()
}

class TagEdgeMutator {
    fun add(id: UUID)
    fun remove(id: UUID)
    fun set(ids: List<UUID>)
}
```

Generated edge mutators are typed by the target's id type — `UUID`
for `Tag` declared with `EntId.uuid()`, `Long` for `EntId.long()`,
etc. The compiler rejects mismatched types, so `tags.add(post.id)`
where `Post.id` is `Long` and `Tag.id` is `UUID` is a compile error.
No entity-argument overloads are generated; the only signatures on
the mutator are id-typed.

`set(...)` is a replacement operation. In V1, replacement and delta
operations are mutually exclusive for a given edge within a single
mutation. A builder may call either `set(...)` or `add(...)` /
`remove(...)`, but not both for the same edge. Multiple `set(...)`
calls for the same edge are allowed; the latest replacement wins as
long as no delta operation is also present. Generated mutators must
reject mixed replacement/delta usage by throwing `IllegalStateException`
naming the edge and the conflicting operations. The check fires fail-fast
at the incompatible mutator call site, with a defense-in-depth check at
`save()` preflight that throws the same exception if state somehow
becomes mixed through reflection or a generated bulk-write helper that
bypasses the per-call check. The defense-in-depth check runs **after**
the transaction/capability preflight and **before** hooks, privacy,
validation, driver reads, or driver writes (per the Many-To-Many Pipeline
order in
[Transaction And Locking Semantics](04-transaction-locking-semantics.md)),
so a malformed save outside a transaction surfaces
`TransactionRequiredException` first, not `IllegalStateException`. Mixed
replacement/delta is a deterministic programming error, not a branchable
expected outcome — it throws on every path including `saveOrError()`,
and is not modeled as an `EntError` variant.

## Pending Edge Operations

Builders should preserve the ordered operation log internally. The public
candidate/context representation should expose the requested replacement, if
any, plus the computed database delta. It should not expose the raw operation
sequence.

Before hooks should receive hook-facing mutation interfaces, not the public
builder type that exposes link-table M2M mutators. Hook-facing interfaces should
continue to expose mutable scalar/FK fields, but they must not expose
`tags.add(...)`, `tags.remove(...)`, or `tags.set(...)`. They should
instead expose a read-only view of pending link-table edge operation intent.
Hooks may inspect which M2M edge operations were requested, but they must not
mutate pending edge operations.

Conceptually:

```kotlin
interface PostHookMutation {
    var title: String
    var authorId: UUID
    val pendingEdges: PostPendingEdgeOps
}

data class PendingEdgeOps<ID>(
    val requestedSet: Set<ID>? = null,
    val requestedAdds: Set<ID> = emptySet(),
    val requestedRemoves: Set<ID> = emptySet(),
) {
    val hasReplacement: Boolean get() = requestedSet != null
    val hasChanges: Boolean get() =
        requestedSet != null || requestedAdds.isNotEmpty() || requestedRemoves.isNotEmpty()
}

data class PostPendingEdgeOps(
    val tags: PendingEdgeOps<Long> = PendingEdgeOps(),
)
```

`PendingEdgeOps` is intent-level. It is computed from the builder's pending
operation log before current junction rows are read. `EdgeChanges`, described
below, is computed later after current junction rows are read and adds
computed `added` / `removed` delta sets. The two types share field names on
purpose: `PendingEdgeOps` is a strict subset of `EdgeChanges`' caller-intent
fields, so a rule that reads `requestedAdds` / `requestedRemoves` in a before
hook sees the same set names in privacy / validation later. `requestedAdds` /
`requestedRemoves` are intent names: they describe relationships the caller
wants present or absent, not database rows that are known to be inserted or
deleted. Actual computed database deltas are exposed only on `EdgeChanges.added`
and `EdgeChanges.removed`. Its public fields are sets because relationship
intent is unordered; hooks must not depend on iteration order.

For a given edge, `requestedSet` is mutually exclusive with `requestedAdds` /
`requestedRemoves` in `PendingEdgeOps` because V1 rejects mixed replacement and
delta operations. `requestedSet` is the deduplicated latest `set(...)`
operand. `requestedAdds` and `requestedRemoves` are used only for delta-only
mutations and contain deduplicated ids from `add(...)` and `remove(...)` calls.
Duplicate calls for the same id collapse, but same-id cancellations do **not**
remove from these sets — they are the literal call log (deduped), aligned with
`EdgeChanges.requestedAdds` / `requestedRemoves`.

## Normalization

Many-to-many mutations normalize by target id before writing:

- duplicate ids collapse to a single intended relationship
- `set(listOf(aId, aId))` is equivalent to `set(listOf(aId))`
- `add(aId)` twice is equivalent to `add(aId)` once
- `add(aId)` followed by `remove(aId)` has no net add
- `remove(aId)` followed by `add(aId)` has no net remove if `aId` was
  already linked, and results in a net add if `aId` was not linked
- removing an id that is not linked is a no-op
- generated writes should compute the final add/remove sets before touching the
  junction table instead of relying on uniqueness constraints or SQL execution
  order for correctness

## Candidate Shape

Many-to-many changes need an additional typed representation. Prefer exposing
ids rather than full target entities:

```kotlin
data class EdgeChanges<ID>(
    val requestedSet: Set<ID>? = null,
    val requestedAdds: Set<ID> = emptySet(),
    val requestedRemoves: Set<ID> = emptySet(),
    val added: Set<ID> = emptySet(),
    val removed: Set<ID> = emptySet(),
)
```

> **Behavior change vs. "operations cancel out".** The `requestedAdds` /
> `requestedRemoves` intent fields are the deduplicated literal call log;
> they are **not** the post-cancellation set. A caller that does
> `remove(aId); add(aId)` in one mutation surfaces `aId` in *both* intent
> sets, even though the normalized database effect is empty (`added` and
> `removed` are both empty in that case). This is deliberate: it lets a
> validator reject `remove(unknownId)` regardless of whether a paired
> `add(...)` cancels the net effect. Callers used to "operations cancel"
> semantics should read the computed `added` / `removed` fields if they
> only care about the database effect.

`EdgeChanges` exposes both the caller's request intent and the computed database
effect, mirroring the `requestedPatch` / `effectivePatch` split that
[ID-Based Update Roots](01-id-based-update-roots.md) defines for scalar/FK
updates:

- `requestedSet` is the deduplicated final intended relationship set from the
  latest `set(...)` operation. Present only in replacement mode.
- `requestedAdds` is the deduplicated set of ids the caller called `add(...)`
  on. Present only in delta mode (mutually exclusive with `requestedSet` per
  the replacement/delta rule). Duplicate calls for the same id collapse, but a
  matching cancelling `remove(...)` for the same id does **not** remove the id
  from this set.
- `requestedRemoves` is the deduplicated set of ids the caller called
  `remove(...)` on. Present only in delta mode. Duplicate calls collapse, but
  a matching cancelling `add(...)` for the same id does **not** remove the id
  from this set. A caller who does `remove(badId); add(badId)` still sees
  `badId` in `requestedRemoves`, so a validator that inspects requested intent
  fires regardless of whether the database effect cancels out.
- `added` contains ids that will be inserted into the link table after comparing
  the pending operations with current junction rows (the computed database
  effect).
- `removed` contains ids that will be deleted from the link table after
  comparing the pending operations with current junction rows (the computed
  database effect).

These fields are sets because relationship membership is unordered. Duplicates
are eliminated, and privacy/validation code must not depend on iteration order.

`EdgeChanges` is not the raw sequence of user calls, and it is distinct from
the `PendingEdgeOps` view exposed to before hooks. Privacy and validation rules
authorize the computed database effect in `added` / `removed`; the
`requestedSet`, `requestedAdds`, and `requestedRemoves` fields are available
when a rule needs to inspect intent independently of effect — for example, to
reject a `remove(nonexistentId)` call even though it produces no database
delete (`requestedRemoves` includes the id while `removed` does not).

For example, if the current link set is `[aId, cId]` and the caller runs
`tags.set(listOf(aId, bId))`, rules observe:

```kotlin
EdgeChanges(
    requestedSet = setOf(aId, bId),
    added = setOf(bId),
    removed = setOf(cId),
)
```

If the caller runs `tags.set(listOf(aId, bId))`, then `tags.add(cId)` in the
same mutation, the builder throws `IllegalStateException` from the
`tags.add(cId)` call site, before any observable work.

If the current link set is `[aId]` and the caller runs `tags.remove(aId)`
followed by `tags.add(aId)` in one mutation (the operations cancel), rules
observe:

```kotlin
EdgeChanges(
    requestedSet = null,
    requestedAdds = setOf(aId),
    requestedRemoves = setOf(aId),
    added = emptySet(),
    removed = emptySet(),
)
```

`aId` appears in both `requestedAdds` and `requestedRemoves` (the literal call
log captures both calls), but `added` and `removed` are empty because the
normalized database effect is a no-op. A validator inspecting either intent
field still sees the request and can reject (e.g.) a `remove(unknownId)` even
when a paired `add(unknownId)` cancels the net effect.

If the current link set is `[cId]` and the caller runs
`tags.add(aId); tags.remove(bId)` where `bId` is not currently linked, rules
observe:

```kotlin
EdgeChanges(
    requestedSet = null,
    requestedAdds = setOf(aId),
    requestedRemoves = setOf(bId),
    added = setOf(aId),
    removed = emptySet(),
)
```

Here `bId` appears in `requestedRemoves` but not in `removed`, because there
was no `(P, bId)` link row to delete. A validation rule that wants to reject
unknown removal ids inspects `requestedRemoves` (intent) rather than `removed`
(effect) — the use case from Target Loading And Existence.

A future `PostWriteCandidate` could include:

```kotlin
data class PostWriteCandidate(
    val title: String,
    val authorId: UUID,
    val tags: EdgeChanges<Long> = EdgeChanges(),
)
```

This gives privacy and validation rules enough information to reason about the
relationship replacement request and computed database effect without requiring
the target rows to be loaded.

## Privacy Scope

V1 should keep privacy and validation owner-centric:

- owner entity update privacy runs once with the scalar candidate and computed
  edge changes visible in context
- owner entity update validation runs once with the same information
- target entity LOAD privacy is not evaluated just because its id appears in an
  edge mutation
- junction entity create/delete hooks, validation, and privacy do not run unless
  the caller explicitly mutates the junction schema through its own generated
  repo
- `throughLink(...)` write-orientation edges get direct M2M helpers only when the
  junction schema satisfies the helper-eligible static shape constraints from
  [Many-To-Many Schema Modeling](03-m2m-schema-modeling.md)
- V1 does not synthesize a reverse traversal edge for `throughLink(...)`
  relationships (per
  [Many-To-Many Schema Modeling — Write Orientation](03-m2m-schema-modeling.md)),
  so there are no reverse-orientation helpers to talk about — callers
  that need to traverse the relationship from the opposite side query
  the junction schema directly. Explicit reverse `throughLink(...)`
  declarations (a second declaration with the same canonical
  relationship identity, including the swapped-orientation case) are
  rejected at schema validation. Reverse traversal is deferred to a
  follow-up `throughLinkInverse(...)` design — see "Future Enhancements"
  in RFC #3.
- `throughEntity(...)` edges do not get direct M2M helpers; their write rules
  live on the junction entity repo

This matches the current entity-level privacy model and avoids introducing a
second implicit policy surface for relationship writes. A later version can add
target or junction policy hooks if there is a concrete need.

## Update-Only V1

Link-table M2M helpers are update-only in V1. For update, the owner id is
already known. For create, the owner id may only be known after insert for
auto-increment ids, so create-time many-to-many mutation should be a later phase
until owner id availability and junction write ordering are specified for
multi-write creates.

## Edge-Only Updates And Returned Entity State

An update containing only link-table M2M edge operations is still an owner update
operation for hooks, privacy, validation, after hooks, and return LOAD privacy.
Before hooks run and may mutate scalar/FK fields, such as `updatedAt`. If hooks
or final-value computation produce scalar/FK changes, including owner field
`updateDefault` values such as `updatedAt.updateDefaultNow()`, generated code
updates the owner row before applying junction writes. This means an edge-only
relationship update may still emit an owner-row update that changes only
`updatedAt` or another update-default field. If no scalar/FK changes remain,
generated code must not issue an empty owner-row update.

`save()` should return the owner entity with scalar fields and FK fields
reflecting the saved owner row. Relationship edges, including mutated link-table
M2M edges, should be returned in the normal unloaded state.

Generated saves must not preserve stale pre-save edge data, patch edge lists in
memory, or implicitly load target entities after a mutation. This is especially
important for ID-only edge writes, where the target entities may never have been
loaded. Callers that need the updated relationship state should issue an
explicit query with eager loading after `save()`.

## Rollout Plan

1. Extend write candidates or write contexts with typed edge changes.
2. Generate many-to-many update helpers for link-table edges with
   id-only junction-table `add`, `remove`, and `set` on the single
   explicit `throughLink(...)` declaration only. The mutator's
   parameter type is the target's id type (mirrors the to-one FK API
   from RFC #2). No entity-argument overloads are generated.
3. Require the transaction and owner-edge serialization semantics from
   [Transaction And Locking Semantics](04-transaction-locking-semantics.md).
4. Consider create-time many-to-many helpers once owner id availability and
   junction write ordering are specified for multi-write creates.

## Test Requirements

Before implementation, add tests for:

- link-table M2M `add`, `remove`, and `set` require a transaction-scoped
  client and update the junction table; helper parameters are typed
  by the target's id type, and no entity-argument overloads are
  generated
- link-table M2M mutations do not evaluate target LOAD privacy and do not
  load target rows just because a target id appears in the mutation
- `remove(nonexistentId)` can be a no-op (no link to delete); applications
  that need it rejected do so via a validation rule that inspects
  `EdgeChanges.requestedRemoves` (the intent surface), which contains the id
  even when the database effect is empty
- edge mutation changes are visible to validation and privacy rules
- returned owner entities have normal unloaded edge state after link-table M2M
  saves; generated saves do not patch stale edge lists or implicitly load target
  entities
- `EdgeChanges` exposes set-valued `requestedSet`, `requestedAdds`,
  `requestedRemoves`, `added`, and `removed`. `requestedSet` is populated only
  in replacement mode; `requestedAdds` / `requestedRemoves` only in delta mode.
  `added` and `removed` are computed from current junction rows; intent fields
  are the deduplicated literal call log (duplicates collapse, same-id
  cancellations do not remove from intent)
- a `remove(aId); add(aId)` (or `add(aId); remove(aId)`) sequence in one
  mutation surfaces `aId` in both `EdgeChanges.requestedAdds` and
  `EdgeChanges.requestedRemoves`, while `added` and `removed` reflect the
  normalized database effect (potentially empty when the operations cancel)
- `EdgeChanges.requestedSet` reflects the final intended replacement set after
  the latest `set` call
- `PendingEdgeOps` exposes the same intent field names as `EdgeChanges`
  (`requestedSet`, `requestedAdds`, `requestedRemoves`). Same dedup /
  no-cancellation rule. `PendingEdgeOps` does not expose `added` / `removed`
  because junction state has not been read yet, and does not expose the raw
  ordered operation log
- mixed replacement and delta operations for the same link-table M2M edge
  throw `IllegalStateException` — fail-fast at the incompatible mutator call
  site, and as a defense-in-depth check at start-of-save preflight (after the
  transaction/capability preflight, before hooks/privacy/validation/driver
  I/O); the exception is not an `EntError` variant and `saveOrError()` does
  not catch it
- before hooks receive hook-facing mutation interfaces that expose mutable
  scalar/FK fields and read-only `PendingEdgeOps`, but do not expose link-table
  M2M mutators
- hooks fire once for the owning entity mutation

## Open Questions

- Should edge changes live directly on `WriteCandidate`, or should privacy and
  validation contexts expose them separately from scalar candidates?
- Should the helpers add a target-side locking strategy so endpoint deletes on
  requested-present ids can't race with `set(...)` / `add(...)` and shrink the
  final link set? V1 declines — adding target-side locking would contradict the
  "never load target rows" principle and require a new driver capability
  (`lockRowsForShare` or equivalent). A future RFC could add an opt-in locking
  mode if the race becomes a practical concern.
