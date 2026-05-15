# RFC: Many-To-Many Schema Modeling

## Status

Partially implemented. The schema-side write-model marker (`throughLink` /
`throughEntity`), the static validation rules described in this RFC, and the
default reverse-edge synthesis behavior all landed. Specifically implemented:

- `manyToMany<Target>(...).throughLink<Junction>(sourceEdge, targetEdge)` and
  `throughEntity<Junction>(sourceEdge, targetEdge)` are the only ways to mark
  an M2M edge — the generic `.through(...)` form is gone.
- Schema finalization rejects malformed refs: identical source/target props,
  sourceEdge not targeting the declaring schema, targetEdge not targeting the
  `manyToMany<Target>` type parameter.
- Codegen rejects incompatible declarations with the same canonical
  relationship identity (junction + unordered junction-edge ref pair): two
  `throughLink` declarations, mixed `throughLink` + `throughEntity`, and
  same-orientation `throughEntity` aliases. Pair-swapped `throughEntity`
  declarations remain allowed and are the supported bidirectional pattern.
- Default reverse-edge synthesis is suppressed for self-referential M2M and
  for any opposite-side schema that declares its own pair-swapped
  `throughEntity` (the explicit declaration owns the traversal surface).
- For one-sided `throughEntity` declarations the synthesized reverse
  produces a full read-only surface on the target schema: an
  `EdgeRef<Source, SourceQuery>` on the companion, a `List<Source>?`
  field on the `Edges` inner data class, a `queryX(): SourceQuery`
  traversal method, and a `withX { }` eager-loading method. The
  traversal predicate uses the source's *forward* edge name in
  `HasEdgeWith`, which the runtime resolves against the source's
  `SCHEMA.edges`.
- `throughLink` junction-shape helper-eligibility is enforced at codegen
  time: payload columns, nullable junction FKs, missing
  `OnDelete.CASCADE`, write-time modifiers on FK backing fields, EXPLICIT id
  strategies, and missing / partial / wrong-order unique composite indexes
  are all rejected with a message naming the failing rule.

