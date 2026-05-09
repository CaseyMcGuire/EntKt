# RFC: To-One Assignment And Nullability

## Status

Possible future feature. This is not implemented.

Split out from [Edge Mutation API](edge-mutation-api.md).

## Summary

Define the first, smallest edge mutation step: FK-owning `belongsTo` edges can
be assigned by entity or by id, and relationship nullability becomes required by
default.

This RFC covers:

- `author = alice`
- `authorId = aliceId`
- required-by-default `belongsTo(...)`
- `.nullable()` / `.optional()`
- field-backed FK nullability matching
- mixed entity/FK writes
- hook, candidate, privacy, and validation visibility for to-one writes

Many-to-many schema modeling, link-table helpers, and multi-write transaction
semantics are covered by separate RFCs.

## Motivation

entkt already generates typed edge metadata and eager-loading APIs. Mutation
APIs still tend to expose storage details:

```kotlin
client.posts.create {
    authorId = user.id
}.save()
```

For FK-owning to-one edges, applications should be able to express the
relationship directly:

```kotlin
client.posts.create {
    title = "Hello"
    author = user
}.save()
```

The ID-only path should remain available when the caller already has the target
id and does not want to load a full entity.

## Non-Goals

- Do not hide required edge validation.
- Do not bypass privacy or validation.
- Do not add cascading create for nested objects.
- Do not mutate inverse edges (`hasOne` / `hasMany`) from the non-FK-owning side
  in this RFC. Those relationships should be changed from the `belongsTo` side
  that owns the FK.
- Do not define many-to-many mutation helpers here.

## Proposed API

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

Passing an entity never requires reloading it; the builder uses the entity id.
Passing an id never loads the target entity automatically. Target existence is
enforced by database constraints unless a validation rule explicitly checks it.
Target LOAD privacy is not evaluated just because a target entity or id appears
in an edge mutation.

## Relationship Nullability

The relationship nullability model should be required by default. A
`belongsTo<Target>(...)` edge should produce a non-null FK unless the schema
marks the relationship with `.nullable()` / `.optional()`. Existing
`.required()` calls can remain as compatibility no-ops or be removed by a
migration, but the long-term public model should be non-null by default and
nullable only when explicitly requested.

Optional to-one edges, declared with `.nullable()` / `.optional()`, can be
cleared by assigning `null`:

```kotlin
client.posts.update(post) {
    author = null
}.save()
```

For optional to-one edges, assigning `null` to the resolved FK property also
clears the edge:

```kotlin
client.posts.update(post) {
    authorId = null
}.save()
```

Required edge checks should continue to happen during generated save preparation
before privacy, validation, or database writes, so leaving a required-by-default
edge unset or clearing it fails the same way setting a required FK to null fails
today.

## Public Types

Generated edge mutators should be typed according to schema nullability.
Required to-one edges should expose non-null assignment types, such as
`author: User` and `authorId: UUID`. Optional to-one edges should expose nullable
assignment types, such as `author: User?` and `authorId: UUID?`.

Required create builders may use nullable internal staging state to represent
"not assigned yet", but `null` should not be part of the public assignment API
for required edges.

Documentation and examples should present entity assignment as the ergonomic
to-one API and FK assignment as the ID-only variant. They should not introduce
additional to-one helper methods.

## Explicit Backing Fields

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

## Mixed Entity And FK Writes

Entity assignment and FK assignment are two public ways to write the same
pending relationship state. If a caller mixes them in one builder, to-one edges
use last-write-wins semantics over the underlying FK value:

```kotlin
authorId = alice.id       // writes authorId
author = bob              // writes authorId = bob.id
authorId = carol.id       // writes authorId and clears cached author
author = null             // optional edge only; writes authorId = null
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

Conceptually, for a required edge:

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
property or the resolved FK clears both for optional edges.

## Save Pipeline

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

## Candidate And Rule Visibility

`WriteCandidate` should continue to expose scalar fields and FK values. To-one
edge mutations need no additional candidate model because they lower to FK
values.

Privacy contexts keep the caller's privacy context. Validation contexts keep the
existing System-scoped LOAD-privacy bypass. Transaction-scoped context behavior
is covered in [Transaction And Locking Semantics](edge-mutation-transaction-locking-semantics.md).

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
4. Add tests proving required/optional to-one semantics and hook/privacy/
   validation visibility.

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
- hooks observe the final FK value after builder writes and can mutate FK values
  through the hook-facing scalar/FK mutation view
- to-one write candidates expose final FK values and do not require target
  entity loads
