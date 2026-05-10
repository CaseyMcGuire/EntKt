# RFC: To-One Assignment And Nullability

## Status

Possible future feature. This is not implemented.

Split out from [Edge Mutation API](edge-mutation-api.md).

## Summary

Define the first, smallest edge mutation step: FK-owning `belongsTo` edges can
be written by entity setter method or by id, and relationship nullability becomes
required by default.

This RFC covers:

- `setAuthor(alice)`
- `authorId = aliceId`
- required-by-default `belongsTo(...)`
- `.nullable()`
- field-backed FK nullability matching
- mixed entity-setter/FK writes
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
    setAuthor(user)
}.save()
```

The ID-only path should remain available when the caller already has the target
id and does not want to load a full entity.

Examples use `.save()` as shorthand for the current generated save operation. If
the [Result Variants](entkt-result-variants-rfc.md) RFC is adopted, examples
should use `saveOrThrow()` for the throwing path or `saveOrError()` for
structured errors. `saveOrNull()` must not swallow privacy, validation,
constraint, transaction, or driver failures.

## Non-Goals

- Do not hide required edge validation.
- Do not bypass privacy or validation.
- Do not add cascading create for nested objects.
- Do not mutate inverse edges (`hasOne` / `hasMany`) from the non-FK-owning side
  in this RFC. Those relationships should be changed from the `belongsTo` side
  that owns the FK.
- Do not define many-to-many mutation helpers here.

## Proposed API

For FK-owning to-one edges, a generated setter method is the ergonomic entity
API:

```kotlin
client.posts.create {
    title = "Hello"
    setAuthor(alice)
}.save()
```

```kotlin
client.posts.update(post) {
    setAuthor(bob)
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
in an edge mutation. If an application requires "a caller may assign only targets
they can see", "targets must belong to the same tenant", or similar
relationship-write authorization, it must encode that rule in owner write privacy
or validation. EntKt does not treat target LOAD privacy as relationship-write
authorization.

## Relationship Nullability

The relationship nullability model should be required by default. A
`belongsTo<Target>(...)` edge should produce a non-null FK unless the schema
marks the relationship with `.nullable()`. The long-term public model should be
non-null by default and nullable only when explicitly requested. The old
`.required()` modifier and any `.optional()` alias should be removed from the API
or rejected by codegen; they should not remain as compatibility sugar.

Nullable to-one edges, declared with `.nullable()`, can be cleared by assigning
`null` through the generated setter method:

```kotlin
client.posts.update(post) {
    setAuthor(null)
}.save()
```

For nullable to-one edges, assigning `null` to the resolved FK property also
clears the edge:

```kotlin
client.posts.update(post) {
    authorId = null
}.save()
```

Required edge checks should continue to happen during generated save preparation
before privacy, validation, or database writes. For create, a required
relationship must have a final non-null FK after builder and hook writes. For
update, both required and nullable relationship fields use the input entity's
current FK as the fallback when the mutation does not touch the relationship, so
`update(post) { title = "x" }` does not require assigning the relationship again.
A nullable relationship is cleared only when the builder or hooks explicitly set
the entity setter method or resolved FK property to `null`. If an update changes
a required relationship to null, save preparation rejects it.

Required edge checks are treated like generated field shape checks: they validate
the local mutation payload and generated schema constraints, not database-visible
domain invariants. Owner privacy and configured validation still run after final
candidate construction.

## Public Types

Generated edge mutators should be typed according to schema nullability.
Required to-one edges should expose non-null entity setter methods and non-null
FK types, such as `setAuthor(user)` and `authorId: UUID`. Nullable to-one edges
should expose nullable entity setter methods and nullable FK types, such as
`setAuthor(null)` and `authorId: UUID?`.

Required create builders may use nullable internal staging state to represent
"not assigned yet", but `null` should not be part of the public assignment API
for required edges.

Entity setter methods are write-only commands on mutation builders. The resolved
FK property, such as `authorId`, is the readable/writable source of truth for
pending relationship state.

Documentation and examples should present entity setter methods as the ergonomic
to-one API and FK assignment as the ID-only variant. They should not introduce
relationship assignment properties on mutation builders.

## Explicit Backing Fields

Generated create/update builders should expose entity setter methods for
`belongsTo` edges. Setting by entity writes its id into the underlying FK
property:

```kotlin
client.posts.create {
    setAuthor(alice)
}.save()
```

They also expose the resolved FK property, either as the generated implicit FK
such as `authorId`, or as the user-declared field for
`belongsTo(...).field(handle)` edges:

```kotlin
setAuthor(alice)     // sets authorId = alice.id
authorId = alice.id  // writes the FK directly
setAuthor(null)      // .nullable() edge only; clears authorId
authorId = null      // .nullable() edge only; clears the FK directly
```

For a field-backed edge, the user-declared field is the id-only path:

```kotlin
class Post : EntSchema("posts") {
    val writerId = uuid("writer_id")
    val author = belongsTo<User>("author").field(writerId)
}
```

```kotlin
client.posts.create {
    setAuthor(alice)      // sets writerId = alice.id
    writerId = bob.id     // writes the FK directly; no authorId is generated
}.save()
```

For implicit FKs, the id-only path is the generated `{edge}Id` property. For
`belongsTo(...).field(handle)` edges, the id-only path is the user-declared
field property backing that edge. The implementation must not create a second
FK path.

For implicit FK edges, codegen must reject schemas where the generated FK
property name, such as `authorId`, collides with an existing field, edge,
generated method, or Kotlin member name. Callers should use
`belongsTo(...).field(handle)` to choose an explicit backing field when a
collision would occur.

For `belongsTo(...).field(handle)` edges, relationship nullability and backing
field nullability must match. Codegen should reject a required relationship
backed by a nullable field, and should reject a nullable relationship backed by
a non-null field. The edge declaration and field declaration should describe the
same database constraint instead of one side overriding the other.

The backing field type must also match the target schema id type. For example, a
`belongsTo<User>` whose target id is `UUID` may be backed only by a UUID field
with matching nullability. Codegen should reject mismatched backing field types.

## Mixed Entity-Setter And FK Writes

Entity setter methods and FK assignment are two public ways to write the same
pending relationship state. If a caller mixes them in one builder, to-one edges
use last-write-wins semantics over the underlying FK value:

```kotlin
authorId = alice.id       // writes authorId
setAuthor(bob)            // writes authorId = bob.id
authorId = carol.id       // writes authorId and clears cached author
setAuthor(null)           // nullable edge only; writes authorId = null
```

The final FK value after the create/update block and before hooks have run is
the value hooks initially observe. Hooks mutate a hook-facing scalar/FK mutation
view, not the public builder. For to-one relationships, that hook-facing view is
FK-only: it exposes the resolved FK property, such as `authorId`, or the
user-declared backing field for `belongsTo(...).field(handle)` edges. It does
not expose relationship entity setter methods, such as `setAuthor(alice)`, or
readable relationship entity properties. Hooks that need to assign a
relationship by entity should write the entity id into the resolved FK property.
Hook FK writes also follow last-write-wins before candidate construction. Hook,
privacy, and validation code should treat the final FK value and `WriteCandidate`
as the source of truth, not any cached entity reference that happened to be
assigned earlier in the builder lifecycle.

Hooks may clear nullable relationships by setting the hook-facing resolved FK
property to null. Hooks may not leave required relationships null: required edge
checks run after hooks and reject a final null FK before privacy, validation, or
database writes.

## Generated Builder Shape

For each mutable FK-owning to-one edge, generated create/update builders expose
an entity setter method and the resolved FK property. Both write through to the
same pending FK state.

Conceptually, for a required edge:

```kotlin
class PostCreate {
    private var cachedAuthor: User? = null
    private var resolvedAuthorFk: UUID? = null

    fun setAuthor(value: User) {
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

Generated builders must distinguish three pending states for nullable
relationships: unset, set to a non-null FK, and explicitly set to null. For
create, unset and explicitly set null both persist null unless defaults are added
later. For update, unset means keep the existing FK; explicitly set null means
clear the FK. Required relationships only need unset vs non-null.

If a caller writes the resolved FK property directly, the generated setter
should clear the cached entity reference because the builder no longer knows
which `User` instance, if any, matches the FK. The entity setter method sets both
the cached entity and the resolved FK. Calling the nullable entity setter with
`null`, or assigning `null` to the resolved FK, clears both for nullable edges.

## Save Pipeline

To-one edge mutations should be resolved before candidate construction:

1. the create/update builder block has already run before `save()`, so entity or
   id writes have already updated the pending FK state
2. generic start-of-save preflight runs, as defined by
   [Transaction And Locking Semantics](edge-mutation-transaction-locking-semantics.md)
3. before hooks run and observe the normalized pending FK value
4. hook FK writes can modify the same pending FK state with last-write-wins
   semantics
5. final scalar/FK values are computed and field validation plus required edge
   checks run
6. the write candidate includes the final FK value
7. privacy and validation run in the caller's client scope
8. the owner row is inserted or updated
9. after hooks and return LOAD privacy run

This avoids a second relationship-write phase for `belongsTo` edges.

## Returned Entity State

A to-one mutation returns the owner entity with scalar fields and FK fields
reflecting the saved owner row. Relationship edges remain in the normal unloaded
state. Calling `setAuthor(alice)` does not cause the returned `Post` to contain
`alice` in its loaded edge state.

## Candidate And Rule Visibility

`WriteCandidate` should continue to expose scalar fields and FK values. To-one
edge mutations need no additional candidate model because they lower to FK
values.

Privacy contexts keep the caller's privacy context. Validation contexts keep the
existing System-scoped LOAD-privacy bypass. Transaction-scoped context behavior
is covered in [Transaction And Locking Semantics](edge-mutation-transaction-locking-semantics.md).

## Rollout Plan

1. Document to-one entity setter methods as the ergonomic public API and FK
   assignment as the ID-only variant. Ensure both write through to the same
   resolved FK state.
2. Change relationship nullability to required by default for `belongsTo(...)`,
   with `.nullable()` as the explicit nullable relationship
   marker. Remove or reject the old `.required()` modifier.
3. Add tests proving required/nullable to-one semantics and hook/privacy/
   validation visibility.

## Test Requirements

Before implementation, add tests for:

- required to-one entity setter method sets the FK
- to-one id assignment sets the FK without loading the target entity
- `belongsTo(...)` is required/non-null by default, while `.nullable()` makes a
  to-one edge nullable
- `belongsTo(...).field(handle)` rejects mismatches between relationship
  nullability and backing field nullability
- `belongsTo(...).field(handle)` rejects backing field types that do not match
  the target schema id type
- implicit FK edges reject generated `{edge}Id` Kotlin member collisions and
  require `belongsTo(...).field(handle)` when callers need an explicit backing
  field name
- nullable to-one `null` assignment clears the FK
- nullable to-one update distinguishes unset from explicit null: unset preserves
  the existing FK, while explicit null clears it
- nullable to-one create allows unset and explicit null, both producing a null FK
- unset required to-one create rejects during generated save preparation
- hooks can set a required FK that the builder left unset before required edge
  validation runs
- hooks can clear a nullable FK by setting it to null
- hooks setting a required FK to null are rejected before privacy, validation, or
  database writes
- direct FK writes clear any cached entity reference
- reading the resolved FK property returns the pending FK value
- `setAuthor(alice)` does not evaluate target LOAD privacy and does not return
  `alice` as a loaded edge
- hooks observe the final FK value after builder writes and can mutate FK values
  through the hook-facing scalar/FK mutation view
- hook-facing to-one mutation views expose resolved FK fields only, not
  relationship entity setter methods or readable relationship entity properties
- to-one write candidates expose final FK values and do not require target
  entity loads
