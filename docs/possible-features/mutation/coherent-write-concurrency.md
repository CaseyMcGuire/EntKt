# RFC: Coherent Write Concurrency Model

## Status

Possible future feature. This is not implemented.

## Summary

Unify EntKt's write-concurrency concepts under one explicit model:

- current-row observation
- compare-and-set preconditions
- version-based optimistic locking
- pessimistic row locking
- transaction isolation and savepoints

Updates and deletes should use the same terminology, capability checks, and
structured conflict outcome.

## Motivation

The current and proposed surfaces are distributed across several features:

- updates can request pessimistic consistency
- delete consistency is a separate proposal
- compare-and-set and optimistic versioning are separate proposals
- query-level `forUpdate()` and transaction options are separate proposals

Each feature is useful, but applications need one mental model for answering:

```text
What state did this write authorize, and what prevents that state from changing
before persistence?
```

## Model

Every write may combine three independent choices.

### Observation

The framework may load the current row so hooks, privacy, and validation can
evaluate it. Under ordinary read-committed behavior, that observation can
become stale before the write.

### Preconditions

The write statement may reassert expected state:

- a version value
- one or more field values
- null or non-null state
- frozen candidate-selection predicates for bulk deletes

Preconditions provide optimistic concurrency without holding locks.

### Locking

The framework may lock the current row through the write. Pessimistic locking
requires an active transaction and an explicit driver capability.

Transaction isolation still matters, but it is not a substitute for stating
the row-level expectation an operation needs.

## Shared Terminology

Use one consistency type for update and delete entry points:

```kotlin
enum class WriteConsistency {
    ReadCurrent,
    Pessimistic,
}
```

Per-client defaults can remain operation-specific if useful, but the enum and
semantics should not diverge.

`ReadCurrent` means the framework observes the current row without promising it
will remain unchanged. `Pessimistic` means the observed row is locked through
the write.

Optimistic preconditions are orthogonal:

```kotlin
client.posts.update(post.id) {
    status = Status.PUBLISHED
}.expect {
    field(Post.status, Status.DRAFT)
}.save()
```

## Versioned Entities

Version locking is explicit schema opt-in:

```kotlin
class Post : EntSchema("posts") {
    override fun id() = EntId.long()
    val version = version("version")
}
```

For a versioned entity:

```sql
UPDATE posts
SET title = ?, version = version + 1
WHERE id = ? AND version = ?
```

`update(entity)` naturally carries the entity's expected version. An ID-only
update must either state an expectation or clearly retain last-write-wins
semantics. The two entry points must not look equivalent while silently using
different concurrency guarantees.

Deletes follow the same rule:

```sql
DELETE FROM posts
WHERE id = ? AND version = ?
```

## Compare-And-Set

CAS expectations are typed field predicates folded into the final `UPDATE` or
`DELETE`, not checked only in a preliminary read.

V1 should support scalar equality and null checks on the target row. Arbitrary
cross-table predicates and application callbacks are out of scope.

Version locking can be implemented as generated CAS sugar, but its public
schema contract remains first-class because it increments and returns the new
version automatically.

## Conflict Classification

Zero affected rows can mean either:

- the target is absent
- an optimistic precondition failed
- a frozen effective predicate no longer matches

The framework must classify these states reliably, using `RETURNING`, a
same-transaction existence probe, or a driver-specific primitive.

Expectation failure becomes a structured `EntConflictException`, never
`EntTargetAbsentException`, `Success(false)`, or an unclassified driver error.
The exception should include:

- entity type and key
- operation
- expectation kind
- expected version or field names
- `MutationWriteState.NotPersisted`

Actual protected values should be omitted from messages when fields are
sensitive.

## Privacy, Validation, And Hooks

Concurrency enforcement must align with lifecycle evaluation:

- `Pessimistic` checks evaluate the locked row.
- CAS checks are reasserted in the final write even if privacy and validation
  evaluated an earlier snapshot.
- A conflict performs no after-persist or after-commit hooks.
- Privacy denial and validation failure remain distinct from conflict.
- Capability and transaction preflights occur before hooks or driver reads.

Applications that require authorization against state that cannot change must
choose pessimistic locking or express that state as CAS. EntKt should document
the residual window under plain `ReadCurrent` rather than imply it is closed.

## Bulk Operations

Bulk candidate predicates owned by EntKt must be reasserted in the final write.
A row that stops matching is skipped and excluded from the affected count.

Caller-specified per-row CAS for heterogeneous bulk updates is not part of V1.
If introduced later, its result must represent per-item conflicts explicitly
rather than collapsing them into a single count.

## Driver Capabilities

Drivers report support for:

- row locking
- update/delete with typed preconditions
- affected-row classification
- `RETURNING` or equivalent outcome metadata

Unsupported pessimistic behavior fails before lifecycle work. CAS should not
fall back to a read-then-write check that reopens the race.

## Test Requirements

- stale versioned update and delete return structured conflicts
- successful versioned update increments and returns the version
- ID-only and entity-based entry points document and enforce distinct guarantees
- scalar and null CAS predicates are present in the final SQL write
- absent target and failed expectation are classified separately
- updates and deletes share `WriteConsistency` semantics and preflights
- pessimistic checks evaluate a locked row through persistence
- conflict runs no after-persist or after-commit callbacks
- frozen bulk-delete predicates are reasserted
- sensitive expected values do not appear in diagnostics

## Related Features

- [Optimistic Locking](../schema/optimistic-locking.md)
- [Compare-And-Set Mutations](compare-and-set-mutations.md)
- [Delete Consistency](delete-consistency.md)
- [Query `forUpdate()` Row Locking](../query/for-update-query-locking.md)
- [Transaction Options And Savepoints](transaction-options-savepoints.md)
