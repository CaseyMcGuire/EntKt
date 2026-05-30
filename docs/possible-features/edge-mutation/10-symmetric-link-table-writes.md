# RFC: Symmetric Link-Table Edges

## Status

Possible future feature. This is not implemented.

Replaces the earlier "Through-Link Inverse Read Traversal" design that
occupied this slot (a read-only `throughLinkInverse(...)` marker — dropped in
favor of full symmetry), and **relaxes** the single-write-orientation rule
from [Many-To-Many Schema Modeling](../../implemented-features/edge-mutation/03-m2m-schema-modeling.md)
(RFC 03) and [Link-Table M2M Mutation Helpers](../../implemented-features/edge-mutation/05-link-table-helpers.md)
(RFC 05).

## Summary

Let a pure link table be declared from **both** endpoints with the same
`throughLink(...)`. By default both sides are fully writable — read, `add`,
`remove`, and `set` — with no hidden coordination: what you declare is what
you get. Two optional, explicit opt-ins tune it: `.readOnly()` (on the schema)
drops a side's write helpers, and `.serializedWrites()` (on the write call)
takes a relationship lock so concurrent writes serialize instead of
deadlocking.

## Motivation

RFC 03/05 allow only one `throughLink(...)` per canonical relationship
identity, so the other endpoint gets **nothing** — you cannot even read
`tag.posts` without promoting the junction to a `throughEntity`. But the
restriction is broader than the constraint that justifies it. The generated
helpers are not equally risky:

- **`add` / `remove` are anchor-symmetric.** `post.tags.add(t)` and
  `tag.posts.add(p)` compile to the *same* statement —
  `INSERT (post_id = this.id, tag_id = targetId) … ON CONFLICT DO NOTHING`
  (`UpdateGenerator.kt:1517-1526`); `remove` is the same
  `DELETE WHERE source = this.id AND target IN (…)`
  (`UpdateGenerator.kt:1535-1549`). One row operation from two anchors — no
  ownership question.
- **Only `set` (exact replace) is anchored to one side.** It reads the current
  junction rows for one anchor (`junctionSourceColumn = this.id`,
  `UpdateGenerator.kt:1015-1029`) and reconciles. Two `set`s from opposite
  anchors are the "exact-set race between opposite orientations" RFC 03 names
  as the reason for the rule (`03-m2m-schema-modeling.md:245-246`) — which it
  says stands "without a concrete read-only marker **or canonical
  reverse-write lock model**."

This RFC provides both of those — but makes them optional and explicit, and
keeps full symmetry as the default.

## Non-Goals

- Do not hide coordination. There is no automatic/secret lock; serialization
  is an explicit opt-in at the write call.
- Do not change `throughEntity(...)` — domain junctions stay symmetric via
  pair-swapped `throughEntity` declarations.
- Do not auto-synthesize the second endpoint — both sides are declared
  explicitly, so each side's surface is auditable in schema source.

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

## Concurrency

With both sides writable, `set` can run from either anchor. This is safe for
data integrity — every `set` is a transactional delete+insert under the
junction's FKs — but has two concurrency properties callers must understand:

- **Deadlock.** Concurrent `set`s from opposite anchors lock junction rows in
  opposite order; Postgres detects this and aborts one transaction (loud,
  retryable).
- **Last-writer-wins.** Each `set` is authoritative over its anchor's rows, so
  two overlapping `set`s can silently overwrite each other's links. This is
  inherent to symmetric exact-replace.

`add` / `remove` add no new race class (idempotent single-row ops).

### `.serializedWrites()` — opt-in, on the write call

To remove deadlocks, opt into a relationship-scoped advisory lock **on the
write operation**, not the schema:

```kotlin
client.posts.update(p) { tags.set(listOf(t1, t2)) }
    .serializedWrites()   // hold the post_tags relationship lock for this write
    .save()
```

It is **cooperative**: it serializes only against other writes that also pass
it. Two writers that both opt in cannot deadlock — they serialize to
last-writer-wins; a writer that omits it is not protected. There is no global
guarantee, by design, consistent with "the client manages the concurrency."
`.serializedWrites()` removes the *deadlock*, not the last-writer-wins.

