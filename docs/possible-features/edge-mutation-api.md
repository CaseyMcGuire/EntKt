# RFC: Edge Mutation API

## Status

Possible future feature. This is not implemented.

## Summary

Generate relationship mutation APIs so callers can assign to-one edges and
mutate link-table edges without editing join tables. The API should also support
ID-only writes so callers do not need to load a full target entity when they
already have the target id.

The implementation should be staged. To-one assignment can lower directly to
the existing foreign-key mutation path. Many-to-many mutation should use explicit
schema-level markers instead of the current generic `.through(...)` marker:

- `throughLink(...)` edges, where the junction table is just relationship
  storage and the declared write orientation may get direct `add/remove/set`
  helpers
- `throughEntity(...)` edges, where the junction row is a real domain entity and
  the junction repo remains the write API

Direct many-to-many helpers require additional junction-table writes and should
be implemented after the to-one API shape is settled.

## Motivation

entkt already generates typed edge metadata and eager-loading APIs. Mutation
APIs still tend to expose storage details:

```kotlin
client.posts.create {
    authorId = user.id
}.save()
```

An edge mutation API would let applications express graph changes directly:

```kotlin
client.posts.create {
    title = "Hello"
    author = user
}.save()
```

## Non-Goals

- Do not hide required edge validation.
- Do not bypass privacy or validation.
- Do not support arbitrary graph saves in the first version.
- Do not add cascading create for nested objects in the first version.
- Do not mutate inverse edges (`hasOne` / `hasMany`) from the non-FK-owning
  side in the first version. Those relationships should be changed from the
  `belongsTo` side that owns the FK.

## Proposed API

Use relationship-shaped APIs for edge mutation in V1:

- to-one edges use property assignment
- link-table many-to-many edges use collection-style edge mutators
- through-entity many-to-many edges use the junction entity repo

The relationship nullability model should be required by default. A
`belongsTo<Target>(...)` edge should produce a non-null FK unless the schema
marks the relationship with `.nullable()` / `.optional()`. Existing
`.required()` calls can remain as compatibility no-ops or be removed by a
migration, but the long-term public model should be non-null by default and
nullable only when explicitly requested.

For FK-owning to-one edges, entity assignment is the ergonomic API:

```kotlin
client.posts.create {
    title = "Hello"
    author = alice
}.save()
```

```kotlin
client.posts.update(post) {
    author = bob
}.save()
```

Optional to-one edges, declared with `.nullable()` / `.optional()`, can be
cleared by assigning `null`:

```kotlin
client.posts.update(post) {
    author = null
}.save()
```

The same relationship can be written by id when the caller already has the
target id:

```kotlin
client.posts.create {
    title = "Hello"
    authorId = aliceId
}.save()
```

```kotlin
client.posts.update(post) {
    authorId = bobId
}.save()
```

For optional to-one edges, assigning `null` to the resolved FK property also
clears the edge:

```kotlin
client.posts.update(post) {
    authorId = null
}.save()
```

Passing an entity never requires reloading it; the builder uses the entity id.
Passing an id never loads the target entity automatically.
Target existence is enforced by database constraints unless a validation rule
explicitly checks it. Target LOAD privacy is not evaluated just because a target
entity or id appears in an edge mutation.

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

All generated link-table M2M helpers require a transaction-scoped client and use
the same owner-edge serialization discipline. `set(...)` and `setIds(...)` are
exact replacements: generated code serializes the owner-edge relationship before
reading or mutating junction rows. After the generated junction writes complete
inside that serialized section, the relationship set equals the requested set.

For link-table M2M helpers, entity arguments are lowered to target ids.
`tags.add(tag)`, `tags.remove(tag)`, and `tags.set(tags)` do not reload target
rows; ID variants never load target rows. Target LOAD privacy is not evaluated
just because a target entity or id appears in a link-table M2M mutation. Target
existence for inserted links is enforced by junction-table foreign-key
constraints unless a validation rule explicitly checks it earlier. Removals do
not prove target existence: `removeId(nonexistentId)` may be a no-op if no
matching link exists. Applications that want unknown removal ids rejected should
add validation.

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

Generated edge mutators should be typed according to schema nullability.
Required to-one edges should expose non-null assignment types, such as
`author: User` and `authorId: UUID`. Optional to-one edges should expose nullable
assignment types, such as `author: User?` and `authorId: UUID?`. Required create
builders may use nullable internal staging state to represent "not assigned yet",
but `null` should not be part of the public assignment API for required edges.
`tags.add(...)` / `tags.remove(...)` should accept `Tag`, and `tags.addId(...)` /
`tags.removeId(...)` / `tags.setIds(...)` should accept `Tag` ids.

