# RFC: Delete Consistency

## Status

Possible future feature. This is not implemented.

The problem this RFC addresses is real in the current implementation. The
delete pipeline reloads the row and runs DELETE privacy, validation, and
hooks against fresh state, but the eventual `DELETE` statement matches only
the row id. The update path already names this class of window and offers an
opt-in fix (`UpdateConsistency.Pessimistic`, implemented in
[Transaction And Locking Semantics For Edge Mutations](../../implemented-features/edge-mutation/04-transaction-locking-semantics.md));
deletes have no equivalent.

## Summary

Delete authorization races the delete. Between the reload that privacy,
validation, hooks, and `deleteMany` candidate selection evaluate and the
id-only `DELETE` that follows, a concurrent transaction can change the row so
that its current state would fail those checks — and the row is deleted
anyway.

This RFC splits the problem into three parts with different owners:

1. **`deleteMany` predicate reassertion** — framework-owned and
   unconditional. The per-row `DELETE` re-asserts the effective candidate
   predicates (caller predicates plus interceptor predicates) in its own
   `WHERE` clause, so a row that stopped matching is skipped, not deleted.
   This is not a locking feature; it is the framework keeping an existing
   promise.
2. **`DeleteConsistency.Pessimistic`** — caller-chosen, opt-in. Mirror
   `UpdateConsistency`: route the delete pipeline's reload through
   `Driver.readRowForUpdate` so the checked state stays stable through the
   `DELETE`.
3. **Optimistic deletes** — caller-chosen, deferred to existing RFCs.
   [Compare-And-Set Mutations](compare-and-set-mutations.md) and
   [Optimistic Locking](../schema/optimistic-locking.md) already define
   delete preconditions evaluated inside the write; this RFC only pins down
   how their failures surface for deletes.

The default posture stays lock-free (`ReadCurrent`), matching updates.

## Motivation

### The window

The generated delete pipeline is load → check → delete-by-id:

- `deleteOrError(entity)` and `deleteByIdOrError(id)` reload via the bare
  driver (`byId`), then call the internal `deleteLoaded(entity)`, which runs
  DELETE privacy, validation, and before-hooks against the loaded entity and
  then issues `driver.delete(table, entity.id)`.
- `deleteMany(predicates)` selects candidates once through the
  read-interceptor chain, then loops `deleteLoaded` over the results.

The `DELETE` statement's `WHERE` clause carries only the id. Nothing
re-asserts the state the checks approved. Inside the (possibly required)
transaction at READ COMMITTED, a concurrent transaction can commit a change
between the load and the delete; the `DELETE` then waits on the row lock,
re-evaluates only `id = ?` against the new row version, and deletes it.

Concretely:

- a row can be mutated so DELETE privacy would now deny the viewer, and
  still be deleted;
- a row can be mutated so delete-side validation would now reject, and
  still be deleted;
- a `deleteMany` candidate can stop matching the caller's predicates — or
  the interceptor predicates — and still be deleted. The candidate list is
  loaded once up front, so this window grows with the row's position in the
  loop.

Higher isolation levels change the failure mode, not the design: at
REPEATABLE READ or SERIALIZABLE (PostgreSQL), the concurrent update makes
the `DELETE` fail with a serialization error instead of deleting the wrong
state. The framework does not require those levels, so the algebra must be
correct at READ COMMITTED.

### The asymmetry with updates

