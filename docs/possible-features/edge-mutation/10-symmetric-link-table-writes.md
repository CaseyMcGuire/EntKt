# RFC: Symmetric Link-Table Edges

## Status

Possible future feature. This is not implemented.

**Relaxes** the single-write-orientation rule from
[Many-To-Many Schema Modeling](../../implemented-features/edge-mutation/03-m2m-schema-modeling.md)
(RFC 03) and [Link-Table M2M Mutation Helpers](../../implemented-features/edge-mutation/05-link-table-helpers.md)
(RFC 05).

> Revised after review to correct three things the first draft got wrong or
> left open: `add` is **not** currently conflict-tolerant (§Idempotent add),
> M2M saves **already** serialize automatically (§Concurrency), and the
> source-first junction-index rule **blocks** pair-swapped declarations
> (§Validation — junction index).

## Summary

Let a pure link table be declared from **both** endpoints with the same
`throughLink(...)`. By default both sides are fully writable — read, `add`,
`remove`, and `set`. This requires three concrete changes beyond "let the
second side through validation":

1. **`add` becomes idempotent** via a new junction upsert driver op
   (`INSERT … ON CONFLICT DO NOTHING`), so concurrent opposite-side adds of the
   same pair don't fail on the junction's unique index.
2. **The existing automatic owner-edge serialization is preserved**; an opt-in
   `relationshipLocking = RelationshipLocking.Canonical` update option adds an
   *additional* **canonical relationship lock** for the cross-orientation case
   the owner lock doesn't cover.
3. **Junction-index validation is revised** so a single unique index on the
   FK pair satisfies both declaration orientations.

Plus an optional `.readOnly()` per side that drops its write helpers.

## Motivation

RFC 03/05 allow only one `throughLink(...)` per canonical relationship
identity, so the other endpoint gets **nothing** — you cannot even read
`tag.posts` without promoting the junction to a `throughEntity`. But the
restriction is broader than the constraint that justifies it. The generated
helpers are not equally risky:

- **`add` / `remove` are anchor-symmetric in intent.** `post.tags.add(t)` and
  `tag.posts.add(p)` describe the *same* junction row. `remove` already is the
  idempotent `DELETE WHERE source = this.id AND target IN (…)`
  (`UpdateGenerator.kt:1535-1549`). `add`, today, is **not** — see §Idempotent
  add; this RFC fixes that.
- **Only `set` (exact replace) is anchored to one side.** It reads the current
  junction rows for one anchor (`junctionSourceColumn = this.id`,
  `UpdateGenerator.kt:1015-1029`) and reconciles. Two `set`s from opposite
  anchors are the "exact-set race between opposite orientations" RFC 03 names
  as the reason for the rule (`03-m2m-schema-modeling.md:245-246`) — which it
  says stands "without a concrete read-only marker **or canonical
  reverse-write lock model**." This RFC supplies the lock model.

## Non-Goals

- Do not change `throughEntity(...)` — domain junctions stay symmetric via
  pair-swapped `throughEntity` declarations.
- Do not auto-synthesize the second endpoint — both sides are declared
  explicitly, so each side's surface is auditable in schema source.
- Do not remove the automatic owner-edge serialization or the
  transaction requirement that M2M saves have today. Those are preserved
  exactly; this RFC only *adds* an opt-in cross-orientation lock.

## Proposed API

Both endpoints declare the same `throughLink` (pair-swapped junction refs,
exactly like bidirectional `throughEntity`):

```kotlin
class Post : EntSchema("posts") {
    val tags = manyToMany<Tag>("tags")
        .throughLink<PostTag>(PostTag::post, PostTag::tag)
}

class Tag : EntSchema("tags") {
    val posts = manyToMany<Post>("posts")
        .throughLink<PostTag>(PostTag::tag, PostTag::post)
}
```

Both `Post.tags` and `Tag.posts` get read traversal **and** the full
`add` / `remove` / `set` write surface — that is the default.

Optional `.readOnly()` makes one side read-only (a deliberate restriction,
e.g. for encapsulation):

```kotlin
val posts = manyToMany<Post>("posts")
    .throughLink<PostTag>(PostTag::tag, PostTag::post)
    .readOnly()
```

| Declared side | read | `add` / `remove` | `set` |
|---|:--:|:--:|:--:|
| `throughLink(...)` | ✓ | ✓ | ✓ |
| `throughLink(...).readOnly()` | ✓ | — | — |

A **lone** declaration (only one side) is unchanged from today: that side gets
the full surface; the other side has none.

