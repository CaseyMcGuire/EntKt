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

## Link-Table Safety

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

## Future Enhancements

- Bidirectional link-table write helpers could be added later if the schema DSL
  gains a concrete read-only/write-orientation marker or the driver/runtime gains
  a canonical edge-lock model. Any design must ensure helpers from both endpoint
  directions serialize on the same relationship key so exact `set(...)`
  semantics cannot race with reverse-direction `add(...)`, `remove(...)`, or
  `set(...)`.
