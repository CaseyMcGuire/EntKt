# RFC: Delete Consistency

## Status

Partially implemented. `deleteMany` already reasserts frozen caller and
interceptor predicates in the write. Opt-in pessimistic delete consistency and
optimistic delete preconditions remain unimplemented.

## Implemented Baseline

Scalar `delete(viewerContext, entity)` and `deleteById(viewerContext, id)` reload
the current row through `DatabaseDriver.byId`, then evaluate DELETE privacy,
validation, and before-delete hooks before issuing the ID-based delete. The
reload prevents authorization against a caller-supplied entity copy; it does
not lock the row through persistence.

`deleteMany(viewerContext, predicates...)` selects candidates through read
interceptors, then runs phase-major privacy, validation, and hooks. It sends
approved IDs and frozen effective predicates to `DatabaseDriver.deleteManyByIds`
inside the caller's transaction or an EntKt-owned transaction. Rows that no
longer match are skipped, excluded from the count, and excluded from
`afterDelete` hooks.

This is implemented by
[DeleteManyMutationOperation](../../../runtime/src/main/kotlin/entkt/runtime/mutation/execution/DeleteManyMutationOperation.kt)
and covered by
[DeleteManyIntegrationTest](../../../integration-tests/src/test/kotlin/entkt/integrationtest/DeleteManyIntegrationTest.kt).
See [Batch-Aware Lifecycle Evaluation](../../implemented-features/privacy-validation/batch-aware-lifecycle-evaluation.md)
for the delivered bulk contract. Predicate reassertion is no longer planned
work in this RFC.

## Remaining Concurrency Window

Predicate reassertion protects bulk selection criteria. It does not re-evaluate
arbitrary Kotlin privacy or validation rules against the row version deleted.
A concurrent writer can change a checked row between its read and the delete
while leaving the frozen bulk predicates true. Scalar deletes have the same
check-then-write window without bulk selection predicates.

The update path already offers `UpdateConsistency.Pessimistic`, which uses
`DatabaseDriver.readRowForUpdate` to keep the owner row stable. Deletes lack an
equivalent option. This RFC retains that opt-in extension and the connection
to future compare-and-set and version checks.

## Design Principles

- Keep the default read-current behavior without adding a pre-check row lock.
- Preserve unconditional bulk predicate reassertion in every consistency mode.
- Make stronger consistency an explicit client default or per-operation choice.
- Distinguish a normal skipped bulk row from a failed caller-stated expectation.
- Use terminology and preflight behavior consistent with update locking.
- Keep transaction, failure, and lifecycle orchestration in shared runtime code.

## Proposed Pessimistic Delete API

Mirror the update surface:

```kotlin
enum class DeleteConsistency { ReadCurrent, Pessimistic }
```

A proposed `defaultDeleteConsistency` client setting and per-call override
would select the mode. The names are tentative; no such delete setting exists
today.

`Pessimistic` would:

1. Preflight the active transaction and `supportsReadRowForUpdate` before
   observable lifecycle work, matching update preflight order.
2. Reload the row through `readRowForUpdate` instead of `byId`.
3. Evaluate privacy, validation, and hooks against the locked row.
4. Hold the lock through the delete and the surrounding transaction boundary.

Missing transactions and unsupported drivers should use the existing
`TransactionRequiredException` and `UnsupportedDriverCapabilityException`
contracts. Locking stabilizes the owner row; it does not automatically lock
related rows consulted by application rules.

For `deleteMany`, acquire candidate locks in a deterministic order and build
the lifecycle batch from the locked, still-eligible rows. Preserve the current
phase-major callbacks and the frozen predicates in the eventual write. Do not
reintroduce a per-row check/write loop or authorize a locked write from an
older unlocked candidate snapshot.

## Optimistic Deletes

[Compare-And-Set Mutations](compare-and-set-mutations.md) and
[Optimistic Locking](../schema/optimistic-locking.md) own the expectation API and
version-column design. Deletes would fold those expectations into the write's
`WHERE` clause.

A failed expectation must be distinguishable from ordinary target absence.
The implementation must define how to classify a zero-row write without
introducing another race through an uncoordinated existence probe. A
transaction alone does not make separate read-committed statements share one
snapshot. Driver-specific returning or locking strategies need an explicit
contract.

Use the current [operation result algebra](../../implemented-features/api/operation-result-algebra.md):
a conflict is `MutationResult.Failed` carrying an `EntConflictException` and
its write state, not a new top-level `Conflict` variant or silent `false`.
Normal absence retains the current delete acknowledgement behavior. This RFC
does not define a new bulk partial-success result.

## Non-Goals

- Changing default isolation or requiring SERIALIZABLE.
- Making pessimistic locking mandatory for every delete.
- Redesigning the CAS API, version fields, or atomic bulk preflight.
- Implicitly locking every relationship a privacy rule might consult.

## Open Decisions

- Shared `WriteConsistency` versus parallel update/delete enums.
- Deterministic per-candidate locks versus one locking candidate query; see
  [Query Row Locking](../query/for-update-query-locking.md).
- Behavior when a candidate disappears or leaves the effective predicate set
  before its lock is acquired, while retaining the current skip/count contract.
- Whether schemas may require a minimum delete consistency or client defaults
  and per-call choices are sufficient.
- Race-safe CAS absence/conflict classification across driver capabilities.

## Test Requirements For Remaining Work

Keep existing predicate-reassertion, callback, transaction, and acknowledgement
coverage. Add tests when implementing the new consistency modes:

- pessimistic preflight occurs before hooks, rules, or reads;
- privacy, validation, and hooks receive locked current rows;
- concurrent writers cannot change the locked owner before deletion;
- bulk lock order is deterministic and callbacks remain phase-major;
- vanished or ineligible candidates preserve skip/count/hook semantics;
- read-current behavior and unconditional predicate reassertion are unchanged;
- CAS distinguishes absence from expectation failure and preserves write state;
- documentation states the remaining owner/related-row consistency boundaries.