(`readOnly` is named to state the behavior and avoid overloading an existing
term, per the project's least-surprise principle.)

## Idempotent `add`

The first draft claimed `add` emits `INSERT … ON CONFLICT DO NOTHING`. It does
not. Generated `add` calls `driver.insert(...)` (`UpdateGenerator.kt:1515-1526`),
and `PostgresDriver.insertWith` (`:149-182`) emits a plain
`INSERT INTO … VALUES (…) RETURNING *`. Because every link-table junction
**requires** a unique index on `(source_fk, target_fk)`
(`validateThroughLinkJunctions` Rule 6), two opposite-side adds of the same
pair —

```kotlin
post.tags.add(tagId)   // INSERT (post_id, tag_id)
tag.posts.add(postId)  // INSERT (post_id, tag_id)  — same row
```

— can both read "missing" and both insert, and one fails on the unique
constraint. (This is latent today even single-side: re-adding an existing link
throws.) Idempotent `add` is the behavior callers expect, so:

- **New driver op with an explicit conflict target** — the generator knows the
  pair columns, so it passes them rather than relying on a bare untargeted
  `ON CONFLICT DO NOTHING` (which would also swallow a surprise PK/id
  collision the RFC wants to surface):

  ```kotlin
  fun insertIgnore(
      table: String,
      values: Map<String, Any?>,
      conflictColumns: List<String>,
  )
  ```

  emitting `INSERT INTO <t> (…) VALUES (…) ON CONFLICT (<conflictColumns>) DO
  NOTHING`. Generated code passes `conflictColumns = [source_fk, target_fk]`,
  so only the expected pair-duplicate is swallowed. Gated behind a driver
  capability `supportsInsertIgnore`.
- **Every generated junction insert uses `insertIgnore`, not only the public
  `add` path.** The write loop inserts every `edgeChanges.added` row, and that
  set is fed by both `add(...)` *and* the additive delta computed by `set(...)`.
  So `set`'s inserts are conflict-tolerant too — a concurrent cross-anchor
  `add` landing between `set`'s read and insert no longer fails it.
- **Preflight:** `supportsInsertIgnore` is required whenever a pending op can
  *insert* junction rows — `add(...)` **yes**, `set(...)` **yes**, a
  remove-only save **no** — joining the existing transaction /
  owner-edge-serialization capability preflight.

This makes `add`/`set` inserts no-ops on an existing link and conflict-tolerant
under concurrency, which is what makes symmetric writes safe.

## Concurrency

**What already happens (preserved).** Every save with pending link-table M2M
ops *today* requires a transaction (`TransactionRequiredException`,
`UpdateGenerator.kt:1202-1218`) and **automatically serializes the owner edge**
— `SELECT … FOR UPDATE` on the owner row, or
`pg_advisory_xact_lock(ownerTable, ownerId)` when the driver lacks row locks
(`UpdateGenerator.kt:1247-1256`, `PostgresDriver.kt:920-959`;
`04-transaction-locking-semantics.md:501-509`). This RFC keeps that unchanged.

**What the owner lock does and doesn't cover.** The owner-edge lock key is
`(ownerTable, ownerId)`. So `post.tags.set(...)` locks `(posts, postId)` and
`tag.posts.set(...)` locks `(tags, tagId)` — **different keys**. Same-owner
concurrency is serialized; **cross-orientation** concurrency is not. Two
opposite-anchor `set`s can therefore:

- **Deadlock** — each scans/locks junction rows from its own anchor in opposite
  order; Postgres aborts one (loud, retryable).
- **Last-writer-wins** — each `set` is authoritative over its anchor's rows, so
  overlapping `set`s can overwrite each other's links. Inherent to symmetric
  exact-replace.

`add` (now via `insertIgnore`) and `remove` are idempotent single-row ops and
add no new race class.

### Relationship locking — the `relationshipLocking` update option

By default a save takes only the always-on owner-edge lock, so cross-orientation
writes can deadlock / last-writer-win (above). Opting into
`RelationshipLocking.Canonical` adds, **on top of** the owner-edge lock, a lock
keyed by the **canonical relationship identity** — `(junction table, unordered
FK pair)`, the same identity `validateM2MOrientation` groups by — so both
orientations of the *same* link table contend on the *same* key.

It is a **per-update option beside `consistency`**, not a fluent method after
the block — matching entkt's existing
`update(id, consistency = UpdateConsistency.Pessimistic) { … }` shape:

```kotlin
enum class RelationshipLocking { None, Canonical }

tx.posts.update(
    post.id,
    relationshipLocking = RelationshipLocking.Canonical,
) {
    tags.set(listOf(t1, t2))
}.save()
```

`relationshipLocking` defaults to `None` and, like `consistency`, honors a
client-wide default. The two are orthogonal, explicit per-update options:
`consistency` (`UpdateConsistency`) governs how the *owner row* is read
(ReadCurrent vs Pessimistic); `relationshipLocking` governs the *relationship*
lock; owner-edge serialization is always-on for M2M regardless of either.
Neither is hidden in the mutation DSL block. There is **no** fluent
`.serializedWrites()` — an alias could exist for convenience, but the option is
the primary API.

> Naming: `RelationshipLocking.Canonical` over the narrower
> `LinkTableLocking.CanonicalRelationship` — broader and lighter, on the bet
> that relationship locking generalizes beyond link-table M2M. Revisit if a
> non-M2M relationship-lock mode is ever needed.

Lock model:

- **Key:** the relationship identity, **not** the owner row — a stable hash of
  `(junctionTable, sortedFkPair)`. This is what coordinates `post.tags` with
  `tag.posts`.
- **Driver API:** mirrors the existing owner-edge serialization
  (`serializeOwnerEdgeAndRead` / `supportsOwnerEdgeSerialization`,
  `PostgresDriver.kt:945-959`): add a capability
  `supportsRelationshipSerialization` and a method `serializeRelationship(key)`.
  Postgres implements it as a second `pg_advisory_xact_lock` on the relationship
  key. Preflight requires the capability when `relationshipLocking = Canonical`.
- **Granularity:** relationship-level (whole junction relationship), not per
  source/target id — coarser, but deadlock-free and trivial to reason about; the
  throughput cost matters only for write-hot link tables, which is exactly where
  the caller opts in deliberately.
- **Acquisition order (deadlock avoidance):** a single save may touch several
  M2M relationships and the owner row. All locks are acquired in one canonical
  total order — owner-edge lock first, then relationship locks in ascending key
  order — so deterministic keys + a fixed order ⇒ no lock-order cycle between
  cooperating transactions.
- **Cooperative:** it serializes only against other writes that also set
  `relationshipLocking = Canonical`. A writer that leaves it `None` still takes
  only its owner-edge lock and is not protected. It removes the *deadlock* for
  cooperating writers; it does **not** remove last-writer-wins.

## Validation

### Orientation — pair-swapped, not just "≤ 2"

`validateM2MOrientation` groups by canonical identity = `(junction, unordered
edge pair)` (`EntGenerator.kt:207-214`) and today rejects any group of size > 1
(`:259-269`). The new rule is **not** merely "allow two" — that would admit two
same-orientation aliases (a duplicate traversal name over the same direction).
Per canonical identity:

- **one** declaration → allowed (lone, unchanged);
- **two** declarations → allowed **only if their orientations are exactly
  pair-swapped** (one `(source, target)`, the other `(target, source)`);
- **two same-orientation** declarations → **rejected** (duplicate alias);
- **three or more** → rejected;
- **mixed** `throughLink` / `throughEntity` over the identity → rejected
  (unchanged).

### Junction index — one unique pair index, either order

`validateThroughLinkJunctions` Rule 6 currently requires a non-partial unique
composite index in **source-first** order `(source_fk, target_fk)`, checked
*per declaration* and rejecting the reversed order outright (test fixtures
`ReverseOrderIdxLink*`). With pair-swapped declarations the second side's
"source-first" is `(target_fk, source_fk)`, which a normal junction does not
have — so the current rule **blocks** the feature.

Revised rule:

- **Correctness:** require exactly one non-partial unique composite index on
  the **unordered** FK pair — `(source_fk, target_fk)` *or*
  `(target_fk, source_fk)` satisfies it. Either index enforces pair uniqueness
  (and is a valid `ON CONFLICT` arbiter for `insertIgnore`). The
  per-declaration source-first requirement is dropped.
- **Performance (the real reason the old rule was source-first):** generated
  helpers do source-keyed lookups via the index's *leading* column — and
  **every declared side does them, writable or `.readOnly()`**: `set` exact-set
  and `add`/`remove` deltas read by source, *and* read traversal / eager-load
  (`query...`, `withX()`) read by source too. A single unique pair index serves
  only one leading direction, so for **any** two-sided declaration **require** a
  non-partial index whose leading column is the *other* side's source FK (it may
  be the unique pair index in the opposite order, or a separate non-unique
  index). This is **not** relaxed for `.readOnly()` — a read-only side still
  traverses and eager-loads by its own source. Diagnostics name the exact
  `index(...)` to declare.