Keeping this on the call site (rather than a schema flag) matches entkt's
existing per-operation locking knob (`UpdateConsistency` / pessimistic opt-in)
and avoids a "declare on both sides and keep them in sync" schema flag.

> **Open detail:** whether `.serializedWrites()` is a standalone save option or
> folds into the existing `UpdateConsistency` selector. It should reuse that
> surface, not invent a parallel one.

## Validation

`validateM2MOrientation` (`codegen/.../EntGenerator.kt:194-288`) changes from
"reject any canonical-identity group with size > 1" to:

- **Allow up to two** `throughLink` declarations per canonical identity — the
  two pair-swapped endpoints. Three or more still reject.
- A `.readOnly()` side generates no write helpers (read surfaces only).
- No set-owner rules are needed: `set` exists on every non-`readOnly` side by
  default.

## Backward Compatibility

Fully compatible. Every existing schema is a lone `throughLink` declaration,
which keeps `read` / `add` / `remove` / `set` on the sole side unchanged. The
relaxation only *widens* what validation accepts (two-sided declarations,
previously rejected), so no currently-valid schema changes behavior.
Declaring the second endpoint is opt-in.

## What This Replaces

The earlier through-link-inverse design — a read-only `throughLinkInverse(...)`
marker resolving to a `LinkTableInverse` sealed variant — is dropped entirely.
Symmetric declaration makes all of it unnecessary:

- Second-side reads are the **default** of a plain second-side `throughLink`.
- "Read-only" is the `.readOnly()` modifier on an otherwise-normal edge.
- Both sides are plain `LinkTable` (no new `ManyToManyThrough` variant), so
  `resolveM2MEdgeJoin` already resolves pair-swapped declarations with no
  special-casing (`SchemaMetadata.kt:359-413`), and there is no
  finalization-order hazard, cross-edge property reference, or schema-explain
  ambiguity to design around.

## Implementation Touchpoints

- **`validateM2MOrientation`** (`EntGenerator.kt:194-288`): "reject size > 1"
  → "reject size > 2"; no set-owner logic.
- **Edge metadata**: carry one `readOnly` boolean from the builder to the edge
  model. No new `ManyToManyThrough` variant — both sides stay `LinkTable`.
- **`helperEligibleM2MEdges`** (`HelperEligibleM2M.kt:64-93`): still filters to
  `LinkTable`; both endpoints now pass; exclude `readOnly` sides.
- **`UpdateGenerator`** (`:79-84`, `:237-245`, junction writes `:1500-1551`):
  emit `add` / `remove` / `set` on every helper-eligible (non-`readOnly`) side.
- **Mutation / save surface**: a `.serializedWrites()` option that wraps the
  junction writes in a relationship advisory lock — ideally via the existing
  `UpdateConsistency` / save-options path.
- **`PrivacyGenerator`** (`:63-68`, `:114-128`): `pendingEdges` /
  `EdgeChanges` aggregators appear on both writable sides.
- **Read path** (`Query.kt` `queryX` / `withX` / `EdgeRef`): unchanged.
- **Schema explain** (`SchemaInspector.buildEdges`): surface `readOnly` so
  write eligibility is auditable.

## Test Requirements

- Read parity: a second-side `throughLink` matches the owner side's
  `queryX` / `withX` / `exists` / `has { … }` results.
- `add` / `remove` parity: `post.tags.add(t)` and `tag.posts.add(p)` produce
  identical junction rows; both sides round-trip.
- `set` from both sides: works from either side; a `readOnly` side has no
  `add` / `remove` / `set`.
- `.serializedWrites()`: two opted-in concurrent `set`s serialize (no
  deadlock); a writer that omits it is not protected (documented).
- Validation: three `throughLink` on one identity reject; `.readOnly()`
  suppresses writes.
- Compatibility: every existing single-side `throughLink` fixture generates
  byte-identical code.