`UpdateConsistency` documents this exact window for updates ("another
transaction may change the owner row's scalar fields or delete it between
the read and the write") and offers `Pessimistic` as the opt-in fix using
`Driver.readRowForUpdate`. The driver capability exists and is implemented;
the delete path simply never uses it. The reload rationale documented on
`deleteOrError` — never authorize against a caller-controlled entity copy —
addresses a different threat (fabricated or stale input), not the
concurrency window after the reload.

### The broken promise in `deleteMany`

The generated `deleteMany` documents that candidates flow through the
read-interceptor chain "so a bulk delete can NOT escape read-side scoping
that would have hidden the same rows on the read path" — tenant scoping,
framework soft-delete, and similar. That is a framework guarantee, not
caller business logic, and the candidate-query-then-delete-by-id gap can
break it: a soft-delete flag flipped mid-loop is not exotic. A caller cannot
be responsible for upholding an invariant the framework asserted.

### The privacy tension

The privacy model is deliberately fail-closed and framework-authoritative:
callers cannot skip DELETE privacy. Yet its delete-time integrity currently
depends on caller locking discipline that the API cannot even express. Every
ORM at READ COMMITTED has this window, and making closure the default would
impose locking costs most deletes do not need — but the framework should
state the window explicitly and give callers a first-class way to close it,
precisely because it otherwise markets privacy as not the caller's problem.

## Design Principles

1. **The default stays lock-free.** `ReadCurrent` semantics remain the
   default for deletes, matching updates. Consistency strength is a
   per-operation caller choice, not a global framework imposition.
2. **Framework guarantees are not delegable.** Read-side scoping of bulk
   deletes is the framework's promise; keeping it cannot require the caller
   to opt into anything.
3. **Checks authorize current state or provably stable state.** Either the
   checked row is locked through the write (`Pessimistic`), or the write
   itself re-asserts what must still hold (predicate reassertion, CAS).
   A check against state that is neither locked nor re-asserted is
   documented as such.
4. **A skipped row is not an error; a failed expectation is.** Reassertion
   skipping a no-longer-matching `deleteMany` row is normal operation and
   affects only the count. A caller-stated CAS expectation failing is a
   structured, distinguishable outcome.
5. **Same knobs, same names.** The delete surface mirrors the update
   surface: enum shape, preflights, exceptions, and driver capabilities are
   shared or parallel, per the principle of least surprise.

## Non-Goals

- Do not change the default isolation level or require SERIALIZABLE.
- Do not make `Pessimistic` the default for any entity in this RFC.
- Do not design the CAS builder surface here; that belongs to
  [Compare-And-Set Mutations](compare-and-set-mutations.md).
- Do not add version columns here; that belongs to
  [Optimistic Locking](../schema/optimistic-locking.md).
- Do not redesign bulk preflight semantics; see
  [Preflighted Bulk Operations](../privacy-validation/preflighted-bulk-operations.md).

## Part 1: `deleteMany` Predicate Reassertion (Unconditional)

The per-row delete issued by `deleteMany` re-asserts the effective
predicates from candidate selection:

```sql
DELETE FROM posts WHERE id = ? AND <caller predicates> AND <interceptor predicates>
```

- Zero rows affected means the row changed (or vanished) since candidate
  selection; the row is skipped and not counted. After-delete hooks do not
  run for it.
- The returned count remains "rows actually deleted", which this change
  makes more truthful, not less.
- This is lock-free and adds no driver capability requirement; it reuses
  the predicate-rendering machinery the candidate query already used.

Limits: reassertion covers the *predicates*, not per-row privacy or
validation — those evaluate the candidate-time snapshot and remain subject
to the (now documented) window unless the caller also chooses `Pessimistic`.
Closing the privacy window unconditionally would require locking or
re-running checks against a re-read inside the write, which is exactly the
cost `ReadCurrent` exists to avoid.

Single-entity deletes have no candidate predicates, so this part does not
apply to them.

## Part 2: `DeleteConsistency.Pessimistic` (Opt-In)

Mirror the update surface:

```kotlin
enum class DeleteConsistency { ReadCurrent, Pessimistic }
```

- `EntClient` exposes `defaultDeleteConsistency`; generated delete entry
  points take an optional per-call override, like the generated
  `update(id, consistency, block)` factory.
- `Pessimistic` preflights exactly as updates do, before any observable
  work: no transaction → `TransactionRequiredException`; driver without
  `supportsReadRowForUpdate` → `UnsupportedDriverCapabilityException`.
- The pipeline's reload routes through `driver.readRowForUpdate(table, id)`
  instead of `byId`. The row lock holds through privacy, validation, hooks,
  and the `DELETE`, closing the window completely for that row.
- `ReadCurrent` keeps the existing `byId` path untouched.

For `deleteMany` under `Pessimistic`, each candidate is re-read under
`readRowForUpdate` before its per-row checks, and checks evaluate the locked
fresh row rather than the candidate-time snapshot. Combined with Part 1's
reassertion (which the locked re-read makes cheap to evaluate in-process or
keep in the `DELETE`), this closes both the predicate and the check windows.
Lock ordering across candidates must be deterministic (e.g. sorted by id) to
avoid avoidable deadlocks between concurrent bulk deletes.

Whether `DeleteConsistency` and `UpdateConsistency` should be one shared
`WriteConsistency` enum is an open decision; the semantics are identical,
but per-operation defaults (`defaultUpdateConsistency`,
`defaultDeleteConsistency`) likely remain separate knobs either way.

## Part 3: Optimistic Deletes (Deferred To Existing RFCs)

Callers who want stale-delete detection without locks state expectations
that the write itself evaluates:

- [Compare-And-Set Mutations](compare-and-set-mutations.md) already sketches
  `delete(post).cas { expectNull(PostSchema.deletedAt) }` with the
  expectations folded into the `DELETE`'s `WHERE` clause.
- [Optimistic Locking](../schema/optimistic-locking.md) provides the
  version-column flavor; if both land, version checking is sugar over CAS.

This RFC adds only the delete-specific contract those RFCs need:

- A zero-rows-affected CAS delete must be distinguishable from an absent
  target. That requires a follow-up existence probe (or `RETURNING`-based
  detection) to classify the outcome as target-absent versus
  expectation-failed; the probe runs inside the same transaction.
- In the [Canonical Operation Result Algebra](../api/operation-result-algebra.md),
  an expectation failure surfaces as the `Conflict` variant (which already
  reserves optimistic-lock failures), never as `TargetAbsent`, a generic
  failure, or a silent `false`.

## Responsibility Model

| Window | Owner | Mechanism |
| --- | --- | --- |
| `deleteMany` row stops matching caller/interceptor predicates | Framework, unconditional | Predicate reassertion in the `DELETE` |
| Row state checked by privacy/validation/hooks goes stale | Caller, opt-in | `DeleteConsistency.Pessimistic` |
| Business-state precondition (status, ownership, soft-delete flag) | Caller, opt-in | CAS expectations / optimistic version |
| Residual window under `ReadCurrent` without CAS | Documented | Explicit docs on the delete surface |

## Open Decisions

- One shared `WriteConsistency` enum versus parallel `UpdateConsistency` /
  `DeleteConsistency` enums with identical shapes.
- Whether Part 1's reassertion keeps the predicates in the per-row `DELETE`
  or re-evaluates them in-process against a `Pessimistic` locked re-read
  when that mode is active (identical outcomes; different SQL).
- Whether `deleteMany` should report skipped-by-reassertion rows (e.g. a
  richer bulk result) or keep the plain deleted count. The result-algebra
  RFC deliberately defers bulk partial-success semantics.
- Whether `Pessimistic` `deleteMany` locks candidates one at a time in
  sorted-id order or acquires locks via a single locking candidate query
  (relates to [Query `forUpdate()` Row Locking](../query/for-update-query-locking.md)).
- Whether privacy-sensitive entities should be able to declare a schema-level
  minimum delete consistency (forcing `Pessimistic`), or whether per-call
  choice plus the client default is enough.
- How the CAS-delete existence probe interacts with drivers lacking
  `RETURNING`-style support.

## Test Requirements

Before implementation, add tests for:

- `deleteMany` does not delete a row concurrently mutated to stop matching
  caller predicates (reassertion skips it; count excludes it)
- `deleteMany` does not delete a row concurrently mutated to escape
  interceptor predicates (tenant scoping, soft-delete)
- after-delete hooks do not run for reassertion-skipped rows
- `Pessimistic` delete preflights transaction and driver capability before
  any observable work, matching update preflight order
- `Pessimistic` delete evaluates privacy, validation, and hooks against the
  locked row and the checked state cannot change before the `DELETE`
- `Pessimistic` `deleteMany` acquires row locks in deterministic order
- `ReadCurrent` delete behavior is byte-identical to today's pipeline
- CAS delete distinguishes target-absent from expectation-failed, and
  expectation failure maps to the structured conflict shape
- documentation on the delete surface states the residual `ReadCurrent`
  window explicitly
