# RFC: Through-Entity Nullable M2M Traversal

## Status

**V1 implemented.** No new runtime or codegen code was needed —
both lowerings already drop null junction FKs via standard SQL
three-valued logic (`= ?` and `IN (...)` return `UNKNOWN` when
the operand is `NULL`). The acceptance work was pinning that
behavior with end-to-end tests so future regressions surface
loudly.

Test coverage lives in
`integration-tests/src/test/kotlin/entkt/integrationtest/ThroughEntityNullableM2MTraversalIntegrationTest.kt`:
16 Postgres-backed cases covering all three traversal shapes
(query-chain `queryUsers()` / `queryGroups()`, predicate
`EdgeRef.exists()` / `EdgeRef.has { where(...) }`, eager
`withUsers` / `withGroups`) × both directions (`Group → User`
forward and `User → Group` via the pair-swapped junction edge
on [User]) × both null shapes (junction source FK null, junction
target FK null). The supporting fixtures —
[Membership] (junction with nullable
`belongsTo<Group>().nullable()` + `belongsTo<User>().nullable()`),
[Group], and the inverse `User.groups` edge — were added to
`integration-tests/schema/` to model exactly this scenario.

Extracted from the
[Many-To-Many Schema Modeling](03-m2m-schema-modeling.md) RFC.

## Summary

Pin and test traversal semantics for `throughEntity(...)` many-to-many edges
whose junction `belongsTo` edges are nullable.

`throughEntity(...)` junctions are domain entities. They may carry nullable
source or target FKs because callers mutate them through the junction repo and
can model partial or staged relationships. Traversal over those rows should use
inner-join semantics: rows with `NULL` on either junction FK are ignored by M2M
edge traversal.

## Proposed Semantics

For:

```kotlin
class Membership : EntSchema("memberships") {
    override fun id() = EntId.long()
    val group = belongsTo<Group>("group").nullable()
    val user = belongsTo<User>("user").nullable()
}

class Group : EntSchema("groups") {
    override fun id() = EntId.long()
    val users = manyToMany<User>("users")
        .throughEntity<Membership>(Membership::group, Membership::user)
}

// Companion target schema (omitted: the `User` schema with its
// own `override fun id()` declaration).
```

Traversal behaves as follows:

- a row with both `group_id` and `user_id` set contributes one `Group.users`
  membership
- a row with `group_id = NULL` contributes no membership
- a row with `user_id = NULL` contributes no membership
- direct `client.memberships` queries still expose the nullable FK values

This applies consistently to:

- **Query-chained traversal** — `client.groups.query().where(...).queryUsers()`
  on `GroupQuery` (the generated `queryX()` traversal members live on
  the source entity's `Query` class, not on entity instances). A
  single-entity traversal is written as
  `client.groups.query().where(Group.id eq group.id).queryUsers()`.
- **Predicate traversal** — both shapes that the `EdgeRef` API exposes:
  - **`Group.users.exists()`** for plain existence — lowers to
    `Predicate.HasEdge` (a simple "any related row at all" check
    walking the junction table).
  - **`client.groups.query().where(Group.users.has { where(User.someField eq value) })`**
    for filtered existence — lowers to `Predicate.HasEdgeWith` (the
    target-filtered EXISTS subquery walking the junction table and
    the target rows it joins to).
- **Eager loading** — `client.groups.query().withUsers { ... }.allOrThrow()`,
  which fetches junction rows in one driver call and target rows
  in a second, then groups targets back to their source rows.
- **Inverse traversal** implemented by a pair-swapped
  `throughEntity(...)` declaration on the opposite side (`User.groups`
  using `Membership::user, Membership::group`).

## Implementation Notes

Two distinct lowering shapes serve M2M traversal — both should
naturally skip null FK values, but the RFC's integration tests
need to pin each independently because the SQL each produces is
not the same:

### Query-chain + predicate traversal (single SQL statement)

`queryUsers()` lowers to an EXISTS subquery against the junction
table joined to the target table. The `EdgeRef` predicate API
exposes two shapes that share the same EXISTS skeleton:
`Group.users.exists()` (lowers to `Predicate.HasEdge`) and
`Group.users.has { where(...) }` (lowers to
`Predicate.HasEdgeWith` with the target-side filter folded into
the subquery). Both rely on equality predicates on the
junction's source / target FK columns, which naturally drop
rows where either FK is NULL — SQL's three-valued logic makes
`junction.source_col = ?` evaluate to `UNKNOWN` (not `TRUE`)
when `source_col IS NULL`, so the row fails the EXISTS
condition regardless of which predicate shape walked the
subquery.

### Eager loading (two-step driver call)

`withUsers { … }` does **not** issue a single SQL join. It runs
in two driver calls:

1. `driver.query(junctionTable, listOf(Predicate.Leaf("source_col", IN, sourceIds)), …)`
   — fetches junction rows whose source FK is in the parent's
   id set. Rows with `source_col IS NULL` fail the `IN` test
   (SQL `IN` with NULL on the left → UNKNOWN), so they're
   naturally skipped here.
2. `driver.query(targetTable, listOf(Predicate.Leaf("id", IN, targetIds)), …)`
   — fetches target rows whose primary key matches the
   distinct target FK values pulled from step 1. The target
   table's id is non-null (primary key), so a junction row
   with `target_col IS NULL` either (a) contributes `null` to
   `targetIds` and fails the `IN` test the same way step 1
   did, or (b) is filtered out by the codegen before
   constructing `targetIds`. Either way it doesn't surface as
   a membership.

The two-step shape exists so that eager-load supports
per-source pagination, interceptors on the target entity, and
ordering on the target's columns — none of which a single
SQL join would express cleanly given the source-grouping the
runtime does afterward.

### Why this RFC may still be a no-op on the implementation side

Both lowerings rely on standard SQL NULL semantics (`= ?` and
`IN (...)` return UNKNOWN when the left side is NULL, which
fails any boolean test). The integration tests called out in
the acceptance criteria are the necessary work; if a test ever
finds a runtime gap (e.g., a future driver that maps NULL
inputs differently, or a step-1 query that doesn't materialize
the IS NULL skip correctly), code changes would land in the
respective lowering — not in the schema-level traversal
definition.

## Non-Goals

- Do not allow nullable junction FKs for `throughLink(...)`; helper-eligible
  link tables must remain non-null.
- Do not synthesize reverse edges.
- Do not change how the junction repo reads or writes nullable FKs.

## Acceptance Criteria

- Integration tests prove nullable source and target junction FKs are
  skipped for **query-chained traversal** (`GroupQuery.queryUsers()`).
  Both null-source and null-target variants must be exercised in
  separate test cases so a regression in one side can't be hidden by
  the other.
- Integration tests prove nullable source and target junction FKs are
  skipped for **predicate traversal**. Cover both `EdgeRef` shapes
  the API exposes:
  - `Group.users.exists()` — lowers to `Predicate.HasEdge` (plain
    existence walking the junction table).
  - `Group.users.has { where(User.someField eq value) }` — lowers
    to `Predicate.HasEdgeWith` (target-filtered EXISTS walking
    the junction + target rows).

  Both share an EXISTS-subquery lowering shape distinct from
  `queryX()`, so each needs its own null-skip pin.
- Integration tests prove nullable source and target junction FKs are
  skipped for **eager loading** (`withUsers { ... }`). The two-step
  junction-fetch + target-fetch lowering is a third distinct shape;
  its null-skip behavior must be verified independently.
- Pair-swapped `throughEntity(...)` traversal follows the same rule in
  both directions across all three shapes above (query-chain,
  predicate, eager).
- User-facing docs state the skip-null behavior.
