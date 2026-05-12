# RFC: Preflighted Bulk Operations

## Status

Possible future enhancement. This is not implemented.

## Summary

Change generated repo-level bulk APIs so privacy and validation are checked
for every item before any database write starts.

The intended contract:

```text
build all candidates
run all privacy checks
run all validation checks
if any item is denied or invalid, write nothing
otherwise perform the writes
```

This makes generated bulk calls behave more like one logical application
operation instead of a loop that can partially mutate before discovering a
later item is unauthorized or invalid.

## Motivation

Current per-item delegation is simple and preserves the normal create/delete
pipeline, but it has unintuitive failure behavior:

```kotlin
client.posts.createMany(
    { title = "A"; authorId = alice.id },
    { title = ""; authorId = alice.id },
)
```

If the second item fails validation after the first item has already been
inserted, callers can observe a partial result unless they explicitly wrap the
operation in a transaction.

For generated convenience APIs, users usually expect framework-level checks to
complete before writes begin:

- all privacy decisions are known before mutation
- all validation errors can be reported together
- no after-hooks fire if a later item was already known to be invalid
- denied or invalid bulk calls do not partially write due only to framework
  preflight order

## Non-Goals

- Do not change low-level `Driver.insertMany`, `Driver.updateMany`, or
  `Driver.deleteMany`; those remain driver primitives.
- Do not promise database atomicity outside a transaction.
- Do not replace database constraints for uniqueness, foreign keys, or
  relationship integrity.
- Do not add batch-aware privacy or validation rule types in this feature.
- Do not push arbitrary validation or privacy checks into SQL.

## Proposed Semantics

Generated repo-level bulk methods become **framework-preflighted**:

- build every mutation candidate first
- run before-hooks that mutate candidates before preflight
- run field validation, privacy, and entity validation for every item
- collect validation errors across all invalid items
- stop before driver writes if any privacy or validation check fails
- start database writes only after every item passes

They are **not database-atomic** unless the caller uses a transaction:

```kotlin
client.withTransaction { tx ->
    tx.posts.createMany(
        { title = "A"; authorId = alice.id },
        { title = "B"; authorId = alice.id },
    )
}
```

If a database constraint fails after the preflight phase, earlier writes in
the same bulk call may still be persisted unless the operation is inside a
transaction.

## Create Many

Proposed generated pipeline:

```text
1.  instantiate all create builders
2.  run beforeSave and beforeCreate hooks for all builders
3.  apply defaults for all builders
4.  run field validation for all builders
5.  build all WriteCandidates
6.  evaluate CREATE privacy for all candidates
7.  evaluate create validation for all candidates
8.  if any privacy or validation check failed, throw before writing
9.  insert rows
10. hydrate inserted entities
11. run afterCreate hooks
12. run returned LOAD privacy
13. return entities
```

Open questions:

- Should returned LOAD privacy also be preflighted for all inserted entities
  before any after-hooks run, or should it keep normal single-create ordering?
- Should `createMany` use `Driver.insertMany` after preflight when no hooks
  require per-row behavior, or always preserve the simple per-row write path?

## Delete Many

Proposed generated pipeline:

```text
1.  raw-query matching entities without LOAD privacy
2.  build delete candidates for all matched entities
3.  evaluate DELETE privacy for all entities
4.  evaluate delete validation for all entities
5.  if any privacy or validation check failed, throw before deleting
6.  run beforeDelete hooks
7.  delete rows
8.  run afterDelete hooks
9.  return deleted count
```

This keeps delete authorization governed by DELETE privacy rather than LOAD
privacy, while avoiding a partial delete caused by a later entity failing
validation.

## Validation Error Shape

Bulk validation should preserve item context. A future structured error model
could expose something like:

```kotlin
data class BulkValidationError(
    val index: Int,
    val entity: String,
    val errors: List<String>,
)
```

Until then, generated code can include item indexes in validation messages.

## Constraint Races

Preflighted validation does not remove database races. For example, a
`UniqueSlug` validator can pass for every item and still lose to a concurrent
insert before the database write.

Applications should continue to enforce invariants with database constraints:

- `UNIQUE` for uniqueness
- foreign keys for relationship integrity
- transactions for all-or-nothing persistence
- locks or serializable transactions for stronger concurrency guarantees

The preflight phase improves framework-level semantics and error reporting; it
does not replace storage-level correctness.

## Test Requirements

Before implementation, add tests for:

- `createMany` runs all create privacy checks before the first insert
- `createMany` runs all create validators before the first insert
- validation errors include enough item context to identify failing inputs
- if any create item is denied, no rows are inserted by framework order
- if any create item is invalid, no rows are inserted by framework order
- `deleteMany` runs all delete privacy checks before the first delete
- `deleteMany` runs all delete validators before the first delete
- if any delete item is denied or invalid, no rows are deleted by framework
  order
- driver bulk primitives remain hookless and privacy-unaware
- transaction-wrapped bulk calls are database-atomic for driver failures
