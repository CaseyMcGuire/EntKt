# RFC: Many-To-Many Schema Modeling

## Status

Possible future feature. This is not implemented.

Split out from [Edge Mutation API](00-overview.md).

## Summary

Replace the generic many-to-many `.through(...)` marker with explicit schema
markers that describe the write model:

- `throughLink(...)`: the junction table is relationship storage
- `throughEntity(...)`: the junction row is a domain entity and remains the write
  API

This RFC defines the schema model and static safety constraints. It does not
define the helper method implementation or transaction/locking strategy.

## Motivation

Many-to-many junction schemas can represent very different concepts:

- a pure link table, such as `post_tags(post_id, tag_id)`
- a domain entity, such as `memberships(group_id, user_id, role, joined_at)`

Generating direct `tags.add(...)` helpers for both shapes is surprising. A
payload-bearing or rule-bearing junction should be mutated through its repo so
callers can provide payload, trigger hooks, and run junction privacy/validation.

The schema should state that modeling choice directly instead of relying on
runtime hook/privacy configuration or inferred column shape.

## Proposed API

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

## Write Orientation

For V1, a link-table M2M relationship may have only one explicit
`throughLink(...)` declaration per **relationship key**, where the
relationship key is the triple

```
(junction schema, source junction edge, target junction edge)
```

— the junction class and the two `belongsTo` property references the
caller passes as `sourceEdge` / `targetEdge` to `.throughLink<Junction>(...)`.
Keying on the specific junction edges (rather than just the source and
target schema types) is required for self-referential and multi-FK
junctions where both junction edges resolve to the same target schema:

- `Friendship` with `val requester = belongsTo<User>("requester")` and
  `val recipient = belongsTo<User>("recipient")` is one junction with two
  distinct edges both pointing at `User`. The
  `(Friendship::class, Friendship::requester, Friendship::recipient)` key
  is distinct from `(Friendship::class, Friendship::recipient, Friendship::requester)`.
- A junction with multiple FK columns to the same target type for
  different purposes (e.g., a `ProjectAssignment` with `assignee` and
  `reviewer` both pointing at `Pet`) likewise distinguishes relationships
  by the specific junction-edge pair.

That single declaration owns the write orientation and gets generated helpers.

A second `throughLink(...)` whose key is the same triple — including the
case where the source and target junction edges are swapped — is the
"explicit opposite-side declaration" rejected below. Two `throughLink(...)`
declarations whose keys differ in either junction edge (e.g.,
`Friendship::requester` / `Friendship::recipient` for one relationship and
`Friendship::recipient` / `Friendship::requester` for the reverse) are
treated as the **same relationship in opposite orientations** and the
second is rejected. Two declarations whose keys differ on one of the
junction-edge properties but describe genuinely different relationships
(e.g., `ProjectAssignment::project` / `ProjectAssignment::assignee` for
"assignees" and `ProjectAssignment::project` / `ProjectAssignment::reviewer`
for "reviewers") have distinct relationship keys and both are allowed.

**V1 does not synthesize a reverse traversal edge for `throughLink(...)`
relationships.** Codegen does not infer a read-only edge on the opposite-side
schema, the opposite-side `Edges` inner data class does not gain a
synthesized field for the relationship, and there is no eager-loading or
predicate surface for the reverse direction. Callers that need to traverse
the relationship from the opposite side query the junction schema directly
for V1. Reverse traversal for link-table relationships is deferred — see
"Future Enhancements" for the planned design.

Codegen must reject an explicit opposite-side `throughLink(...)` declaration for
the same relationship key in V1 — concretely, a second declaration whose
relationship key matches the first with `sourceEdge` / `targetEdge` swapped.
Without a concrete read-only marker or canonical reverse-write lock model,
explicit bidirectional link-table helpers would make the owner of the
relationship ambiguous and could reintroduce exact-set races between opposite
orientations.

For `throughEntity(...)`, callers mutate the junction entity through its repo, so
explicit opposite-side traversal declarations are allowed as long as both
declarations use `throughEntity(...)`. Codegen should still reject mismatches
between `throughLink(...)` and `throughEntity(...)` for the same junction
relationship.

By default, a `throughEntity(...)` declaration synthesizes a read-only reverse
traversal edge on the opposite-side schema (matching the prior
`manyToMany(...).through(...)` behavior — query traversal, eager loading,
predicate handle, no write helpers). When the opposite side explicitly
declares its own `throughEntity(...)` for the same junction, codegen
**suppresses the synthesized reverse edge for that relationship** — the
explicit declaration owns the traversal surface on its side, so duplicate
traversal handles aren't generated. The two explicit declarations must agree
on the junction schema and on the source/target property references (each
side's `sourceEdge` is the opposite side's `targetEdge`); codegen rejects
mismatched pairs at schema validation.

## Link-Table Safety

### Ref resolution

The two `KProperty1<Junction, BelongsToHandle<*>>` arguments to
`.throughLink<Junction>(sourceEdge, targetEdge)` are erased to
`BelongsToHandle<*>` at the type-system level, so the constraints below
are enforced as schema validation rules at codegen time, not by the
Kotlin compiler:

- both refs must resolve to non-null `belongsTo` edges declared on the
  junction schema; refs that don't reach a junction `belongsTo` (e.g.,
  point at a `hasMany`, a scalar field, or null) are rejected
- the two refs must be **distinct** junction edges; passing the same
  property reference twice (e.g., `Friendship::requester` for both
  `sourceEdge` and `targetEdge`) is rejected
- `sourceEdge` must resolve to a junction `belongsTo` whose target is
  the **declaring schema** (the schema declaring the `manyToMany`);
  rejected otherwise. For self-referential M2M (the declaring schema
  and the M2M target schema are the same), this collapses to "must
  target the shared schema."