## `.readOnly()` schema model

`readOnly` is a property of a link-table edge only, so it goes on the existing
`ManyToManyThrough.LinkTable` variant (a new `readOnly: Boolean = false`
field) — **not** on `Edge` (would touch unrelated edge kinds) and **not** a new
sealed variant. The builder captures it; `LinkTable` carries it;
`helperEligibleM2MEdges` and explain read it.

## Backward Compatibility

Fully compatible. Every existing schema is a lone `throughLink` declaration —
the sole side keeps `read` / `add` / `remove` / `set`, and its junction has the
source-first unique index the revised rule still accepts. The relaxation only
*widens* what validation accepts (pair-swapped second declarations, previously
rejected). One behavior improvement that is strictly safer: `add` becomes
idempotent (re-adding an existing link is now a no-op instead of throwing).

## Implementation Touchpoints

- **Driver**: add `insertIgnore(table, values, conflictColumns)` +
  `supportsInsertIgnore` capability (`PostgresDriver`). **All** generated
  junction inserts — the `edgeChanges.added` loop
  (`UpdateGenerator.kt:1515-1526`), fed by both `add(...)` and `set(...)` —
  switch from `insert` to `insertIgnore` with
  `conflictColumns = [source_fk, target_fk]`. Preflight requires
  `supportsInsertIgnore` for any save with pending insert-producing ops
  (`add`/`set`), not remove-only.