Deferred to a future RFC (or to the link-table-helpers RFC #5):

- The actual `throughLink` direct-helper method generation (`tags.add(...)`,
  `tags.remove(...)`, `tags.set(...)`) — RFC #5 covers the API shape; the
  generator implementation is not yet in this repo.
- User-facing reverse surface for `throughLink` (vs. `throughEntity`).
  For `throughLink` the reverse-edge runtime metadata is still
  synthesized so forward query traversal works, but no
  `EdgeRef` / `Edges` field / `queryX` / `withX` is generated on the
  target — link-table reverse traversal is deferred until the
  link-table helpers spec lands.
- Junction `belongsTo` nullable-FK traversal semantics for `throughEntity`
  (inner-join skip-null behavior described under "Traversal semantics with
  nullable junction FKs"); the runtime currently treats nullable junction
  FKs the same as non-null ones until the link-table helpers spec lands.

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

Conceptually, the edge metadata should carry this choice as a sealed
type rather than an enum + nullable disambiguation fields. A sealed
model makes downstream codegen branching exhaustive (a future variant
can't be silently skipped), drops the "always populated in practice
but typed nullable" footgun on `sourceEdge` / `targetEdge`, and leaves
the door open for additional variants like `LinkTableInverse` (see the
read-only reverse-traversal design in "Future Enhancements") without
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
sealed variants — if a future variant is added (e.g. `LinkTableInverse`
for read-only reverse traversal), the compiler flags every branching
site that needs an opinion on the new mode rather than letting it
silently fall through.

## Write Orientation

M2M identity in this RFC works at two levels:

1. The **orientation key** is the ordered triple

   ```
   (junction schema, source junction edge, target junction edge)
   ```

   — the junction class and the two `belongsTo` property references the
   caller passes as `sourceEdge` / `targetEdge` to
   `.throughLink<Junction>(...)`. It's what the caller actually writes
   down, and order matters: it pins the write orientation, the
   pair-uniqueness column order, and which side owns the helpers.

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

For V1, a link-table M2M relationship may have only one explicit
`throughLink(...)` declaration per **canonical relationship identity**.
That single declaration's orientation key picks the write orientation
and gets generated helpers.

A second `throughLink(...)` whose canonical relationship identity
matches an existing declaration — including the case where the source
and target junction edges are swapped (same identity, opposite
orientation) — is the "explicit opposite-side declaration" rejected
below. So in practice the rejection rule is "two `throughLink(...)`
declarations with the same canonical identity are rejected"; whether
the two orientation keys match exactly or are pair-swapped doesn't
matter for rejection. Two declarations with *distinct* canonical
identities — e.g., `(ProjectAssignment, project, assignee)` and
`(ProjectAssignment, project, reviewer)` — describe genuinely
different relationships and both are allowed.

**V1 does not synthesize a reverse traversal edge for `throughLink(...)`
relationships.** Codegen does not infer a read-only edge on the opposite-side
schema, the opposite-side `Edges` inner data class does not gain a
synthesized field for the relationship, and there is no eager-loading or
predicate surface for the reverse direction. Callers that need to traverse
the relationship from the opposite side query the junction schema directly
for V1. Reverse traversal for link-table relationships is deferred — see
"Future Enhancements" for the planned design.

Codegen must reject an explicit opposite-side `throughLink(...)` declaration
that resolves to the same **canonical relationship identity** in V1 —
concretely, any second declaration whose junction class matches and whose
junction-edge ref pair matches the first's as an unordered pair, regardless
of which side is `sourceEdge` and which is `targetEdge`. Without a concrete
read-only marker or canonical reverse-write lock model, explicit bidirectional
link-table helpers would make the owner of the relationship ambiguous and
could reintroduce exact-set races between opposite orientations.

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
traversal handles aren't generated.

**Synthesized reverse name.** Codegen names the synthesized reverse
edge mechanically so callers can refer to it from eager-loading
scopes, predicates, and `EdgeRef`s without surprise:

- **Edge name** (the runtime metadata `Edge.name` string used in the
  generated `EntitySchema.edges` map and the database-facing
  identifiers): `${sourceTable}_${forwardEdgeName}`. For
  `Post.tags = manyToMany<Tag>("tags").throughEntity<PostTag>(...)`
  on a schema with `tableName = "posts"`, the synthesized reverse
  edge on `Tag` has edge name `posts_tags`.
- **Kotlin property name** on the opposite-side schema's `Edges`
  inner data class (and on its companion `EdgeRef` for eager-loading
  / predicate handles): `toCamelCase("${sourceTable}_${forwardEdgeName}")`,
  i.e. `postsTags` for the example above. This is the identifier
  reverse-traversal callers see in autocomplete.

**Read-only / repo-only writes.** The synthesized reverse edge is
**read-only** — codegen emits no `add(...)` / `remove(...)` / `set(...)`
helpers on it, and the only write surface for the relationship is
the forward `throughEntity(...)` declaration on the source schema
(which goes through the junction repo). Mutations to the M2M
relationship from the opposite-side schema's perspective happen by
creating, updating, or deleting junction rows directly through the
junction's generated repo. Implementers should not be tempted by the
synthesized reverse appearing in `Edges` and `EdgeRef` to also emit
write helpers for it — the surface is intentionally narrow to keep
the write orientation unambiguous.

The `${sourceTable}_${forwardEdgeName}` shape is deterministic, mirrors
the FK column-naming convention already used elsewhere in the schema,
and disambiguates multi-relationship junctions (e.g.,
`ProjectAssignment` with both an `assignees` forward edge on `Project`
and a `reviewers` forward edge on `Project` synthesizes
`projects_assignees` and `projects_reviewers` on the opposite-side
schemas — distinct names from distinct forward edges).

**Collision rejection.** If the synthesized reverse edge name collides
with an existing declared edge, declared field, generated edge ref,
generated eager-loading member, or JVM signature on the opposite-side
schema, codegen rejects the schema at validation time and directs the
caller to declare the opposite-side `throughEntity(...)` **explicitly**
with a chosen `manyToMany` name. An explicit opposite-side declaration
picks the name and suppresses the synthesized reverse (per the matching
rule below), so the caller has full control of the identifier when the
synthesized name doesn't fit. The synthesized name is never silently
renamed or suffix-disambiguated.

**Self-referential `throughEntity(...)`: no default synthesis.** When the
declaring schema and the M2M target schema are the same — i.e., the
"opposite-side schema" is the *same* schema — V1 does not synthesize a
reverse traversal edge. There is no separate schema to put it on, and
generating a second edge on the declaring schema would need a
synthesized name with no stable convention (and risks colliding with
the original declared edge). Callers that need bidirectional traversal
for a self-referential `throughEntity(...)` declare both edges
explicitly on the same schema, with orientation keys that pair-swap so
the matching rule below recognizes them as the two orientations of one
canonical relationship identity:

```kotlin
class User : EntSchema("users") {
    val following = manyToMany<User>("following")
        .throughEntity<Follow>(Follow::follower, Follow::followed)
    val followers = manyToMany<User>("followers")
        .throughEntity<Follow>(Follow::followed, Follow::follower)
}
```

The two declarations are the two orientations of the same canonical
identity (`{Follow, follower, followed}` as an unordered pair). Because
each side is explicit, neither triggers the "default synthesize reverse"
path — symmetric with how cross-schema explicit opposites suppress
synthesis on both sides.

**Matching rule for two explicit `throughEntity(...)` declarations.**
Codegen treats two explicit declarations as opposite sides of the same
relationship when, and only when:

1. They reference the **same junction schema** (same `KClass`).
2. They reference the **same two junction `belongsTo` edges**, in
   opposite order — concretely, side A's `(sourceEdge, targetEdge)`
   orientation key is `(X, Y)` and side B's orientation key is
   `(Y, X)` for the same two junction-edge property references.

If both conditions hold, the synthesized reverse on each side is
suppressed and the two explicit declarations describe the same
relationship from the two endpoints.

**Scope of the matching rule.** The pair-swap check applies *only* when
the two declarations share a canonical relationship identity — same
junction class plus the same junction-edge ref pair as an unordered
set. Declarations with **distinct canonical relationship identities**
are independent and may coexist freely: no matching is attempted
between them, no rejection fires, and each independently runs the
default reverse-synthesis path on its own target schema (which the
opposite-side schema can suppress by declaring its own explicit
`throughEntity(...)`).

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

- **Same canonical identity, orientation keys pair-swap** → matched
  as opposites; synthesized reverse suppressed on both sides.
- **Same canonical identity, identical orientation key** → rejected
  as same-orientation alias (see "Same-orientation aliases are
  rejected" below).
- **Distinct canonical identities** → independent; no matching, no
  rejection, both allowed.

The same matching rule applies to the `throughLink(...)` opposite-side
rejection (already covered above):

- **Same canonical identity, orientation keys pair-swap** → second
  declaration rejected (same relationship in opposite orientations).
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
    `.maxLen(...)`, `.match(...)`, etc.)
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
  **exactly the source FK column followed by the target FK column**,
  in that order: `(source_fk, target_fk)`. The constraint must contain
  only those two columns; an index that adds a third column (even one
  that's deterministically set, like a `kind` discriminator) does not
  enforce uniqueness of the pair alone and is not sufficient. The
  constraint must also be non-partial — a `WHERE` clause filtering out
  some rows wouldn't reject duplicate links under concurrent writers
  in the filtered region. Normalized set semantics require the
  database to reject duplicate links under concurrent writers and to
  rule out preexisting duplicate link rows.

  **Why source-first**, not order-insensitive: generated link-table
  helpers also need to look up "all links for a given source FK" (for
  `set(...)` exact-set semantics, eager-loading the M2M edge by source,
  etc.). A `(source_fk, target_fk)` index serves both the
  pair-uniqueness constraint and that source-keyed lookup directly via
  the index's leading column; allowing `(target_fk, source_fk)` would
  force callers to either tolerate a sequential scan for the
  source-keyed lookup or maintain a companion source-first index. V1
  picks the simpler rule: one source-first index covers both needs.

  **Column resolution.** The two columns named here are the **physical
  backing FK columns** after `belongsTo(...).field(handle)` resolution
  (the `field` value on `EdgeKind.BelongsTo`, defaulting to
  `${edgeName}_id`), not the junction edge identifiers. A junction
  edge declared as
  `val source = belongsTo<Post>("source").field(postId)` contributes
  `post_id` (the backing column), not `source_id`.

  **Scope of "exactly".** "Exactly the source FK column followed by
  the target FK column" describes the *qualifying index itself* — its
  column list contains exactly those two columns in that order. It is
  not a statement about the whole junction schema's index set: the
  junction may declare additional indexes (e.g., a target-first
  secondary lookup index, a partial index for an unrelated query
  pattern, a single-column index on the source FK for join planning)
  as long as one qualifying index exists. Duplicate-shape indexes
  remain subject to the normal schema index-validation rules
  (duplicate index names, duplicate column-set + uniqueness + where
  triples).

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
3. Generate direct helpers only for the single explicit `throughLink(...)`
   declaration for a junction relationship.
4. V1 does not synthesize a reverse traversal edge for `throughLink(...)`
   relationships (see "Write Orientation"); reverse traversal is
   deferred to a follow-up `throughLinkInverse(...)` design.
5. Keep through-entity edges repo-only for write paths; the
   default-synthesize / opposite-side-suppress reverse-traversal rule
   from "Write Orientation" applies.

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
- a second `throughLink(...)` declaration that resolves to the same
  **canonical relationship identity** as an existing declaration — same
  junction class, same junction-edge ref pair as an unordered pair,
  regardless of which side is `sourceEdge` and which is `targetEdge` —
  is rejected in V1 (covers self-referential junctions like
  `Friendship::requester` ↔ `Friendship::recipient` and multi-FK
  junctions where both sides resolve to the same target schema)
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
- V1 does not synthesize a reverse traversal edge for a `throughLink(...)`
  relationship: the opposite-side schema's generated `Edges` data class
  does not gain a synthesized field, no eager-loading scope is generated,
  and no predicate handle is exposed for the reverse direction
- explicit opposite-side `throughEntity(...)` traversal declarations for the
  same junction relationship are allowed when both sides use `throughEntity(...)`
- a `throughEntity(...)` declaration synthesizes a read-only reverse
  traversal edge on the opposite-side schema by default; when the
  opposite side explicitly declares its own `throughEntity(...)` for
  the same junction, the synthesized reverse is suppressed
- the synthesized reverse edge is named mechanically:
  `Edge.name = "${sourceTable}_${forwardEdgeName}"`, Kotlin property
  on the opposite-side `Edges` data class and `EdgeRef` companion =
  `toCamelCase(edgeName)` (e.g., `Post.tags` with
  `tableName = "posts"` produces `posts_tags` / `postsTags` on `Tag`)
- if the synthesized reverse edge name collides with an existing
  declared edge, declared field, generated edge ref, generated
  eager-loading member, or JVM signature on the opposite-side schema,
  codegen rejects the schema and directs the caller to declare the
  opposite-side `throughEntity(...)` explicitly with a chosen name;
  the synthesized name is never silently renamed so users
  don't get duplicate traversal handles
- two explicit `throughEntity(...)` declarations are treated as
  opposite sides of the same relationship only when they reference
  the same junction schema AND the same two junction `belongsTo`
  edges in opposite order (side A's
  `(sourceEdge, targetEdge) = (X, Y)`, side B's = `(Y, X)`); when
  matched, each side's synthesized reverse is suppressed. Test
  fixtures cover both shapes the rule has to handle:
  - **cross-schema**: e.g., `Group.members` and `User.groups` over a
    `Membership` junction, each declared on a different endpoint
    schema and pair-swapping each other's orientation key. Assert
    neither side synthesizes a reverse, and both declared `manyToMany`
    handles work for traversal/eager-loading/predicates.
  - **self-schema (self-referential)**: e.g., `User.following` and
    `User.followers` over a `Follow` junction, both declared on the
    same `User` schema with pair-swapped orientation keys (per the
    self-referential rule below). Assert no synthesis attempt, no
    same-orientation-alias rejection, and both declared `manyToMany`
    handles work side-by-side
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
  target schema are the same) gets **no default reverse synthesis**;
  callers who want bidirectional traversal declare both orientations
  explicitly on the same schema with pair-swapped orientation keys
  (e.g., `User.following` via `(Follow::follower, Follow::followed)`
  and `User.followers` via `(Follow::followed, Follow::follower)`)
  and the matching rule above recognizes them as the two orientations
  of one canonical identity
- two explicit `throughEntity(...)` declarations with **distinct
  canonical relationship identities** (different junction class, or
  same junction class with different unordered junction-edge ref
  pairs, e.g. `(project, assignee)` vs `(project, reviewer)`) are
  independent — no matching attempted, no rejection, both allowed,
  each independently runs default reverse synthesis on its target
  schema
- generated link-table M2M helpers are emitted only for the single explicit
  `throughLink(...)` declaration for a junction relationship
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
  `.positive()` / `.minLen(...)` / `.match(...)`, `.sensitive()`,
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
- the unique source/target FK pair check requires source-first
  ordering: an index declared as `(source_fk, target_fk)` qualifies;
  one declared as `(target_fk, source_fk)` is rejected (V1 picks the
  source-first rule so a single index covers both pair-uniqueness and
  the source-keyed lookups generated helpers need)
- a unique index that includes a third column alongside the source
  and target FKs does not satisfy the pair-uniqueness check, even when
  non-partial
- the source FK and target FK columns named in the unique-pair check
  are the **physical backing columns** after
  `belongsTo(...).field(handle)` resolution
  (`EdgeKind.BelongsTo.field`, defaulting to `${edgeName}_id`), not
  the junction edge identifiers
- generated link-table M2M helpers populate client-generated UUID junction ids
  before calling raw `Driver.insert(...)` / `insertMany(...)`
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
  canonical relationship identity so exact `set(...)` semantics cannot
  race with reverse-direction `add(...)`, `remove(...)`, or `set(...)`.