Many-to-many edges must declare their mutation model at the schema site. V1
should replace the generic `.through(...)` marker with two explicit forms:

```kotlin
class Post : EntSchema("posts") {
    val tags = manyToMany<Tag>("tags")
        .throughLink<PostTag>(PostTag::post, PostTag::tag)
}
```

```kotlin
class Group : EntSchema("groups") {
    val members = manyToMany<User>("members")
        .throughEntity<Membership>(Membership::group, Membership::user)
}
```

- `throughLink(...)`: the junction table is relationship storage. Direct edge
  helpers are part of the public API for the declared write orientation when the
  junction schema satisfies the V1 static safety constraints.
- `throughEntity(...)`: the relationship remains traversable as an edge, but the
  junction row is a domain entity. Callers mutate it through its generated repo
  instead of edge sugar.

Codegen must not infer this distinction from runtime hook/privacy/validation
configuration or from the junction table shape alone. The schema marker is the
source of truth; static shape checks can reject unsafe `throughLink(...)`
declarations.

For V1, a link-table M2M relationship may have only one explicit
`throughLink(...)` declaration for a given junction/source/target pair. That
declaration owns the write orientation and gets generated helpers. The reverse
edge, if synthesized, inherits the relationship metadata but remains
traversal/eager/query-only.

Codegen must reject an explicit opposite-side `throughLink(...)` declaration for
the same junction relationship in V1. Without a concrete read-only marker or
canonical reverse-write lock model, explicit bidirectional link-table helpers
would make the owner of the relationship ambiguous and could reintroduce
exact-set races between opposite orientations.

For `throughEntity(...)`, callers mutate the junction entity through its repo, so
explicit opposite-side traversal declarations are allowed as long as both
declarations use `throughEntity(...)`. Codegen should still reject mismatches
between `throughLink(...)` and `throughEntity(...)` for the same junction
relationship.

In the first version, to-one assignment is generated only for FK-owning
`belongsTo` edges. Inverse `hasOne` and `hasMany` edges do not get owner-side
mutators until the API has a clear rule for updating the target row that owns
the FK.

## Existing To-One Surface

Generated create/update builders already expose entity assignment properties for
`belongsTo` edges. Assigning an entity writes its id into the underlying FK
property:

```kotlin
client.posts.create {
    author = alice
}.save()
```

They also expose the resolved FK property, either as the generated implicit FK
such as `authorId`, or as the user-declared field for
`belongsTo(...).field(handle)` edges:

```kotlin
author = alice       // sets authorId = alice.id
authorId = alice.id  // writes the FK directly
author = null        // .nullable() / .optional() edge only; clears authorId
authorId = null      // .nullable() / .optional() edge only; clears the FK directly
```

For implicit FKs, the id-only path is the generated `{edge}Id` property. For
`belongsTo(...).field(handle)` edges, the id-only path is the user-declared
field property backing that edge. The implementation must not create a second
FK path.

For `belongsTo(...).field(handle)` edges, relationship nullability and backing
field nullability must match. Codegen should reject a required relationship
backed by a nullable field, and should reject an optional relationship backed by
a non-null field. The edge declaration and field declaration should describe the
same database constraint instead of one side overriding the other.

Documentation and examples should present entity assignment as the ergonomic
to-one API and FK assignment as the ID-only variant. They should not introduce
additional to-one helper methods.

Required edge checks should continue to happen during generated save preparation
before privacy, validation, or database writes, so leaving a required-by-default
edge unset or clearing it fails the same way setting a required FK to null fails
today.

Entity assignment and FK assignment are two public ways to write the same
pending relationship state. The intended normalized behavior is that if a caller
mixes them in one builder, to-one edges use last-write-wins semantics over the
underlying FK value:

```kotlin
authorId = alice.id       // writes authorId
author = bob              // writes authorId = bob.id
authorId = carol.id       // writes authorId and clears cached author
author = null             // writes authorId = null
```

The final FK value after the create/update block and before hooks have run is
the value hooks initially observe. Hooks mutate a hook-facing scalar/FK mutation
view, not necessarily the public builder, and their writes also follow
last-write-wins before candidate construction. Hook, privacy, and validation code
should treat the final FK value and `WriteCandidate` as the source of truth, not
any cached entity reference that happened to be assigned earlier in the builder
lifecycle.