- **`validateM2MOrientation`** (`EntGenerator.kt:194-288`): replace "reject
  size > 1" with the pair-swapped rule above.
- **`validateThroughLinkJunctions`** (Rule 6, `EntGenerator.kt:~498-514`):
  unordered-pair unique-index rule + a leading-column index requirement for
  **every** two-sided declaration, including `.readOnly()` (it still reads by
  source).
- **`ManyToManyThrough.LinkTable`** (`Edge.kt`): add `readOnly: Boolean`; the
  builder sets it.
- **`helperEligibleM2MEdges`** (`HelperEligibleM2M.kt:64-93`): still filters to
  `LinkTable`; both endpoints now pass; exclude `readOnly` sides.
- **`UpdateGenerator`** (`:79-84`, `:237-245`, junction writes `:1500-1551`):
  emit `add` / `remove` / `set` on every non-`readOnly` side; **all
  `edgeChanges.added` junction inserts use `insertIgnore`**, whether produced by
  `add(...)` or `set(...)`. The transaction requirement and owner-edge
  serialization (`:1202-1256`) are unchanged.
- **`relationshipLocking` option**: a `RelationshipLocking` enum on the per-
  update options surface beside `consistency` (client-wide default). When
  `Canonical`, take the canonical-relationship lock — new driver
  `serializeRelationship(key)` + `supportsRelationshipSerialization` capability
  (mirroring `serializeOwnerEdgeAndRead`/`supportsOwnerEdgeSerialization`) —
  in addition to the owner-edge lock, in the canonical acquisition order.
  Preflight requires the capability when `Canonical`.
- **`PrivacyGenerator`** (`:63-68`, `:114-128`): `pendingEdges` / `EdgeChanges`
  aggregators on both writable sides.
- **Read path** (`Query.kt` `queryX` / `withX` / `EdgeRef`): unchanged —
  `resolveM2MEdgeJoin` already resolves pair-swapped declarations with no
  special-casing (`SchemaMetadata.kt:359-413`).
- **Schema explain** (`SchemaInspector.buildEdges`): surface the concrete
  write surface, not just a flag — e.g. `helpers=[add, remove, set]` for a
  writable side, `helpers=[]` for a `readOnly` side — as the write-API audit.

## Test Requirements

- **Read parity:** a second-side `throughLink` matches the owner side's
  `queryX` / `withX` / `exists` / `has { … }` results.
- **`add` / `remove` parity & idempotency:** `post.tags.add(t)` and
  `tag.posts.add(p)` produce identical rows; re-adding an existing link is a
  no-op (no throw); `add` emits `ON CONFLICT … DO NOTHING`.
- **Concurrent opposite-side `add`** (Postgres integration): both succeed,
  converging to one junction row — proving `insertIgnore`, not a unique
  violation. Same for a cross-anchor `set` + `add` race (`set`'s additive
  insert tolerates the racing `add`).
- **Preflight scope:** a save with pending `add`/`set` requires
  `supportsInsertIgnore`; a remove-only save does not.
- **`set` ownership/visibility:** `set` works from either side; a `readOnly`
  side has no `add` / `remove` / `set`, no `pendingEdges`, no `EdgeChanges`.
- **`relationshipLocking = Canonical` uses the relationship lock:** assert it
  calls `serializeRelationship` on the canonical-relationship key (junction
  identity), **not** the owner-row lock — two `Canonical` opposite-side `set`s
  serialize (no deadlock); a writer that leaves it `None` is unprotected.
  Preflight requires `supportsRelationshipSerialization` when `Canonical`.
- **Validation — orientation:** pair-swapped two-sided declaration accepts;
  two same-orientation declarations reject; three reject; mixed reject.
- **Validation — junction index:** a pair-swapped declaration is accepted with
  a single `(post_id, tag_id)` unique index; **every** two-sided declaration —
  writable *or* `.readOnly()` — requires the other-direction leading-column
  index (a `.readOnly()` second side without it is rejected, since it still
  reads by source).
- **Compatibility:** every existing single-side `throughLink` fixture generates
  byte-identical code except junction inserts (`add` *and* `set`'s additive
  delta) switch `insert` → `insertIgnore`.
