# RFC: Link-Table M2M Mutation Helpers

## Status

Possible future feature. This is not implemented.

Split out from [Edge Mutation API](00-overview.md).

## Summary

Generate collection-style mutation helpers for helper-eligible `throughLink(...)`
many-to-many edges:

- `tags.add(tag)` / `tags.addId(tagId)`
- `tags.remove(tag)` / `tags.removeId(tagId)`
- `tags.set(tags)` / `tags.setIds(tagIds)`

This RFC covers helper API shape, normalization, hook-facing pending edge views,
privacy/validation candidate shape, target loading semantics, edge-only update
return state, and create-time deferral.

The schema marker and link-table eligibility rules live in
[Many-To-Many Schema Modeling](03-m2m-schema-modeling.md). Transaction
and locking requirements live in
[Transaction And Locking Semantics](04-transaction-locking-semantics.md).

## Proposed API

For link-table many-to-many edges, generate collection-style add/remove/set
methods on the edge property:

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post) {
        tags.add(kotlinTag)
    }.save()
}
```

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post) {
        tags.remove(oldTag)
    }.save()
}
```

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post) {
        tags.set(listOf(kotlinTag, ormTag))
    }.save()
}
```

Generate ID-only variants for callers that do not have the target entities
loaded:

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post) {
        tags.addId(kotlinTagId)
        tags.removeId(oldTagId)
    }.save()
}
```

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post) {
        tags.setIds(listOf(kotlinTagId, ormTagId))
    }.save()
}
```

All generated link-table M2M helpers require a transaction-scoped client and use
the same owner-edge serialization discipline. `set(...)` and `setIds(...)` are
exact replacements: generated code serializes the owner-edge relationship before
reading or mutating junction rows. After the generated junction writes complete
inside that serialized section, the relationship set equals the requested set.

## Target Loading And Existence

For link-table M2M helpers, entity arguments are lowered to target ids.
`tags.add(tag)`, `tags.remove(tag)`, and `tags.set(tags)` do not reload target
rows; ID variants never load target rows. Target LOAD privacy is not evaluated
just because a target entity or id appears in a link-table M2M mutation.

Target existence for inserted links is enforced by junction-table foreign-key
constraints unless a validation rule explicitly checks it earlier. Removals do
not prove target existence: `removeId(nonexistentId)` may be a no-op if no
matching link exists. Applications that want unknown removal ids rejected should
add validation.

## Generated Builder Shape

For link-table many-to-many edges, generated builders should expose an edge
mutator property:

```kotlin
class PostUpdate {
    val tags: TagEdgeMutator = TagEdgeMutator()
}

class TagEdgeMutator {
    fun add(tag: Tag) {
        addId(tag.id)
    }

    fun remove(tag: Tag) {
        removeId(tag.id)
    }

    fun set(tags: List<Tag>) {
        setIds(tags.map { it.id })
    }

    fun addId(id: UUID)
    fun removeId(id: UUID)
    fun setIds(ids: List<UUID>)
}
```

Generated edge mutators should be typed. `tags.add(...)` / `tags.remove(...)`
should accept `Tag`, and `tags.addId(...)` / `tags.removeId(...)` /
`tags.setIds(...)` should accept `Tag` ids.

`set(...)` and `setIds(...)` are replacement operations. In V1, replacement
operations and delta operations are mutually exclusive for a given edge within a
single mutation. A builder may call either `set(...)` / `setIds(...)` or
`add(...)` / `addId(...)` / `remove(...)` / `removeId(...)`, but not both for the
same edge. Multiple replacement calls for the same edge are allowed; the latest
replacement wins as long as no delta operation is also present. Generated
mutators should reject mixed replacement/delta usage at the incompatible call
site, or at `save()` preflight before hooks, privacy, validation, driver reads,
or driver writes.

## Pending Edge Operations

Builders should preserve the ordered operation log internally. The public
candidate/context representation should expose the requested replacement, if
any, plus the computed database delta. It should not expose the raw operation
sequence.

Before hooks should receive hook-facing mutation interfaces, not the public
builder type that exposes link-table M2M mutators. Hook-facing interfaces should
continue to expose mutable scalar/FK fields, but they must not expose
`tags.add(...)`, `tags.remove(...)`, `tags.set(...)`, or ID variants. They should
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
    val ensurePresentIds: Set<ID> = emptySet(),
    val ensureAbsentIds: Set<ID> = emptySet(),
) {
    val hasReplacement: Boolean get() = requestedSet != null
    val hasChanges: Boolean get() =
        requestedSet != null || ensurePresentIds.isNotEmpty() || ensureAbsentIds.isNotEmpty()
}

data class PostPendingEdgeOps(
    val tags: PendingEdgeOps<Long> = PendingEdgeOps(),
)
```