## Generated Builder Shape

For each mutable FK-owning to-one edge, generated create/update builders expose
the relationship property and its resolved FK property. Both write through to
the same pending FK state.

Conceptually:

```kotlin
class PostCreate {
    private var cachedAuthor: User? = null
    private var resolvedAuthorFk: UUID? = null

    var author: User
        get() = cachedAuthor
            ?: error("author has not been assigned")
        set(value) {
            cachedAuthor = value
            resolvedAuthorFk = value.id
        }

    var authorId: UUID
        get() = resolvedAuthorFk
            ?: error("authorId has not been assigned")
        set(value) {
            cachedAuthor = null
            resolvedAuthorFk = value
        }
}
```

This is conceptual, not a required implementation shape. Generated code may
store only one backing FK field as long as assignment and dirty tracking behave
the same way.

The nullable private fields in this conceptual required-edge example are staging
state only. They let create builders distinguish an unset required edge from an
assigned edge before save preparation. They do not mean public assignment accepts
`null` for required relationships.

If a caller writes the resolved FK property directly, the generated setter
should clear the cached entity reference because the builder no longer knows
which `User` instance, if any, matches the FK. Entity assignment sets both the
cached entity and the resolved FK. Assigning `null` to either the entity
property or the resolved FK clears both.

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

`set(...)` and `setIds(...)` are replacement operations. In V1, replacement
operations and delta operations are mutually exclusive for a given edge within a
single mutation. A builder may call either `set(...)` / `setIds(...)` or
`add(...)` / `addId(...)` / `remove(...)` / `removeId(...)`, but not both for the
same edge. Multiple replacement calls for the same edge are allowed; the latest
replacement wins as long as no delta operation is also present. Generated
mutators should reject mixed replacement/delta usage at the incompatible call
site, or at `save()` preflight before hooks, privacy, validation, driver reads,
or driver writes.

All generated link-table M2M writes use one serialization discipline. Any save
with pending `add(...)`, `addId(...)`, `remove(...)`, `removeId(...)`, `set(...)`,
or `setIds(...)` requires a transaction-scoped client and a driver row-lock
capability. The transaction and row-lock capability requirements are checked at
the start of `save()`, before hooks, field extraction/defaulting, privacy,
validation, driver reads, or driver writes.

Before reading current junction rows or mutating the junction table, generated
code must serialize the owner-edge relationship. V1 may implement this by
locking the owner row, which can serialize all link-table edges on that owner.
That over-serialization is acceptable; the semantic requirement is at least
per-owner-edge serialization. If the save is not running inside an explicit
transaction, or if a driver cannot provide serialization for an edge, generated
code must fail deterministically before observable work.

Generated link-table M2M serialization is guaranteed only among generated
link-table M2M helpers, plus other code that uses the same locking discipline.
It cannot protect against direct junction repo writes, manual driver calls, or
database access outside entkt.

V1 should not expose weak or optimistic edge-set concurrency. Optimistic set
semantics need a concrete revision source, such as an owner version column, a
dedicated edge-revision row, or a driver capability like serializable
transactions with retry. Non-serialized semantics should remain available
through lower-level junction repo or manual driver writes, not through the
generated link-table edge helper API.

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
intent is unordered; hooks must not depend on iteration order. For a given edge,
`requestedSet` is mutually exclusive with `ensurePresentIds` /
`ensureAbsentIds` in `PendingEdgeOps` because V1 rejects mixed replacement and
delta operations. `requestedSet` is the deduplicated latest `set(...)` /
`setIds(...)` operand. `ensurePresentIds` and `ensureAbsentIds` are used only for
delta-only mutations and contain deduplicated ids from `add(...)` / `addId(...)`
and `remove(...)` / `removeId(...)` calls after applying in-builder delta
normalization.

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

### Many-To-Many Scope

V1 should make the many-to-many distinction explicit in the edge declaration
instead of surprising callers with conditional helper generation after the fact:

- `throughLink(...)`: helper-eligible relationship whose junction table is just a
  link table; the declared write orientation gets `add/remove/set` and
  `addId/removeId/setIds` after static V1 safety checks pass
- `throughEntity(...)`: relationship whose junction row carries domain data or
  domain rules; this edge does not get direct helpers, and callers mutate the
  junction entity through its repo

Conceptually, the edge metadata should carry this choice:

```kotlin
enum class ManyToManyMutationMode {
    LinkTable,
    ThroughEntity,
}

data class Through(
    val target: EntSchema,
    val sourceEdge: String?,
    val targetEdge: String?,
    val mutationMode: ManyToManyMutationMode,
)
```

