# RFC: Many-To-Many Schema Modeling

## Status

Implemented as the core many-to-many schema modeling baseline.

Specifically implemented:

- `manyToMany<Target>(...).throughLink<Junction>(sourceEdge, targetEdge)` and
  `throughEntity<Junction>(sourceEdge, targetEdge)` are the only ways to mark
  an M2M edge — the generic `.through(...)` form is gone.
- Schema finalization rejects malformed refs: identical source/target props,
  sourceEdge not targeting the declaring schema, targetEdge not targeting the
  `manyToMany<Target>` type parameter.
- Codegen rejects incompatible declarations with the same canonical
  relationship identity (junction + unordered junction-edge ref pair):
  mixed `throughLink` + `throughEntity` declarations and same-orientation
  aliases. Exactly pair-swapped declarations are allowed for both write
  models and are the supported bidirectional pattern.
- **No reverse-edge metadata or user-facing API is synthesized.**
  An entity's `SCHEMA.edges` map contains only the edges its own schema
  declares; nothing is injected on the target side of a `manyToMany`.
  Bidirectional traversal — `EdgeRef` on the entity, field on `Edges`,
  `queryX()`, `withX { }` — requires both schemas to declare the M2M
  explicitly with pair-swapped orientations. This keeps the generated
  surface explicit: adding a `manyToMany` on schema X never introduces
  metadata or methods on schema Y, even by string name.
- Forward query traversal works without synthesized reverse names. The
  generated `queryX(): TargetQuery` method lowers to a
  `Predicate.HasM2MEdgeFromShape(edgeName, sourceShape)` evaluated
  against each candidate target row; the runtime walks the junction
  backwards using the *source* schema's own forward-edge metadata, with
  no dependency on a reverse entry in the target's schema.
- `throughLink` junction-shape helper-eligibility is enforced at codegen
  time: payload columns, nullable junction FKs, missing
  `OnDelete.CASCADE`, write-time modifiers on FK backing fields, EXPLICIT id
  strategies, missing or partial unique-pair indexes, and missing
  per-direction leading indexes for two-sided declarations are all rejected
  with a message naming the failing rule.

Follow-up work was extracted into focused RFCs:

- [Through-Entity Nullable M2M Traversal](09-through-entity-nullable-m2m-traversal.md)
- [Symmetric Link-Table Edges](10-symmetric-link-table-writes.md)

Split out from [Edge Mutation API](../../possible-features/edge-mutation/00-overview.md).

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