- `targetEdge` must resolve to a junction `belongsTo` whose target is
  the **M2M target schema** (the type parameter of `manyToMany<Target>`);
  rejected otherwise

These ref-resolution rules apply equally to `throughEntity(...)` and
are checked at the same point in schema validation.

### Junction-shape rules

The safety rules below define what qualifies as a helper-eligible
`throughLink(...)` edge in V1. A junction schema is safe for direct edge mutation
only when:

- it contains exactly the junction id column plus the two FK columns. Extra
  payload columns are not safe in V1, even when nullable or defaulted, because
  generated create builders, not low-level `Driver.insert`, apply field defaults
- both junction `belongsTo` edges are non-null. Under the long-term schema model,
  this is the default; junction edges marked `.nullable()` are not safe for
  direct link-table helpers
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

If a relationship needs hooks, privacy, validation, or other write-time behavior
on the junction row itself, that relationship should be declared with
`throughEntity(...)` instead of `throughLink(...)`. Direct link-table helpers do
not run junction repo hooks, privacy, or validation.

## Relationship To Other RFCs

- [To-One FK Mutation And Nullability](02-to-one-assignment-nullability.md)
  defines required-by-default `belongsTo(...)`, which this RFC relies on for
  non-null junction FK semantics.
- [Link-Table M2M Mutation Helpers](05-link-table-helpers.md) defines
  the generated helper APIs for safe `throughLink(...)` edges.
- [Transaction And Locking Semantics](04-transaction-locking-semantics.md)
  defines the transaction and serialization requirements those helpers need.

## Rollout Plan

1. Replace the generic many-to-many `.through(...)` API with explicit
   `throughLink(...)` and `throughEntity(...)` schema markers.
2. Add static codegen validation for link-table safety.
3. Generate direct helpers only for the single explicit `throughLink(...)`
   declaration for a junction relationship.
4. Keep synthesized reverse link-table edges traversal-only in V1.
5. Keep through-entity edges repo-only.

## Test Requirements

Before implementation, add tests for:

- M2M schemas use `throughLink(...)` or `throughEntity(...)`; codegen does not
  infer the mutation model from junction shape or runtime configuration
- `throughLink(...)` / `throughEntity(...)` ref resolution rules are
  enforced as schema validation: refs that don't resolve to a junction
  `belongsTo` are rejected; the same prop ref passed for both
  `sourceEdge` and `targetEdge` is rejected; a `sourceEdge` whose
  target schema is not the declaring schema is rejected; a `targetEdge`
  whose target schema is not the M2M target schema is rejected
- a second `throughLink(...)` declaration whose relationship key
  `(junction schema, sourceEdge prop, targetEdge prop)` matches an
  existing declaration's key with `sourceEdge`/`targetEdge` swapped is
  rejected in V1 (covers self-referential junctions like
  `Friendship::requester` ↔ `Friendship::recipient` and multi-FK
  junctions where both sides resolve to the same target schema)
- two `throughLink(...)` declarations with distinct relationship keys —
  e.g., `(ProjectAssignment, project, assignee)` and
  `(ProjectAssignment, project, reviewer)` — describe genuinely
  different relationships and both are accepted
- V1 does not synthesize a reverse traversal edge for a `throughLink(...)`
  relationship: the opposite-side schema's generated `Edges` data class
  does not gain a synthesized field, no eager-loading scope is generated,
  and no predicate handle is exposed for the reverse direction
- explicit opposite-side `throughEntity(...)` traversal declarations for the
  same junction relationship are allowed when both sides use `throughEntity(...)`
- a `throughEntity(...)` declaration synthesizes a read-only reverse
  traversal edge on the opposite-side schema by default; when the
  opposite side explicitly declares its own `throughEntity(...)` for
  the same junction, the synthesized reverse is suppressed so users
  don't get duplicate traversal handles
- mismatched explicit `throughEntity(...)` pairs (different junction
  schema, or `sourceEdge`/`targetEdge` props that don't pair up
  symmetrically) are rejected at schema validation
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

## Future Enhancements

- **Read-only reverse traversal via an explicit marker.** V1 omits the
  reverse traversal edge for `throughLink(...)` relationships entirely.
  The eventual design is a third M2M DSL marker — working name
  `throughLinkInverse(...)` — that the opposite-side schema declares
  explicitly to opt into traversal, eager loading, and predicate
  handles for the reverse direction. The marker generates no
  `add(...)` / `remove(...)` / `set(...)` helpers, so the write
  orientation stays unambiguous. Sketch:

  ```kotlin
  class Post : EntSchema("posts") {
      val tags = manyToMany<Tag>("tags")
          .throughLink<PostTag>(PostTag::post, PostTag::tag)
  }

  class Tag : EntSchema("tags") {
      val posts = manyToMany<Post>("posts")
          .throughLinkInverse(Post::tags)   // read-only reverse
  }
  ```

  Conflict rules: declaring both an explicit reverse `throughLink(...)`
  *and* a `throughLinkInverse(...)` for the same junction is rejected;
  declaring `throughLinkInverse(...)` without the corresponding write
  orientation `throughLink(...)` is rejected; the inverse marker
  inherits all relationship metadata from the write side. Migration
  from V1 is purely additive — V1 callers gain reverse traversal by
  declaring the marker; existing schemas continue to work unchanged.

- **Bidirectional link-table write helpers** could be added later if the
  schema DSL gains a concrete read-only/write-orientation marker or the
  driver/runtime gains a canonical edge-lock model. Any design must
  ensure helpers from both endpoint directions serialize on the same
  relationship key so exact `set(...)` semantics cannot race with
  reverse-direction `add(...)`, `remove(...)`, or `set(...)`.