The safety rules below define what qualifies as a helper-eligible
`throughLink(...)` edge in V1. A junction schema is safe for direct edge mutation
only when:

- it contains exactly the junction id column plus the two FK columns. Extra
  payload columns are not safe in V1, even when nullable or defaulted, because
  generated create builders, not low-level `Driver.insert`, apply field defaults
- both junction `belongsTo` edges are non-null. Under the long-term schema model,
  this is the default; junction edges marked `.nullable()` / `.optional()` are not
  safe for direct link-table helpers
- its id strategy can be satisfied without caller input, such as auto numeric
  ids or client-generated UUIDs. Junction schemas with explicit caller-provided
  ids, such as `EntId.string()`, are not safe for direct helpers unless a later
  design defines how edge mutators supply those ids
- it declares a non-partial unique composite index or constraint on exactly the
  source FK and target FK pair. Normalized set semantics require the database to
  reject duplicate links under concurrent writers and to rule out preexisting
  duplicate link rows

For junction schemas whose id strategy is client-generated UUID, generated
link-table M2M helpers must populate the junction `id` with a freshly generated
UUID before calling `Driver.insert(...)` / `insertMany(...)`. Auto-generated
database ids may be omitted when the driver/database owns id generation.

If a junction schema carries payload such as `role`, `joinedAt`, or other domain
data, the edge should be declared with `throughEntity(...)`. V1 should reject
`throughLink(...)` for that edge instead of silently omitting direct collection
mutators. Callers should mutate the junction schema through its generated repo,
where they can provide the payload explicitly and get the normal defaulting
behavior.

If a relationship needs hooks, privacy, validation, or other write-time
behavior on the junction row itself, that relationship should be declared with
`throughEntity(...)` instead of `throughLink(...)`. Direct link-table helpers do
not run junction repo hooks, privacy, or validation.

## Enforcement Semantics

Edge mutations must participate in the same write pipeline as scalar fields.
Generated saves are transaction-neutral by default: they execute in the client
scope they are called from. A normal client does not open a transaction
implicitly. A transaction-scoped client created by
`client.withTransaction { tx -> ... }` causes generated driver calls, privacy
checks, validation checks, and rule-context `ctx.client` queries for that save
to use the transaction-scoped driver/client.

Transactions are required only when the selected operation semantics require
them, such as any generated link-table M2M helper, or when the client configures
a stricter `TransactionRequirement`.

1. transaction/capability preflight
2. before hooks
3. field extraction/defaults or final-value computation
4. field validation and required edge checks
5. candidate construction
6. relationship reads and edge normalization when needed
7. privacy
8. validation
9. database writes
10. after hooks
11. returned LOAD privacy

Candidates should include resulting foreign key values and relationship
changes so validation and privacy rules can inspect them.

Privacy contexts keep the caller's privacy context. Validation contexts keep the
existing System-scoped LOAD-privacy bypass. When a save runs on a
transaction-scoped client, those contexts must also use clients backed by the
transaction-scoped driver so rule code observes the same transaction snapshot as
the write it is authorizing or validating. When a save runs on a normal client,
contexts use the normal client/driver.

Link-table M2M helpers may issue multiple driver calls: owner-row updates,
junction reads, junction inserts, and junction deletes. They require a
transaction-scoped client so those calls share one transaction and one
owner-edge serialization boundary. Generated code must reject link-table M2M
saves outside an explicit transaction before hooks, privacy, validation, driver
reads, or driver writes.

The client may provide a runtime guardrail for teams that want stricter
transaction discipline without implicit transactions:

```kotlin
enum class TransactionRequirement {
    Optional,
    RequiredForMultiWrite,
    RequiredForAllWrites,
}
```

This setting is a runtime check, not an implicit transaction mechanism. All
transaction requirement checks must run at the start of `save()`, before hooks,
field extraction/defaulting, privacy, validation, driver reads, or driver
writes. This includes configured `TransactionRequirement` checks and semantic
requirements selected by pending operations, such as any generated link-table
M2M mutation. If a save violates a transaction requirement, generated code
should throw `TransactionRequiredException` immediately.
Implementing the check requires the generated client or driver to expose whether
the current client is transaction-scoped. `RequiredForMultiWrite` applies to
saves that may perform more than one driver write, such as owner-row plus
link-table junction writes.