Conceptually, the edge metadata should carry this choice as a sealed
type rather than an enum + nullable disambiguation fields. A sealed
model makes downstream codegen branching exhaustive (a future variant
can't be silently skipped), drops the "always populated in practice
but typed nullable" footgun on `sourceEdge` / `targetEdge`, and leaves
the door open for additional variants without
needing to add another enum case and another nullable-discriminator
field on `Through`:

```kotlin
sealed interface ManyToManyThrough {
    /**
     * The junction `EntSchema` (e.g., `PostTag` for `Post.tags`). Named
     * `junction` rather than `target` because in M2M edge metadata
     * "target" already means the M2M edge's *target schema* (the type
     * parameter of `manyToMany<Target>`, e.g., `Tag` for `Post.tags`),
     * and conflating the two names is a footgun. Codegen reads the
     * M2M edge target schema from `Edge.target`; this carries the
     * junction class separately.
     */
    val junction: EntSchema
    val sourceEdge: String
    val targetEdge: String

    data class LinkTable(
        override val junction: EntSchema,
        override val sourceEdge: String,
        override val targetEdge: String,
    ) : ManyToManyThrough

    data class ThroughEntity(
        override val junction: EntSchema,
        override val sourceEdge: String,
        override val targetEdge: String,
    ) : ManyToManyThrough
}
```

The existing `Through` data class (in `schema/.../Edge.kt`) is a
single-shape carrier that predates this RFC and currently has no
mutation-mode awareness. The implementation rollout (see "Rollout
Plan") replaces references to `Through` on `EdgeKind.ManyToMany` with
this sealed `ManyToManyThrough` so every codegen path that reads
M2M metadata is forced to branch on the variant.

Codegen `when (through) { … }` blocks should be exhaustive over the
sealed variants — if a future variant is added, the compiler flags every
branching site that needs an opinion on the new mode rather than letting
it silently fall through.

## Write Orientation

M2M identity in this RFC works at two levels:

1. The **orientation key** is the ordered triple

   ```
   (junction schema, source junction edge, target junction edge)
   ```

   — the junction class and the two `belongsTo` property references the
   caller passes as `sourceEdge` / `targetEdge` to
   `.throughLink<Junction>(...)`. It's what the caller actually writes
   down, and order matters: it identifies the source endpoint for that
   declaration's traversal and mutation helpers.

2. The **canonical relationship identity** is the orientation key
   normalized so the two junction edge refs are *unordered*. Two
   orientation keys whose junction class matches and whose junction
   edge refs are the same set — regardless of which is `sourceEdge`
   and which is `targetEdge` — describe the same relationship in
   opposite orientations.

Keying on the specific junction edges (rather than just the source and
target schema types) is required for self-referential and multi-FK
junctions where both junction edges resolve to the same target schema:

- `Friendship` with `val requester = belongsTo<User>("requester")` and
  `val recipient = belongsTo<User>("recipient")` is one junction with
  two distinct edges both pointing at `User`. Orientation keys
  `(Friendship::class, requester, recipient)` and
  `(Friendship::class, recipient, requester)` are *distinct
  orientations* of the *same canonical relationship identity*.
- A junction with multiple FK columns to the same target type for
  different purposes (e.g., a `ProjectAssignment` with `assignee` and
  `reviewer` both pointing at `Pet`) hosts multiple canonical
  relationship identities: `{ProjectAssignment, project, assignee}` and
  `{ProjectAssignment, project, reviewer}` are distinct identities,
  not just two orientations of the same relationship.

A link-table M2M relationship may have one explicit `throughLink(...)`
declaration, or two declarations whose orientation keys are exactly
pair-swapped. Every declared side gets read traversal and direct
`add` / `remove` / `set` helpers. Two same-orientation aliases and
three or more declarations for one canonical relationship identity
are rejected.

Two `throughEntity(...)` declarations with *distinct* canonical
identities over the same junction — e.g.,
`(ProjectAssignment, project, assignee)` and
`(ProjectAssignment, project, reviewer)` over a `ProjectAssignment`
junction with `project`, `assignee`, *and* `reviewer` `belongsTo`
edges — describe genuinely different relationships and both are
allowed. **For `throughLink(...)` this combination is unreachable**:
the Junction-shape rules below restrict a helper-eligible
`throughLink(...)` junction to exactly the id and the two named FK
columns, so a junction that would host distinct canonical identities
necessarily has belongsTo edges beyond the named pair, which the
extra-belongsTo rule rejects. A relationship that wants distinct
canonical identities over one junction must therefore declare both
sides as `throughEntity(...)` and mutate the junction through its
generated repo.

**Codegen synthesizes no reverse traversal edge for any M2M.** Codegen does not
infer an edge on the opposite-side schema, the opposite-side
`Edges` inner data class does not gain a synthesized field, and there is
no eager-loading or predicate surface for the reverse direction. The rule
applies to both `throughLink(...)` and `throughEntity(...)` (see
"No reverse-edge synthesis" below for the `throughEntity` case and the
forward-traversal mechanism that lets queries work without it). Reverse
traversal requires an explicit pair-swapped declaration. For
`throughLink(...)`, that declared reverse edge is writable; see
[Symmetric Link-Table Edges](10-symmetric-link-table-writes.md).

For `throughEntity(...)`, callers mutate the junction entity through its repo, so
explicit opposite-side traversal declarations are allowed as long as both
declarations use `throughEntity(...)`. Codegen should still reject mismatches
between `throughLink(...)` and `throughEntity(...)` for the same junction
relationship.

**No reverse-edge synthesis.** A `throughEntity(...)` declaration produces
a traversal surface (`EdgeRef`, `Edges` field, `queryX()`, `withX { }`) only
on its own declaring schema. Codegen does not synthesize a reverse-edge entry
on the M2M target's schema, neither in the runtime `SCHEMA.edges` map nor as
a Kotlin-visible surface. Bidirectional traversal requires the opposite-side
schema to declare its own pair-swapped `throughEntity(...)` explicitly. This
keeps the generated surface explicit: adding a `manyToMany` on schema X never
introduces metadata or methods on schema Y, even by string name.

Forward query traversal still works without any reverse metadata. The
generated `queryX(): TargetQuery` method lowers to a
`Predicate.HasM2MEdgeFromShape(forwardEdgeName, sourceShape)` evaluated
against each candidate target row; the runtime walks the junction backwards
using the *source* schema's own forward-edge metadata. No reverse-edge entry
is needed on the target schema for the predicate to resolve.

**Self-referential `throughEntity(...)`: requires explicit pair-swap for
bidirectional traversal.** When the declaring schema and the M2M target
schema are the same, no reverse synthesis applies (consistent with the
no-synthesis rule above). Callers that need bidirectional traversal for a
self-referential relationship declare both edges explicitly on the same
schema with pair-swapped orientation keys:

```kotlin
class User : EntSchema("users") {
    val following = manyToMany<User>("following")
        .throughEntity<Follow>(Follow::follower, Follow::followed)
    val followers = manyToMany<User>("followers")
        .throughEntity<Follow>(Follow::followed, Follow::follower)
}
```

The two declarations are the two orientations of the same canonical
identity (`{Follow, follower, followed}` as an unordered pair). Each
declaration produces its own traversal surface, and Phase 3's matching
rule below treats them as opposite sides of one canonical relationship
(used for codegen validation, not synthesis).

**Matching rule for two explicit declarations.**
Codegen treats two explicit declarations as opposite sides of the same
relationship when, and only when:

1. They reference the **same junction schema** (same `KClass`).
2. They reference the **same two junction `belongsTo` edges**, in
   opposite order — concretely, side A's `(sourceEdge, targetEdge)`
   orientation key is `(X, Y)` and side B's orientation key is
   `(Y, X)` for the same two junction-edge property references.

If both conditions hold, the two declarations describe the same
relationship from the two endpoints. Each side independently produces
its own forward traversal surface; the matching rule does not synthesize
or merge metadata. Pair-swapped declarations are accepted for both
`throughEntity(...)` and `throughLink(...)`; each declared `throughLink`
side also gets its own mutation helpers.

**Scope of the matching rule.** The pair-swap check applies *only* when
the two declarations share a canonical relationship identity — same
junction class plus the same junction-edge ref pair as an unordered
set. Declarations with **distinct canonical relationship identities**
are independent and may coexist freely: no matching is attempted
between them, no rejection fires, and each side produces its own
forward traversal surface independently.

For example, on `Project`, two relationships over the same junction
schema (`ProjectAssignment` with `project` / `assignee` / `reviewer`
edges) are perfectly fine:

```kotlin
val assignees = manyToMany<User>("assignees")
    .throughEntity<ProjectAssignment>(
        ProjectAssignment::project, ProjectAssignment::assignee
    )
val reviewers = manyToMany<User>("reviewers")
    .throughEntity<ProjectAssignment>(
        ProjectAssignment::project, ProjectAssignment::reviewer
    )
```

The two declarations share the junction class but have different
unordered junction-edge ref pairs (`{project, assignee}` vs
`{project, reviewer}`), so their canonical identities are distinct.
Codegen doesn't try to match them as opposites and doesn't reject
them — they describe two genuinely independent relationships.

Concretely:

- **Same canonical identity, orientation keys pair-swap** → accepted
  as opposite sides of one canonical relationship; each side keeps
  its own forward traversal surface.
- **Same canonical identity, identical orientation key** → rejected
  as same-orientation alias (see "Same-orientation aliases are
  rejected" below).
- **Distinct canonical identities** → independent; no matching, no
  rejection, both allowed.

The same matching rule applies to `throughLink(...)`:

- **Same canonical identity, orientation keys pair-swap** → second
  declaration accepted; both declared sides are writable.
- **Same canonical identity, identical orientation keys** → second
  declaration rejected as a same-orientation alias (see
  "Same-orientation aliases are rejected" below).
- **Distinct canonical identities** → declarations describe genuinely
  different relationships and both are allowed; no matching attempted,
  no rejection.

**Same-orientation aliases are rejected.** V1 also rejects multiple
explicit `throughEntity(...)` declarations whose canonical relationship
identity *and* orientation key are identical — i.e., two `manyToMany(...)`
declarations on the same schema that pass the same junction class and
the same `(sourceEdge, targetEdge)` prop pair under different
`manyToMany` names. Example:

```kotlin
class Group : EntSchema("groups") {
    val members = manyToMany<User>("members")
        .throughEntity<Membership>(Membership::group, Membership::user)
    val users = manyToMany<User>("users")
        .throughEntity<Membership>(Membership::group, Membership::user)
}
```

Both declarations have orientation key
`(Membership, Membership::group, Membership::user)` and would generate
two separate traversal surfaces over the *same* relationship in the
*same* direction. V1 rejects this — alias traversal names over the
same relationship require a separate future alias design, mirroring
the same restriction RFC #2 places on
`belongsTo(...).field(handle)` (a single backing field may back at
most one edge). The same alias-rejection rule applies to
`throughLink(...)` for completeness: two same-orientation
`throughLink(...)` declarations on the same schema are rejected even
though the pair-swap rule wouldn't catch them.

## Link-Table Safety

### Ref resolution

The two `KProperty1<Junction, BelongsToHandle<*>>` arguments to
`.throughLink<Junction>(sourceEdge, targetEdge)` are erased to
`BelongsToHandle<*>` at the type-system level, so the constraints below
are enforced as schema validation rules at codegen time, not by the
Kotlin compiler:

- both refs must resolve to a `belongsTo` edge declared on the junction
  schema; refs that don't reach a junction `belongsTo` (e.g., point at
  a `hasMany`, a scalar field, or a null property handle) are rejected.
  This is a resolution check on the property reference itself, not a
  statement about FK nullability — see the separate non-null-FK rule
  under "Junction-shape rules" below for the helper-eligibility constraint
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

**Scope note: non-null junction FKs.** The "junction `belongsTo` edges
must be relationship-required (no `.nullable()`)" constraint in
"Junction-shape rules" below is a **`throughLink(...)` helper-eligibility
rule only**, not a general M2M traversal invariant. `throughEntity(...)`
relationships may declare junction `belongsTo` edges as `.nullable()`
when the domain allows partial junction rows — callers mutate the
junction through its repo, where nullable FKs interact with the
normal create/update builder semantics. The direct-driver link-table
helpers, in contrast, cannot reason about nullable junction FKs
without a separate spec for "what does add(...) / remove(...) /
set(...) mean when an endpoint may be null", so `throughLink(...)`
restricts to non-null junction FKs in V1.

**Traversal semantics with nullable junction FKs.** When a
`throughEntity(...)` relationship's junction `belongsTo` is
`.nullable()` and a row has `NULL` on the source or target FK,
generated M2M traversal and eager loading use **inner-join
semantics** and skip those rows — they are not part of the
relationship for query purposes. Concretely, a row in the junction
with `source_id = NULL` doesn't show up when traversing from any
source row (there's no source to traverse from), and a row with
`target_id = NULL` doesn't show up when eager-loading the target
collection from a given source. Predicate handles
(`Junction.<sourceEdge>.has(...)` and the like) treat the null FK
as "doesn't participate" rather than "participates in every match".
Callers that need to operate on partial junction rows query the
junction schema directly through its repo. This mirrors how nullable
FK columns are treated in vanilla SQL JOINs.

#### Worked example

For

```kotlin
class Post : EntSchema("posts") {
    val tags = manyToMany<Tag>("tags")
        .throughLink<PostTag>(PostTag::post, PostTag::tag)
}
```

schema validation requires:

- `PostTag::post` resolves to a `belongsTo<Post>` edge declared on
  `PostTag` (the junction schema). The target type `Post` matches the
  declaring schema (`manyToMany<Tag>` is declared on `Post`).
- `PostTag::tag` resolves to a `belongsTo<Tag>` edge declared on
  `PostTag`. The target type `Tag` matches the M2M target schema
  (the type parameter of `manyToMany<Tag>`).
- `PostTag::post` and `PostTag::tag` are distinct junction edges (not
  the same property reference passed twice).

For a self-referential or multi-role junction (e.g., `Friendship` with
two `belongsTo<User>` edges named `requester` and `recipient`), the
declaring and target schemas are the same `User`. The rule still
holds: both refs target `User`, but the **distinct-edges** check is
what tells `Friendship::requester` and `Friendship::recipient` apart
and prevents a degenerate
`throughLink<Friendship>(Friendship::requester, Friendship::requester)`.

### Self-referential M2M

V1 `throughLink(...)` supports **directed** self-referential
relationships when the source and target junction edges are distinct.
Example:

```kotlin
class Follow : EntSchema("follows") {
    val follower = belongsTo<User>("follower")
    val followed = belongsTo<User>("followed")
}

class User : EntSchema("users") {
    val following = manyToMany<User>("following")
        .throughLink<Follow>(Follow::follower, Follow::followed)
}
```

`(a, b)` and `(b, a)` are treated as distinct directed edges (a follows b
is not the same as b follows a). The pair-uniqueness check from
"Junction-shape rules" enforces this at the database level on the
ordered column pair.

**Undirected or symmetric self-referential relationships** (e.g.,
mutual friendship where `(a, b)` and `(b, a)` should be the same edge)
are **not special-cased in V1.** Codegen does not canonicalize
`(a, b)` / `(b, a)`, will not deduplicate them, and does not generate
helpers that swap the pair to a canonical orientation.

Three valid modeling choices for symmetric relationships in V1,
chosen by which write surface the caller plans to use:

- **`throughEntity(...)`** — store both `(a, b)` and `(b, a)` rows
  (or a canonical single row), and let application-level validation /
  hooks on the junction repo maintain the symmetry invariant. Because
  callers mutate through the junction repo, junction `beforeCreate` /
  `beforeUpdate` hooks fire and can canonicalize or reject
  non-canonical pairs.
- **`throughLink(...)` with caller-side canonicalization** — generated
  helpers are owner-source: `client.users.update(a.id) { friends.add(b.id) }`
  writes a junction row with `source = a.id, target = b.id` regardless of
  which id is smaller. To keep storage canonical (e.g., always
  `source = min(id), target = max(id)`), the caller has to **route the
  write through the canonical-source side** — pick the smaller-id user
  as the `update(...)` target before calling `friends.add(...)`. The
  helper itself can't swap the source: it's pinned to the row being
  updated.

  This enforces a **storage invariant only**: the database ends up
  with exactly one canonical row per friendship. It does **not** make
  traversal symmetric — `client.users.update(largerId).friends` traverses
  only rows where `largerId` is the source, so from that user's
  perspective the friendship doesn't appear under `friends` unless they
  also query the inverse direction (or the junction directly). Callers
  that want to read "all friendships involving user X" must either
  consult the junction schema's repo directly or do two traversals
  (`x.friends` plus a query against the junction for rows where X is
  the target). Junction hooks **do not fire** on this path (see
  "Link-table helpers bypass the junction repo" below), so
  canonicalization has to happen in the caller, not via a hook.
- **`throughLink(...)` with a database `CHECK` constraint** —
  enforce the canonical ordering at the database level (e.g.,
  `CHECK (follower_id < followed_id)` in the junction's DDL). The
  constraint rejects non-canonical inserts at the driver layer
  regardless of which write surface the caller used. Pair this with
  caller-side canonicalization so `throughLink(...)` helpers don't
  hit the constraint on every misordered call.

What is **not** a viable option for symmetric self-referential
`throughLink(...)`: a junction `beforeCreate` hook. Junction hooks
don't run on the direct-driver path used by `throughLink(...)`
helpers, so a hook that canonicalizes
`(follower_id, followed_id)` would silently fail to fire for every
helper-driven write. If the design depends on a hook, the
relationship belongs in `throughEntity(...)`.

A future RFC may add first-class symmetric-relationship support
(canonicalization, single-row uniqueness, swap-aware traversal).
Until then, V1 treats every link-table relationship as directed.

### Junction-shape rules

The safety rules below define what qualifies as a helper-eligible
`throughLink(...)` edge in V1. A junction schema is safe for direct edge mutation
only when:

- it contains exactly the junction id column plus the two FK columns.
  **Any declared scalar field other than the junction id and the two
  source/target FK columns counts as a payload column** and makes
  `throughLink(...)` invalid. Specifically rejected:
  - nullable fields (e.g., `val nickname = string("nickname").nullable()`)
  - defaulted fields (`.default(...)`, `.defaultNow()`)
  - timestamp / audit fields (`createdAt`, `updatedAt`, `deletedAt`)
  - soft-delete markers, version fields, optimistic-concurrency tokens
  - role / kind / status discriminators
  - any mixin-provided field (e.g. `include(::Timestamps)` adds
    timestamp fields that count as payload here)
  - field-backed FK columns whose backing field carries any of the
    above (e.g. a `comment`-only field is allowed because it doesn't
    add a column, but a `string("note")` column is not)

  Generated create builders apply field defaults, run before-create
  hooks, evaluate validation rules, and so on — none of which fire
  on the low-level `Driver.insert(...)` / `insertMany(...)` paths
  used by the link-table helpers, so any "harmless" payload column
  silently bypasses its declared semantics. The cleaner answer is to
  reject and direct the schema author to `throughEntity(...)`.
- both junction `belongsTo` edges are non-null. Under the long-term schema model,
  this is the default; junction edges marked `.nullable()` are not safe for
  direct link-table helpers
- the two source/target FK backing fields carry no write-time
  modifiers. Specifically rejected on a `throughLink(...)` junction's
  source/target FK columns:
  - field-level validators (`.positive()`, `.min(...)`,
    `.maxLength(...)`, `.match(...)`, etc.)
  - `.sensitive()`
  - `.default(...)` / `.defaultNow()`
  - `.updateDefault(...)` / `.updateDefaultNow()` (already rejected
    by the general `belongsTo(...).field(handle)` backing-FK rule
    from RFC #2, restated here for completeness)
  - `.immutable()` (`throughLink(...)` writes FK columns directly via
    the driver and never updates them after insert; declaring the
    backing field immutable adds nothing on this path and is rejected
    to keep the allowed modifier list mechanical)

  Allowed on the FK backing fields:
  - `.comment(...)` — pure metadata, doesn't affect write semantics.
  - `.field(...)` storage column rename (`uuid("alt_name")`) — the
    physical column name, not a modifier.

  Reasoning: `throughLink(...)` helpers bypass the junction repo
  entirely (see "Link-table helpers bypass the junction repo" below),
  so junction CREATE validation, defaults, hooks, and update-default
  resolution never fire. A `.positive()` on `post_id` would silently
  not run when the helpers insert junction rows. Rejecting these
  modifiers at schema validation surfaces the gap at build time
  instead of leaving the marker silently inert. Junctions that need
  any of those behaviors should be modeled with `throughEntity(...)`,
  where the junction repo runs validation/defaults/hooks on the
  normal builder paths.
- both junction `belongsTo` edges declare `OnDelete.CASCADE`
  **explicitly**. Pure link-table junction rows are meaningless once
  either endpoint is gone — leaving them around contradicts the
  relationship's set semantics and breaks the pair-uniqueness
  invariant for the next insert. `OnDelete.RESTRICT` is rejected
  because it would block endpoint deletion until the caller manually
  drained the link rows (a foot-gun for "delete this user" callers);
  `OnDelete.SET_NULL` is already rejected indirectly via the non-null
  junction-FK rule above. Junctions with a different deletion policy
  should be modeled with `throughEntity(...)`, where the caller
  mutates the junction through its repo and can encode any policy
  explicitly.

  **Concurrency note.** The `OnDelete.CASCADE` requirement also has a
  concurrency consequence for the link-table helpers: a concurrent
  endpoint delete on a target that a `tags.set(...)` or `tags.add(...)`
  call has just read can silently remove the corresponding junction
  row via the cascade, between the helper's junction read and write.
  Owner-edge serialization protects the owner row but not the target
  rows, so the helper's "the relationship set equals the requested
  set" guarantee is scoped to interleavings among generated M2M
  helpers, not against endpoint cascades. See
  [Link-Table M2M Mutation Helpers — Target Loading And Existence](05-link-table-helpers.md)
  for the full discussion.

  **Note on "explicit".** Codegen reads
  `EdgeKind.BelongsTo.onDelete` and accepts the helper-eligibility
  check only when that field equals `OnDelete.CASCADE`. If EntKt's
  framework-level default for a `belongsTo` without an `.onDelete(...)`
  call is something other than an explicit `OnDelete.CASCADE` value
  in the resolved metadata (e.g., null / `RESTRICT` / a sentinel
  "framework default"), the schema author must declare
  `.onDelete(OnDelete.CASCADE)` explicitly on each junction
  `belongsTo` for the junction to qualify. This avoids ambiguity over
  whether "unset" should be treated as `CASCADE` — the helper rule is
  literal: the resolved `onDelete` value must be `OnDelete.CASCADE`.
- its id strategy can be satisfied without caller input, such as auto numeric
  ids or client-generated UUIDs. Junction schemas with explicit caller-provided
  ids, such as `EntId.string()`, are not safe for direct helpers unless a later
  design defines how edge mutators supply those ids
- it declares a non-partial unique composite index or constraint on
  **exactly the source and target FK columns**, in either order:
  `(source_fk, target_fk)` or `(target_fk, source_fk)`. The constraint
  must contain only those two columns; an index that adds a third column
  (even one that's deterministically set, like a `kind` discriminator)
  does not enforce uniqueness of the pair alone and is not sufficient.
  The constraint must also be non-partial — a `WHERE` clause filtering
  out some rows would not reject duplicate links outside the indexed
  region. Normalized set semantics require the database to reject
  duplicate links under concurrent writers and to rule out preexisting
  duplicate link rows.

  Generated helpers and traversal also look up all links for the
  declaration's source FK. For a relationship declared from both
  endpoints, the junction must therefore have a non-partial index
  leading with each side's source FK. The unique pair index can satisfy
  one direction; the other can use the pair index in the opposite order
  or a separate non-unique leading-column index. A lone declaration does
  not require the companion index.

  **Column resolution.** The two columns named here are the **physical
  backing FK columns** after `belongsTo(...).field(handle)` resolution
  (the `field` value on `EdgeKind.BelongsTo`, defaulting to
  `${edgeName}_id`), not the junction edge identifiers. A junction
  edge declared as
  `val source = belongsTo<Post>("source").field(postId)` contributes
  `post_id` (the backing column), not `source_id`.

  **Scope of "exactly".** "Exactly the source and target FK columns"
  describes the qualifying unique index itself. It is not a statement
  about the whole junction schema's index set: the junction may declare
  additional indexes as long as one qualifying unique pair index exists.
  Duplicate-shape indexes remain subject to the normal schema
  index-validation rules (duplicate index names, duplicate column-set +
  uniqueness + where triples).

For junction schemas whose id strategy is client-generated UUID, generated
link-table M2M helpers must populate the junction `id` with a freshly generated
UUID before calling `Driver.insertIgnore(...)`. Auto-generated
database ids may be omitted when the driver/database owns id generation.

If a junction schema carries payload such as `role`, `joinedAt`, or other domain
data, the edge should be declared with `throughEntity(...)`. V1 should reject
`throughLink(...)` for that edge instead of silently omitting direct collection
mutators. Callers should mutate the junction schema through its generated repo,
where they can provide the payload explicitly and get the normal defaulting
behavior.

If a relationship needs hooks, privacy, validation, or other write-time behavior
on the junction row itself, that relationship should be declared with
`throughEntity(...)` instead of `throughLink(...)`.

**`throughLink(...)` helpers bypass the junction repo entirely.** They
write junction rows directly through the driver
(`Driver.insert(...)` / `insertMany(...)` / `delete(...)` /
`deleteMany(...)`) and **must not** call the junction's generated
`{Junction}Create` / `{Junction}Update` / `{Junction}.deleteById`
builders. As a consequence, link-table helpers do not run any of:

- junction field defaults (`.default(...)`, `.defaultNow()`,
  `updateDefault(...)`)
- junction `beforeSave` / `beforeCreate` / `beforeUpdate` /
  `beforeDelete` / `afterCreate` / `afterUpdate` / `afterDelete` hooks
- junction CREATE / UPDATE / DELETE privacy rules
- junction CREATE / UPDATE validation rules
- junction LOAD privacy on returned values
- the requested-patch / effective-patch / write-candidate machinery
  generated for the update path

If caller code needs any of the above on the junction row itself, the
schema must declare the relationship with `throughEntity(...)` and
mutate through the junction repo. The junction-shape rules above
(`exactly the junction id plus the two FK columns`,
`OnDelete.CASCADE`, etc.) exist precisely to keep `throughLink(...)`
junctions free of state that would silently be skipped on the
direct-driver path.

## Relationship To Other RFCs

- [To-One FK Mutation And Nullability](02-to-one-assignment-nullability.md)
  defines required-by-default `belongsTo(...)`, which this RFC relies on for
  non-null junction FK semantics.
- [Link-Table M2M Mutation Helpers](05-link-table-helpers.md) defines
  the generated helper APIs for safe `throughLink(...)` edges.
- [Transaction And Locking Semantics](04-transaction-locking-semantics.md)
  defines the transaction and serialization requirements those helpers need.

## Rollout Plan

1. **Remove the generic `.through(...)` marker.** It is replaced by the
   explicit `throughLink(...)` and `throughEntity(...)` markers; there
   is no alias from `.through(...)` to either form. The whole point of
   the new markers is to force the schema author to choose the write
   model — silently defaulting to one or the other would defeat the
   purpose. If `.through(...)` still exists on the builder during a
   migration window, schema validation must reject it with a diagnostic
   directing callers to choose either `throughLink(...)` (junction is
   pure relationship storage) or `throughEntity(...)` (junction carries
   payload, hooks, privacy, or validation). Once the migration window
   closes, the method is deleted from `ManyToManyBuilder`.
2. Add static codegen validation for link-table safety (junction-shape
   rules + ref-resolution rules from "Link-Table Safety").
3. Generate direct helpers for every explicit `throughLink(...)`
   declaration for a junction relationship.
4. V1 does not synthesize a reverse traversal edge for any M2M, regardless
   of write model (see "Write Orientation" → "No reverse-edge synthesis").
   Bidirectional traversal requires the opposite-side schema to declare
   its own pair-swapped M2M. Pair-swapped `throughLink(...)` declarations
   are covered by the follow-up
   [Symmetric Link-Table Edges](10-symmetric-link-table-writes.md).
5. Keep through-entity edges repo-only for write paths. Forward query
   traversal lowers to `Predicate.HasM2MEdgeFromShape` against the source
   schema's forward-edge metadata, so no target-side reverse entry is
   needed at runtime.

## Test Requirements

Before implementation, add tests for:

- M2M schemas use `throughLink(...)` or `throughEntity(...)`; codegen does not
  infer the mutation model from junction shape or runtime configuration
- the generic `.through(...)` marker is removed and not aliased to
  either explicit form; if it survives during a migration window,
  schema validation rejects it with a diagnostic naming the two
  replacements (`throughLink(...)` vs `throughEntity(...)`) and a
  one-line summary of how to choose
- `throughLink(...)` / `throughEntity(...)` ref resolution rules are
  enforced as schema validation: refs that don't resolve to a junction
  `belongsTo` are rejected; the same prop ref passed for both
  `sourceEdge` and `targetEdge` is rejected; a `sourceEdge` whose
  target schema is not the declaring schema is rejected; a `targetEdge`
  whose target schema is not the M2M target schema is rejected
- the non-null-junction-FK rule applies to **`throughLink(...)` only**.
  A `throughLink(...)` declaration over a junction whose `belongsTo`
  edges are `.nullable()` is rejected as helper-ineligible; the same
  junction shape under `throughEntity(...)` is accepted (callers
  mutate through the junction repo, which handles nullable FKs via
  the normal builder semantics)
- when a `throughEntity(...)` junction `belongsTo` is `.nullable()`
  and a row has NULL on the source or target FK, generated M2M
  traversal, eager loading, and predicate handles use inner-join
  semantics: the row is skipped (it doesn't traverse from anywhere
  and doesn't appear in any source's target collection). Test fixture
  inserts both a fully-populated junction row and one with each FK
  set to NULL, then asserts traversal returns only the populated row
  pair
- a second `throughLink(...)` declaration for the same canonical
  relationship is accepted only when its orientation is exactly
  pair-swapped. Each declared side gets traversal and
  `add` / `remove` / `set`; same-orientation aliases and three or more
  declarations are rejected
- two `throughLink(...)` declarations with **distinct canonical
  identities** — e.g., `{ProjectAssignment, project, assignee}` and
  `{ProjectAssignment, project, reviewer}` (different junction-edge
  ref pairs) — describe genuinely different relationships and both are
  accepted
- directed self-referential `throughLink(...)` works (`User.following`
  via `Follow::follower` / `Follow::followed`) — `(a, b)` and `(b, a)`
  are distinct rows; pair-uniqueness applies to the ordered pair
- V1 does not auto-canonicalize symmetric self-referential pairs;
  generated helpers treat every link-table relationship as directed.
  Symmetric relationships are modeled either as `throughEntity(...)`
  with application-level invariants or as directed `throughLink(...)`
  with caller-side canonicalization (e.g., always
  `(min(id), max(id))`)
- no auto-synthesized reverse-edge entries on the target side of any
  M2M (regardless of write model). Test asserts that the M2M target's
  generated `SCHEMA.edges` map contains no `${sourceTable}_${forwardEdgeName}`
  entry; that the target's `Edges` inner data class has no
  reverse-side field; and that no `EdgeRef` / `queryX()` / `withX { }`
  is emitted on the target's companion / query class
- forward query traversal lowers to `Predicate.HasM2MEdgeFromShape(
  forwardEdgeName, sourceShape)` against the candidate target row (at the
  time of this RFC, the predicate-only `HasM2MEdgeFrom`; upgraded by the
  shape-preserving traversal RFC); the runtime walks the junction
  backwards using the source schema's own forward-edge metadata. Test
  asserts the generated `queryX()` body emits `HasM2MEdgeFromShape`
  (not `HasEdgeWith` against a synthesized reverse name) and that the
  runtime returns the right targets when the source-side filter matches
  a subset of source rows
- explicit opposite-side declarations for the same junction relationship
  are allowed when both sides use the same write model and pair-swap their
  orientation. Each side produces its own forward traversal surface;
  `throughLink(...)` sides also produce mutation helpers. Nothing is merged
  or de-duplicated. Test fixtures cover both shapes the matching rule has
  to handle:
  - **cross-schema**: e.g., `Group.members` and `User.groups` over a
    `Membership` junction, each declared on a different endpoint
    schema and pair-swapping each other's orientation key. Assert both
    declared `manyToMany` handles work for traversal/eager-loading/predicates
    independently, and that the target side has no synthesized
    `${sourceTable}_${forwardEdgeName}` entry
  - **self-schema (self-referential)**: e.g., `User.following` and
    `User.followers` over a `Follow` junction, both declared on the
    same `User` schema with pair-swapped orientation keys (per the
    self-referential rule below). Assert both declared `manyToMany`
    handles work side-by-side and the canonical-identity match does
    not fire same-orientation-alias rejection
- multiple `throughEntity(...)` declarations with identical canonical
  identity AND identical orientation key (same junction class, same
  `(sourceEdge, targetEdge)` pair, different `manyToMany` names) are
  rejected as same-orientation aliases; this also applies to
  `throughLink(...)`. The same-relationship-in-two-orientations case
  is governed by the pair-swap rule above; this bullet covers
  same-relationship-in-same-orientation duplicates. Test fixtures
  cover both shapes:
  - **cross-schema**: e.g., two `manyToMany<User>` declarations on
    the same `Group` schema (`val members` and `val users`) both
    passing `(Membership::group, Membership::user)` — rejected.
  - **self-schema (self-referential)**: e.g., two `manyToMany<User>`
    declarations on the same `User` schema both passing
    `(Follow::follower, Follow::followed)` — rejected
- self-referential `throughEntity(...)` (declaring schema and M2M
  target schema are the same): callers who want bidirectional
  traversal declare both orientations explicitly on the same schema
  with pair-swapped orientation keys (e.g., `User.following` via
  `(Follow::follower, Follow::followed)` and `User.followers` via
  `(Follow::followed, Follow::follower)`) and the matching rule above
  recognizes them as the two orientations of one canonical identity
- two explicit `throughEntity(...)` declarations with **distinct
  canonical relationship identities** (different junction class, or
  same junction class with different unordered junction-edge ref
  pairs, e.g. `(project, assignee)` vs `(project, reviewer)`) are
  independent — no matching attempted, no rejection, both allowed
- generated link-table M2M helpers are emitted for every explicit
  `throughLink(...)` declaration
- `throughLink(...)` M2M helpers are rejected for junction schemas with payload
  columns, nullable source/target FKs, caller-provided ids, partial unique
  indexes, or missing non-partial unique source/target FK pairs
- the payload-column rejection is enumerative, not heuristic: any
  declared scalar field other than the junction id and the two
  source/target FK columns triggers it. Test coverage spans nullable
  fields, defaulted fields, timestamp fields (`createdAt`,
  `updatedAt`, `deletedAt`), soft-delete markers, version /
  optimistic-concurrency tokens, role/kind discriminators, and
  mixin-provided fields (e.g. `include(::Timestamps)`)
- `throughLink(...)` rejects junction schemas whose two source/target
  FK backing fields carry write-time modifiers (validators like
  `.positive()` / `.minLength(...)` / `.match(...)`, `.sensitive()`,
  `.default(...)` / `.defaultNow()`, `.updateDefault(...)`,
  `.immutable()`). Junction helpers bypass the repo so these never
  fire and silent inertness is a foot-gun. `.comment(...)` is
  allowed (pure metadata). The same junction shape under
  `throughEntity(...)` accepts these modifiers because the junction
  repo runs validation / defaults / hooks on the normal builder paths
- `throughLink(...)` requires both junction `belongsTo` edges to declare
  `OnDelete.CASCADE`; `OnDelete.RESTRICT` (or unset, when the
  framework default isn't CASCADE) is rejected. Junctions that need a
  different deletion policy must be modeled with `throughEntity(...)`
- the unique source/target FK pair check accepts either column order.
  A two-sided relationship additionally requires a non-partial index
  leading with each declaration's source FK
- a unique index that includes a third column alongside the source
  and target FKs does not satisfy the pair-uniqueness check, even when
  non-partial
- the source FK and target FK columns named in the unique-pair check
  are the **physical backing columns** after
  `belongsTo(...).field(handle)` resolution
  (`EdgeKind.BelongsTo.field`, defaulting to `${edgeName}_id`), not
  the junction edge identifiers
- generated link-table M2M helpers populate client-generated UUID junction ids
  before calling `Driver.insertIgnore(...)`
- `throughEntity(...)` M2M edges do not generate direct helpers and continue to
  be mutated through the junction repo
- direct link-table helpers do not invoke any junction repo
  machinery — concretely, generated `throughLink(...)` helpers must
  not call `{Junction}Create.save()` / `{Junction}Update.save*()` /
  `{Junction}.deleteById(...)`, and they do not run junction field
  defaults, beforeSave/beforeCreate/beforeUpdate/beforeDelete/
  afterCreate/afterUpdate/afterDelete hooks, CREATE/UPDATE/DELETE
  privacy rules, CREATE/UPDATE validation rules, returned LOAD
  privacy, or the requested-patch / effective-patch / write-candidate
  machinery. Test fixtures should declare a junction with one of each
  (a hook, a privacy rule, a validation rule, a default) and assert
  none of them fire on a `throughLink(...)` helper write

## Extracted Follow-Ups

The old future-enhancement notes are now split into smaller possible-feature
RFCs:

- [Through-Entity Nullable M2M Traversal](09-through-entity-nullable-m2m-traversal.md)
- [Symmetric Link-Table Edges](10-symmetric-link-table-writes.md)

Bidirectional link-table write helpers are implemented by
[Symmetric Link-Table Edges](10-symmetric-link-table-writes.md), including
the opt-in canonical relationship lock for callers that need opposite-side
`set(...)` operations to serialize.
