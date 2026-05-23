# RFC: Through-Entity Nullable M2M Traversal

## Status

Possible future feature. This is not implemented.

Extracted from the implemented
[Many-To-Many Schema Modeling](../../implemented-features/edge-mutation/03-m2m-schema-modeling.md)
RFC.

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
    val group = belongsTo<Group>("group").nullable()
    val user = belongsTo<User>("user").nullable()
}

class Group : EntSchema("groups") {
    val users = manyToMany<User>("users")
        .throughEntity<Membership>(Membership::group, Membership::user)
}
```

Traversal behaves as follows:

- a row with both `group_id` and `user_id` set contributes one `Group.users`
  membership
- a row with `group_id = NULL` contributes no membership
- a row with `user_id = NULL` contributes no membership
- direct `client.memberships` queries still expose the nullable FK values

This applies consistently to:

- `group.queryUsers()`
- `client.groups.query().where(Group.edges.users.has(...))`
- eager loading via `withUsers { ... }`
- inverse traversal implemented by pair-swapped `throughEntity(...)`
  declarations

## Implementation Notes

The PostgreSQL lowering already uses normal joins and equality predicates for
M2M traversal, which should naturally skip null FK values. This RFC may only
require targeted integration tests and documentation updates unless those tests
find a runtime gap.

## Non-Goals

- Do not allow nullable junction FKs for `throughLink(...)`; helper-eligible
  link tables must remain non-null.
- Do not synthesize reverse edges.
- Do not change how the junction repo reads or writes nullable FKs.

## Acceptance Criteria

- Integration tests prove nullable source and target junction FKs are skipped
  for query traversal.
- Integration tests prove nullable source and target junction FKs are skipped
  for eager loading.
- Pair-swapped `throughEntity(...)` traversal follows the same rule in both
  directions.
- User-facing docs state the skip-null behavior.