`RequiredForMultiWrite` should be classified from the requested operation shape
at the start of `save()`, before hooks, defaults, normalization, privacy,
validation, or driver reads/writes. Any pending generated link-table M2M mutation
counts as multi-write, even if normalization later produces no junction delta or
the save emits no owner-row update. Scalar/to-one saves count as multi-write only
when the generated save path is known to require more than one driver write.

### To-One Pipeline

To-one edge mutations should be resolved before candidate construction:

1. start-of-save transaction requirement checks run. For scalar/to-one saves,
   this primarily enforces configured guardrails such as
   `TransactionRequirement.RequiredForAllWrites`, and must throw before hooks or
   other observable work when the requirement is not met
2. before hooks run
3. entity or id assignment has already updated the FK property
4. final scalar/FK values are computed and field validation plus required edge
   checks run
5. the write candidate includes the final FK value
6. privacy and validation run in the caller's client scope
7. the owner row is inserted or updated
8. after hooks and return LOAD privacy run

This avoids a second relationship-write phase for `belongsTo` edges.

### Many-To-Many Pipeline

Many-to-many edge mutations require junction-table writes and therefore require
a transaction-scoped client:

1. start-of-save transaction requirement checks run. If any generated link-table
   M2M operation is pending, generated code requires a transaction-scoped client
   and throws `TransactionRequiredException` before hooks or driver reads/writes
   when the save is not transaction-scoped
2. before hooks receive the normal scalar/FK mutation surface plus a read-only
   pending edge operation view
3. the pending edge operation log is captured
4. generated code serializes the owner-edge relationship and reads the current
   owner row before current junction rows are read or junction rows are mutated
5. final scalar/FK values are computed using the locked current owner row as the
   fallback base for fields not changed by the builder or hooks. Field validation
   and required edge checks run on those final values
6. the scalar/FK candidate is built using the locked current owner row as the
   update `before` state
7. current junction rows are read and `EdgeChanges` is computed from the pending
   operation log and current link set
8. write privacy runs with the locked current owner row, candidate, and computed
   edge changes
9. configured write validation runs with the same information
10. the owner row is updated when scalar/FK values changed
11. junction rows are inserted/deleted/replaced
12. after hooks and return LOAD privacy run

For update, the owner id is already known. For create, the owner id may only be
known after insert for auto-increment ids, so create-time many-to-many mutation
should be a later phase until owner id availability and junction write ordering
are specified for multi-write creates.

The transaction must include edge serialization, the current owner-row read, the
current-state junction read, final candidate construction, `EdgeChanges`
computation, privacy checks, validation checks, any owner update, and all
junction writes. After hooks and returned LOAD privacy also run before the
caller's explicit transaction exits. If privacy, validation, the owner update,
junction inserts/deletes/replacements, after hooks, or returned LOAD privacy
throw, the owner update and junction database writes must roll back.

Database rollback does not undo external side effects performed by before or
after hooks. Before hooks may run before a missing-owner update returns `null`,
and after hooks may run before the caller's explicit transaction exits. Hooks
that send messages, write caches, or call external services should be idempotent
or use an outbox/after-commit pattern if those side effects must only happen
after the transaction commits. A dedicated after-commit hook can be considered
separately.

For update saves with pending link-table M2M operations, the entity passed to
`update(entity)` supplies the owner id and initial builder state only. Privacy
and validation must use the locked current owner row as the update `before`
entity. Non-dirty scalar/FK fields must also fall back to the locked current
owner row, not the possibly stale entity originally passed to `update(entity)`.
Scalar/to-one-only updates keep the existing behavior unless a later RFC changes
all updates to refresh or lock before save.

Before hooks intentionally run before any owner-row lock/read, matching scalar
and to-one save ordering. For link-table M2M updates, hook-facing mutation state
is initialized from the input entity and pending builder changes, not from the
locked current database row. Hooks should shape the requested mutation, such as
timestamps or derived fields, rather than authorize current stored state.
Because hook-facing scalar/FK values may come from the stale entity passed to
`update(entity)`, a hook that reads a scalar/FK value and writes a derived value
can mark stale input data dirty and overwrite newer database state. Before hooks
should avoid read-modify-write logic that assumes current database values.
Current-state invariants must live in privacy or validation, which run after the
locked current owner row is read. If the owner row no longer exists, before hooks
may already have run before `save()` returns `null`.