`PendingEdgeOps` is intent-level. It is computed from the builder's pending
operation log before current junction rows are read. `EdgeChanges`, described
below, is computed later after current junction rows are read. `ensurePresentIds`
and `ensureAbsentIds` are intent names: they describe relationships the caller
wants present or absent, not database rows that are known to be inserted or
deleted. Actual computed database deltas are exposed only on `EdgeChanges.added`
and `EdgeChanges.removed`. Its public fields are sets because relationship
intent is unordered; hooks must not depend on iteration order.

For a given edge, `requestedSet` is mutually exclusive with `ensurePresentIds` /
`ensureAbsentIds` in `PendingEdgeOps` because V1 rejects mixed replacement and
delta operations. `requestedSet` is the deduplicated latest `set(...)` /
`setIds(...)` operand. `ensurePresentIds` and `ensureAbsentIds` are used only for
delta-only mutations and contain deduplicated ids from `add(...)` / `addId(...)`
and `remove(...)` / `removeId(...)` calls after applying in-builder delta
normalization.

## Normalization

Many-to-many mutations normalize by target id before writing:

- duplicate ids collapse to a single intended relationship
- `set(listOf(a, a))` is equivalent to `set(listOf(a))`
- `setIds(listOf(aId, aId))` is equivalent to `setIds(listOf(aId))`
- `add(a)` twice is equivalent to `add(a)` once
- `addId(aId)` twice is equivalent to `addId(aId)` once
- `add(a)` followed by `removeId(a.id)` has no net add
- `removeId(aId)` followed by `addId(aId)` has no net remove if `aId` was
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
    val added: Set<ID> = emptySet(),
    val removed: Set<ID> = emptySet(),
)
```

`EdgeChanges` separates replacement intent from the computed database effect:

- `requestedSet` is present only when the pending operation log contains a
  `set(...)` / `setIds(...)` replacement. It contains the deduplicated final
  intended relationship set from the latest replacement operation.
- `added` contains ids that will be inserted into the link table after comparing
  the pending operations with current junction rows.
- `removed` contains ids that will be deleted from the link table after
  comparing the pending operations with current junction rows.

These fields are sets because relationship membership is unordered. Duplicates
are eliminated, and privacy/validation code must not depend on iteration order.

It is not the raw sequence of user calls, and it is distinct from the
`PendingEdgeOps` view exposed to before hooks. Privacy and validation rules
authorize the computed database effect in `added` / `removed`; `requestedSet` is
available when a rule needs to distinguish replacement operations from delta
operations.

For example, if the current link set is `[a, c]` and the caller runs
`tags.set(listOf(a, b))`, rules observe:

```kotlin
EdgeChanges(
    requestedSet = setOf(a, b),
    added = setOf(b),
    removed = setOf(c),
)
```

If the caller runs `tags.setIds(listOf(a, b))`, then `tags.addId(c)` in the same
mutation, the builder rejects the mixed replacement/delta usage before
observable work.

If the caller runs `tags.add(a)` followed by `tags.removeId(a.id)` in one
mutation, rules observe no computed database change for `a`.

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
- synthesized reverse orientations remain traversal-only in V1, and explicit
  reverse `throughLink(...)` declarations are rejected
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
   junction-table `add/remove/set` and `addId/removeId/setIds` on the single
   explicit `throughLink(...)` declaration only.
3. Require the transaction and owner-edge serialization semantics from
   [Transaction And Locking Semantics](04-transaction-locking-semantics.md).
4. Consider create-time many-to-many helpers once owner id availability and
   junction write ordering are specified for multi-write creates.

## Test Requirements

Before implementation, add tests for:

- link-table M2M `add`, `remove`, `set`, `addId`, `removeId`, and `setIds`
  require a transaction-scoped client and update the junction table
- link-table M2M entity arguments lower to target ids without reloading target
  rows, and ID variants never load target rows
- link-table M2M mutations do not evaluate target LOAD privacy just because a
  target entity or id appears in the mutation
- `removeId(nonexistentId)` can be a no-op unless validation rejects unknown
  removal ids
- edge mutation changes are visible to validation and privacy rules
- returned owner entities have normal unloaded edge state after link-table M2M
  saves; generated saves do not patch stale edge lists or implicitly load target
  entities
- `EdgeChanges` exposes set-valued `requestedSet`, `added`, and `removed`, with
  `added` and `removed` computed from current junction rows
- `EdgeChanges.requestedSet` reflects the final intended replacement set after
  the latest `set` / `setIds`
- `PendingEdgeOps` exposes set-valued intent fields and does not expose the raw
  ordered operation log
- mixed replacement and delta operations for the same link-table M2M edge are
  rejected at the incompatible mutator call or during start-of-save preflight
- before hooks receive hook-facing mutation interfaces that expose mutable
  scalar/FK fields and read-only `PendingEdgeOps`, but do not expose link-table
  M2M mutators
- hooks fire once for the owning entity mutation

## Open Questions

- Should edge changes live directly on `WriteCandidate`, or should privacy and
  validation contexts expose them separately from scalar candidates?