An update containing only link-table M2M edge operations is still an owner update
operation for hooks, privacy, validation, after hooks, and return LOAD privacy.
Before hooks run and may mutate scalar/FK fields, such as `updatedAt`. If hooks
or final-value computation produce scalar/FK changes, including owner field
`updateDefault` values such as `updatedAt.updateDefaultNow()`, generated code
updates the owner row before applying junction writes. This means an edge-only
relationship update may still emit an owner-row update that changes only
`updatedAt` or another update-default field. If no scalar/FK changes remain,
generated code must not issue an empty owner-row update. Instead, it returns the
current owner row read inside the transaction after the owner lock, with
relationship edges unloaded. If the owner row cannot be locked/read, `save()`
returns `null` and no junction rows are read or mutated.

### Returned Entity State

`save()` should return the owner entity with scalar fields and FK fields
reflecting the saved owner row. Relationship edges, including mutated link-table
M2M edges, should be returned in the normal unloaded state.

Generated saves must not preserve stale pre-save edge data, patch edge lists in
memory, or implicitly load target entities after a mutation. This is especially
important for ID-only edge writes, where the target entities may never have been
loaded. Callers that need the updated relationship state should issue an
explicit query with eager loading after `save()`.

## Candidate Shape

`WriteCandidate` should continue to expose scalar fields and FK values. To-one
edge mutations need no additional candidate model because they lower to FK
values.

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

- owner entity create/update privacy runs once with the scalar candidate and
  computed edge changes visible in context
- owner entity create/update validation runs once with the same information
- target entity LOAD privacy is not evaluated just because its id appears in an
  edge mutation
- junction entity create/delete hooks, validation, and privacy do not run unless
  the caller explicitly mutates the junction schema through its own generated
  repo
- `throughLink(...)` write-orientation edges get direct M2M helpers only when the
  junction schema satisfies the helper-eligible static shape constraints from the
  Many-To-Many Scope section; synthesized reverse orientations remain
  traversal-only in V1, and explicit reverse `throughLink(...)` declarations are
  rejected
- `throughEntity(...)` edges do not get direct M2M helpers; their write rules
  live on the junction entity repo

This matches the current entity-level privacy model and avoids introducing a
second implicit policy surface for relationship writes. A later version can add
target or junction policy hooks if there is a concrete need.

## Driver Strategy

To-one edge mutations require no new driver methods.

Generated saves do not call `driver.withTransaction` implicitly. They use the
driver attached to the client they were created from: the normal driver for a
normal client, or the transaction-scoped driver for a client created by
`client.withTransaction { tx -> ... }`.

For link-table many-to-many mutations, prefer generated code using existing
driver methods against the junction table:

- `query(...)` to inspect existing junction rows for normalization
- `insert(...)` or `insertMany(...)` to add rows
- `deleteMany(...)` to remove rows

Beyond the row-lock capability below, only add dedicated driver APIs if
generated junction-table code becomes duplicated or inconsistent across drivers.

Using existing methods does not create an implicit transaction. Generated
link-table helper code calls those methods through the same current driver used
by the owner-row update. Because link-table helpers always require a
transaction-scoped client, owner updates and junction writes share one transaction.

Generated link-table M2M helpers require an explicit transaction-scoped driver
and a driver capability for owner-edge serialization. V1 should add a
transaction state signal and a low-level row-lock operation:

```kotlin
interface Driver {
    val inTransaction: Boolean
    val supportsRowLockForUpdate: Boolean
    fun lockRowForUpdate(table: String, id: Any): Boolean
}
```

`lockRowForUpdate(table, id)` is an internal driver capability used by generated
code. It is intentionally table/id based to match the existing raw `Driver`
contract. Application code should not call it as the edge mutation API; the typed
application-facing API remains on generated builders, such as `tags.add(...)`,
`tags.remove(...)`, and `tags.set(...)`.

Generated code checks transaction state and row-lock support at the start of
`save()`, before hooks or driver reads/writes:

```kotlin
if (hasLinkTableM2MMutation && !driver.inTransaction) {
    throw TransactionRequiredException("link-table edge mutation requires a transaction")
}
if (hasLinkTableM2MMutation && !driver.supportsRowLockForUpdate) {
    throw UnsupportedOperationException("link-table edge mutation requires row locking")
}
```

After that guard has passed, generated code takes the row lock and reads the
current owner row before reading current junction rows or mutating the junction
table:

```kotlin
if (hasLinkTableM2MMutation) {
    val ownerExists = driver.lockRowForUpdate(Post.TABLE, post.id)
    if (!ownerExists) return null
    lockedOwnerRow = driver.byId(Post.TABLE, post.id) ?: return null
}

val before = Post.fromRow(lockedOwnerRow)
val candidate = buildCandidate(fallbackBase = before, dirtyScalarAndFkValues)
val currentLinks = driver.query(PostTag.TABLE, ...)
val edgeChanges = normalize(currentLinks, pendingEdgeOps)
// privacy(before, candidate, edgeChanges)
// validation(before, candidate, edgeChanges)
// optional owner update, junction writes
```

If the owner row cannot be locked because it no longer exists, generated update
saves should return `null` without reading or mutating junction rows, matching
the existing `driver.update(...) ?: return null` behavior. For edge-only updates
with no scalar/FK changes, this lock result is the owner existence check and
generated code should not issue a no-op owner update; it should return the owner
row read inside the transaction after the lock, with edges unloaded.

For link-table M2M updates, the locked owner row is also the update `before`
state and the fallback base for scalar/FK fields the caller did not change. The
input entity passed to `update(entity)` must not be used as the privacy/
validation `before` state after the lock has been taken.

Drivers may implement `lockRowForUpdate` with an equivalent internal strategy,
such as an advisory lock keyed by table and id plus an owner existence check, if
it provides the same owner-edge serialization semantics inside the active
transaction.
Serializable transactions with retry may be modeled by a later, more abstract
driver capability. Drivers that cannot provide V1 row-lock semantics must reject
generated link-table M2M mutations at the start of `save()`, before hooks,
privacy, validation, driver reads, or driver writes.

Through-entity edges do not use this direct junction-write path. They continue
to use the junction repo's normal create/update/delete pipeline.

## Rollout Plan

1. Document to-one entity assignment as the ergonomic public API and FK
   assignment as the ID-only variant. Ensure both write through to the same
   resolved FK state.
2. Change relationship nullability to required by default for `belongsTo(...)`,
   with `.nullable()` / `.optional()` as the explicit optional relationship
   marker. Keep `.required()` only as compatibility sugar or migrate it away.
3. Preserve transaction-neutral generated save semantics. Normal clients should
   not open transactions implicitly; transaction-scoped clients should make all
   generated driver calls and rule-context client queries use the transaction
   driver.
4. Add runtime transaction requirement guardrails, including
   `RequiredForMultiWrite` and `RequiredForAllWrites`. These checks must run at
   the start of `save()`, before hooks or any driver reads/writes.
5. Add tests proving required/optional to-one semantics and hook/privacy/
   validation visibility.
6. Replace the generic many-to-many `.through(...)` API with explicit
   `throughLink(...)` and `throughEntity(...)` schema markers.
7. Extend write candidates or write contexts with typed edge changes.
8. Generate many-to-many update helpers for link-table edges with
   junction-table `add/remove/set` and `addId/removeId/setIds` on the single
   explicit `throughLink(...)` declaration only. All generated link-table helpers
   require a transaction-scoped client and owner-edge serialization.
   Generate them only for junction schemas that satisfy the
   payload-free/non-null-FK/generated-id/non-partial-unique-pair link-table V1
   constraints.
   Synthesized reverse edges remain traversal-only, explicit reverse
   `throughLink(...)` declarations are rejected, and through-entity edges remain
   repo-only.
9. Consider create-time many-to-many helpers once owner id availability and
   junction write ordering are specified for multi-write creates.

## Test Requirements

Before implementation, add tests for:

- required to-one entity assignment sets the FK
- to-one id assignment sets the FK without loading the target entity
- `belongsTo(...)` is required/non-null by default, while `.nullable()` /
  `.optional()` makes a to-one edge optional
- `belongsTo(...).field(handle)` rejects mismatches between relationship
  nullability and backing field nullability
- optional to-one `null` assignment clears the FK
- required to-one `null` assignment rejects during generated save preparation
- direct FK writes clear any cached entity reference
- generated saves do not open transactions implicitly on normal clients
- saves called through a transaction-scoped client create privacy and validation
  contexts backed by the transaction-scoped driver, while preserving the caller
  privacy context for privacy and System-scoped LOAD-privacy bypass for
  validation
- `TransactionRequirement.RequiredForMultiWrite` rejects qualifying multi-write
  saves outside a transaction at the start of `save()`, before hooks, privacy,
  validation, driver reads, or driver writes
- `TransactionRequirement.RequiredForMultiWrite` classifies any pending
  generated link-table M2M mutation as multi-write before hooks or normalization,
  even if it later produces no junction delta or owner-row update
- `TransactionRequirement.RequiredForAllWrites` rejects create/update/delete
  saves outside a transaction at the start of `save()`, before hooks, privacy,
  validation, driver reads, or driver writes
- link-table M2M `add`, `remove`, `set`, `addId`, `removeId`, and `setIds`
  require a transaction-scoped client and update the junction table
- link-table M2M entity arguments lower to target ids without reloading target
  rows, and ID variants never load target rows
- link-table M2M mutations do not evaluate target LOAD privacy just because a
  target entity or id appears in the mutation
- link-table M2M `add`, `remove`, `set`, `addId`, `removeId`, and `setIds`
  serialize per owner-edge relationship before reading current junction rows or
  mutating junction rows
- link-table M2M helpers throw `TransactionRequiredException` outside a
  transaction before hooks, privacy, validation, driver reads, or driver writes
- drivers that cannot support owner-edge serialization reject generated
  link-table M2M helpers at the start of `save()`, before hooks, privacy,
  validation, driver reads, or driver writes
- link-table M2M update returns `null` without reading or mutating junction rows
  when the owner row cannot be locked because it no longer exists
- link-table M2M updates use the locked current owner row, not a stale input
  entity passed to `update(entity)`, as the privacy/validation `before` state and
  fallback base for non-dirty scalar/FK fields
- link-table M2M before hooks run before owner-row lock/read, observe the
  hook-facing mutation state initialized from the input entity and pending
  builder changes, and do not observe locked current DB state
- edge-only link-table M2M updates run before hooks, owner write privacy,
  validation, after hooks, and return LOAD privacy once
- link-table M2M saves roll back owner updates and junction database writes when after
  hooks or returned LOAD privacy throw before the explicit transaction exits
- edge-only link-table M2M updates persist scalar/FK changes made by hooks but do
  not issue an empty owner-row update when no scalar/FK values changed
- edge-only link-table M2M updates apply owner `updateDefault` values, so an
  `updatedAt.updateDefaultNow()` field causes an owner-row update even when no
  user scalar field changed
- edge-only link-table M2M updates with no scalar/FK changes return the current
  owner row read inside the transaction after the owner lock, with edges unloaded
- before hooks receive hook-facing mutation interfaces that expose mutable
  scalar/FK fields and read-only `PendingEdgeOps`, but do not expose link-table
  M2M mutators
- M2M schemas use `throughLink(...)` or `throughEntity(...)`; codegen does not
  infer the mutation model from junction shape or runtime configuration
- explicit reverse `throughLink(...)` declarations for the same junction
  relationship are rejected in V1, while synthesized reverse edges inherit the
  relationship metadata and remain traversal-only
- explicit opposite-side `throughEntity(...)` traversal declarations for the
  same junction relationship are allowed when both sides use `throughEntity(...)`
- generated link-table M2M helpers are emitted only for the single explicit
  `throughLink(...)` declaration for a junction relationship
- `throughLink(...)` M2M helpers are rejected for junction schemas with payload
  columns, nullable source/target FKs, caller-provided ids, partial unique
  indexes, or missing non-partial unique source/target FK pairs
- generated link-table M2M helpers populate client-generated UUID junction ids
  before calling raw `Driver.insert(...)` / `insertMany(...)`
- `throughEntity(...)` M2M edges do not generate direct helpers and continue to
  be mutated through the junction repo
- direct link-table helpers do not invoke junction repo write rules
- edge mutation changes are visible to validation and privacy rules
- returned owner entities have normal unloaded edge state after link-table M2M
  saves; generated saves do not patch stale edge lists or implicitly load target
  entities
- `EdgeChanges` exposes set-valued `requestedSet`, `added`, and `removed`, with
  `added` and `removed` computed from current junction rows
- `EdgeChanges.requestedSet` reflects the final intended replacement set after
  the latest `set` / `setIds`
- mixed replacement and delta operations for the same link-table M2M edge are
  rejected at the incompatible mutator call or during start-of-save preflight
- hooks fire once for the owning entity mutation

## Open Questions

- Should edge changes live directly on `WriteCandidate`, or should privacy and
  validation contexts expose them separately from scalar candidates?

## Future Enhancements

- Bidirectional link-table write helpers could be added later if the schema DSL
  gains a concrete read-only/write-orientation marker or the driver/runtime gains
  a canonical edge-lock model. Any design must ensure helpers from both endpoint
  directions serialize on the same relationship key so exact `set(...)`
  semantics cannot race with reverse-direction `add(...)`, `remove(...)`, or
  `set(...)`.
