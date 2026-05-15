# RFC: Transaction And Locking Semantics For Edge Mutations

## Status

Partially implemented. The transaction-neutral generated save model, the
`TransactionRequirement` runtime guardrail, the `UpdateConsistency.Pessimistic`
per-save mode, and the driver capability surface that backs both all landed.
Specifically implemented:

- **Driver capability surface.** `Driver.inTransaction`,
  `supportsReadRowForUpdate` + `readRowForUpdate(table, id)`, and
  `supportsOwnerEdgeSerialization` + `serializeOwnerEdgeAndRead(table, id)`.
  InMemoryDriver advertises both row-lock capabilities and delegates to
  `byId` (its per-table `synchronized` blocks make the cooperative-vs-true-row-lock
  distinction moot for sequential tests). PostgresDriver's
  `readRowForUpdate` runs `SELECT ... FOR UPDATE` and
  `serializeOwnerEdgeAndRead` calls `pg_advisory_xact_lock` then reads —
  both methods throw on the root (auto-commit) driver since the lock would
  release immediately; the transactional sub-driver routes through
  internal `*With(conn, ...)` helpers.
- **`TransactionRequirement` guardrail.** Runtime enum
  (`Optional` / `RequiredForMultiWrite` / `RequiredForAllWrites`) plus
  `TransactionRequiredException`. EntClient exposes a
  `transactionRequirement` config knob; every generated save (Create
  builder, Update builder, repo `delete`) calls
  `client.checkTransactionRequirement(...)` at the start of `save()` /
  `delete(...)`, before hooks, privacy, validation, driver reads, or
  driver writes. Both transactional and scoped sub-clients inherit the
  configured requirement.
- **`UpdateConsistency.Pessimistic`.** Runtime enum +
  `UnsupportedDriverCapabilityException`. EntClient exposes
  `defaultUpdateConsistency`; the generated `update(id, consistency =
  client.defaultUpdateConsistency, block)` factory takes an optional
  per-save override. The Update builder's `save()` runs two preflights
  for `Pessimistic` (no transaction → `TransactionRequiredException`,
  driver without `supportsReadRowForUpdate` →
  `UnsupportedDriverCapabilityException`) and routes the internal
  current-row load through `driver.readRowForUpdate(...)`. The
  `ReadCurrent` path keeps the existing `byId(...)`-based RFC #1
  pipeline.
- **Hook / privacy / validation contexts** observe the
  transaction-scoped client when the save runs inside one — the
  existing wiring already passed `ctx.client` through
  `withTransaction`, and the Update / Create / Delete pipelines all
  read it from the surrounding scope.

Deferred to RFC #5 (link-table M2M helpers):

- The link-table M2M write helpers themselves (`tags.add(...)`,
  `tags.remove(...)`, `tags.set(...)`) — RFC #5 covers the API shape.
  Until they exist, no save uses
  `serializeOwnerEdgeAndRead(...)` or calls
  `checkTransactionRequirement(op, multiWrite = true)`, so
  `RequiredForMultiWrite` is effectively a no-op (it behaves like
  `Optional` for single-write saves).
- The Many-To-Many save pipeline described in this RFC's
  `Many-To-Many Pipeline` section. The pipeline's per-driver primitive
  choice and pessimistic-row-lock-shared-with-edge-serialization design
  is documented here, but no generator emits the M2M save shape
  yet — the multi-write helpers it relies on don't exist.

Split out from [Edge Mutation API](00-overview.md).

## Summary

Define transaction-neutral generated saves, optional runtime transaction
guardrails, the `UpdateConsistency.Pessimistic` update mode, and the required
transaction/locking semantics for generated link-table M2M helpers.

Generated saves should not open transactions implicitly. They run in the client
scope they are called from. Link-table M2M helpers are the exception at the API
level: they require callers to use a transaction-scoped client because they issue
multiple driver calls and need owner-edge serialization.

This RFC assumes [ID-Based Update Roots](01-id-based-update-roots.md) and extends
that baseline with `UpdateConsistency.Pessimistic`, a per-save update mode that
locks and reads the owner row before update hooks, privacy, validation, and
writes — see Update Consistency Modes below. Generated update saves are rooted by
id, not `update(entity)`. `UpdateConsistency` applies uniformly to scalar/to-one
and link-table M2M update saves; M2M saves additionally serialize the owner edge
for their junction multi-write, which is an orthogonal requirement that does not
change the selected consistency mode.

## Generated Save Transaction Model

Generated saves are transaction-neutral by default: they execute in the client
scope they are called from. A normal client does not open a transaction
implicitly. A transaction-scoped client created by
`client.withTransaction { tx -> ... }` causes generated driver calls, before and
after hook `ctx.client`, privacy checks, validation checks, and rule-context
`ctx.client` queries for that save to use the transaction-scoped driver/client.

Transactions are required only when the selected operation semantics require
them, such as any generated link-table M2M helper, or when the client configures
a stricter `TransactionRequirement`.

The generated save pipeline is not one generic shape. Create saves, `ReadCurrent`
scalar/to-one update saves, and owner-row-serialized update saves (`Pessimistic`
updates and link-table M2M updates) order their phases differently, because
update roots load — or lock, or cooperatively serialize and read — the current
owner row before before hooks. The shapes below are high-level; the authoritative
algorithm for update saves lives in
[ID-Based Update Roots](01-id-based-update-roots.md), and the detailed
link-table M2M ordering lives in the Many-To-Many Pipeline section below.

Create save pipeline:

1. transaction/capability preflight
2. before hooks
3. field defaults / final-value computation
4. field validation and required edge checks
5. candidate construction
6. privacy
7. validation
8. database writes
9. after hooks
10. returned LOAD privacy

`ReadCurrent` scalar/to-one update save pipeline (see
[ID-Based Update Roots](01-id-based-update-roots.md) for the authoritative
algorithm):

1. syntactically empty update classification — report `NoChanges` before any
   other observable work, including transaction requirement checks
2. transaction/capability preflight
3. internal current-row load — the loaded row is the update `before` state; a
   missing row reports `NotFound`
4. before hooks
5. hook-cleared empty classification — run UPDATE privacy, then report
   `NoChanges`
6. update defaults / effective-patch computation
7. field validation and required edge checks
8. candidate construction
9. privacy
10. validation
11. database writes
12. after hooks
13. returned LOAD privacy

Pessimistic update and link-table M2M update save pipeline (covers `Pessimistic`
scalar/to-one and M2M updates and `ReadCurrent` M2M updates; see the
Many-To-Many Pipeline section below for the detailed link-table M2M steps):

1. syntactically empty update classification where applicable — report
   `NoChanges` before any other observable work
2. transaction/capability preflight, including the transaction-scoped client and
   the locking-capability requirements
3. owner-row read under the save's serialization level — the primitive is
   chosen per driver, not per consistency mode: on a driver with
   `supportsReadRowForUpdate`, both `Pessimistic` (scalar/to-one or M2M) and
   `ReadCurrent` M2M saves use the true row lock; only on an advisory-only
   driver does a `ReadCurrent` M2M save use the cooperative owner-edge
   serialized read (`Pessimistic` is rejected at the capability gate). The
   row read here is the update `before` state, and a missing row reports
   `NotFound`
4. before hooks
5. hook-cleared empty classification (scalar/to-one, no pending M2M ops) — run
   UPDATE privacy, then report `NoChanges`
6. update defaults / effective-patch computation
7. field validation and required edge checks
8. candidate construction, including current junction read and `EdgeChanges`
   computation for link-table M2M saves
9. privacy
10. validation
11. database writes — owner-row update and any junction writes
12. after hooks
13. returned LOAD privacy

Privacy contexts keep the caller's privacy context. Validation contexts keep the
existing System-scoped LOAD-privacy bypass. When a save runs on a
transaction-scoped client, those contexts must also use clients backed by the
transaction-scoped driver so rule code observes the same transaction snapshot as
the write it is authorizing or validating. When a save runs on a normal client,
contexts use the normal client/driver.

Before and after hook `ctx.client` follows the same rule: it uses the save's
client scope. On a transaction-scoped client, every `beforeCreate`,
`beforeUpdate`, `beforeSave`, `afterCreate`, and `afterUpdate` hook observes the
transaction-scoped driver/client, so hook reads and writes share the same
transaction snapshot as the save. This is consistent with
[ID-Based Update Roots](01-id-based-update-roots.md), which already specifies
this for update hooks; this RFC extends the same rule to create and M2M save
hooks. On a normal client, hook `ctx.client` uses the normal client/driver.

## Update Consistency Modes

Generated update saves run under a selectable consistency mode. The baseline,
defined by [ID-Based Update Roots](01-id-based-update-roots.md), is
`ReadCurrent`: the current owner row is read unlocked before hooks, privacy,
validation, and the write, so a concurrent writer can change the row between
rule evaluation and the update write. This RFC adds `Pessimistic`, which locks
and reads the owner row in one operation so the checked owner state cannot
change between rule evaluation and the write.

```kotlin
enum class UpdateConsistency {
    ReadCurrent,
    Pessimistic,
}
```

### Selecting A Mode

`UpdateConsistency` is selected per save on the generated `update(...)` root:

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post.id, consistency = UpdateConsistency.Pessimistic) {
        viewCount = currentViewCount + 1
    }.save()
}
```

The parameter defaults to `UpdateConsistency.ReadCurrent`, so existing
`update(id) { ... }` calls keep RFC #1 semantics unchanged. A client may
configure a different default through `EntClientConfig`, set via the config
lambda the `EntClient(driver) { ... }` constructor already accepts:

```kotlin
val client = EntClient(driver) {
    defaultUpdateConsistency = UpdateConsistency.Pessimistic
}
```

`defaultUpdateConsistency` defaults to `UpdateConsistency.ReadCurrent`. The
per-save `consistency = ...` argument on `update(...)` always overrides the
client default.

Every generated client derived from a parent client must preserve the parent's
`defaultUpdateConsistency` — the transaction-scoped client from
`withTransaction`, plus the privacy-, validation-, and system-context scoped
clients behind hook and rule `ctx.client` — so a per-save `consistency = ...`
omitted from a hook- or rule-issued save resolves to the same default as a save
issued through the original client. This mirrors `TransactionRequirement`
inheritance (see Runtime Transaction Guardrails).

Create saves have no current-row load and do not take a consistency mode.

### Pessimistic Requires A Transaction-Scoped Client

A `Pessimistic` update locks the owner row with `readRowForUpdate` (see Driver
Strategy). That lock is only meaningful if the lock/read and the subsequent
owner-row update share one transaction; outside a transaction the lock is
released as soon as the read statement's implicit transaction commits, which
defeats the purpose. A `Pessimistic` update — including a scalar/to-one update
with no pending link-table M2M operations — therefore requires a
transaction-scoped client and a driver that reports `supportsReadRowForUpdate`,
which is **true** row-lock support. The weaker cooperative
`supportsOwnerEdgeSerialization` capability that link-table M2M saves may fall
back to does not satisfy `Pessimistic`, because a cooperative lock does not block
ordinary `UPDATE`/`DELETE` (see Driver Strategy). The preflight checks are:

- not transaction-scoped → `TransactionRequiredException`
- driver lacks true row-lock support (`supportsReadRowForUpdate`) →
  `UnsupportedDriverCapabilityException`

Both are named entkt exceptions, not raw `kotlin.UnsupportedOperationException`.
A missing transaction or an unsupported driver capability is a deterministic
configuration/programming error, not a branchable expected outcome: it is
neither a validation failure nor a privacy denial, and it is not modeled as an
`EntError` result variant. `TransactionRequiredException` and
`UnsupportedDriverCapabilityException` throw on every path, including
`saveOrError()` — `saveOrError()` does not catch them. `saveOrNull()` likewise
does not swallow them: because they throw rather than returning, `saveOrNull()`
never converts them to `null` (per
[ID-Based Update Roots](01-id-based-update-roots.md)).

Both checks run at the start of `save()`, after request-shape no-op
classification for update roots but before hooks, privacy, validation, driver
reads, or driver writes. A syntactically empty update still reports `NoChanges`
first and never triggers these checks, even when `Pessimistic` is selected,
consistent with the empty-update carve-out in Runtime Transaction Guardrails.

### Pessimistic Scalar/To-One Pipeline

A `Pessimistic` scalar/to-one update with no pending link-table M2M operations
follows the pessimistic update pipeline from Generated Save Transaction Model,
without the junction-specific steps:

1. syntactically empty update classification — report `NoChanges` before any
   other observable work
2. transaction/capability preflight — require a transaction-scoped client and
   `supportsReadRowForUpdate`
3. owner-row lock/read via `readRowForUpdate` — the locked row is the update
   `before` state; a missing row reports `NotFound`
4. before hooks
5. hook-cleared empty classification — run UPDATE privacy, then report
   `NoChanges`
6. update defaults / effective-patch computation
7. field validation and required edge checks
8. candidate construction from the locked `before` row and effective patch
9. privacy
10. validation
11. owner-row update
12. after hooks
13. returned LOAD privacy

The lock/read replaces the unlocked `ReadCurrent` current-row load from RFC #1;
every other phase keeps its RFC #1 ordering and semantics, including the
syntactically empty and hook-cleared empty update results. Because the owner row
is locked, the `before` state observed by hooks, privacy, and validation cannot
be changed or deleted by another transaction before the owner-row update.
`Pessimistic` is therefore the mode RFC #1 points to when it says rules that need
the checked current state to stay stable until the write should use a stronger
consistency mode.

### Link-Table M2M And UpdateConsistency

`UpdateConsistency` applies to link-table M2M update saves the same way it
applies to scalar/to-one update saves. It governs the *contract* for
owner-*row* stability, not the implementation primitive (the primitive is
chosen per driver — see "Driver Capabilities" below):

- `ReadCurrent` (the default) does not guarantee owner-row stability. Another
  transaction may change the owner row's scalar fields or delete it between the
  read and the write — the RFC #1 `ReadCurrent` races. (On a driver with
  `supportsReadRowForUpdate`, the implementation primitive at the read step is
  the true row lock, which incidentally blocks those concurrent writes; on
  advisory-only drivers it doesn't. Callers must not rely on the side-effect
  the contract doesn't promise.)
- `Pessimistic` does guarantee owner-row stability: the `before` state observed
  by hooks, privacy, and validation stays stable through the write. This is
  enforced by requiring `supportsReadRowForUpdate` at the capability gate and
  using the true row lock for the owner-row read; advisory-only drivers reject
  `Pessimistic` saves up front.

M2M saves carry one additional, orthogonal requirement: they must serialize the
owner *edge* so concurrent generated M2M helpers do not corrupt the junction set
(see Link-Table M2M Transaction Requirement). That owner-edge serialization is
always on for M2M saves and is independent of the selected `UpdateConsistency`.
The *primitive* used to provide both row stability (when requested) and edge
serialization is chosen per driver, not per mode: on a driver that supports
`readRowForUpdate`, every M2M-mutating save uses the true row lock — `Pessimistic`
*and* `ReadCurrent` alike — so concurrent saves of any mode mutually serialize
through the same lock; on a driver that supports only the cooperative
`serializeOwnerEdgeAndRead`, `ReadCurrent` M2M saves use that and `Pessimistic`
is rejected at the capability gate (see "Driver Capabilities" below for the
algorithm). What `UpdateConsistency` changes for an M2M save is only the
owner-row stability guarantee, exactly as it does for a scalar/to-one save:
selecting `ReadCurrent` does not weaken edge serialization, and selecting
`Pessimistic` does not add a second locking concept — it just upgrades the
owner-row stability requirement so drivers without true row-lock support are
rejected.

## Runtime Transaction Guardrails

The client may provide a runtime guardrail for teams that want stricter
transaction discipline without implicit transactions:

```kotlin
enum class TransactionRequirement {
    Optional,
    RequiredForMultiWrite,
    RequiredForAllWrites,
}
```

`TransactionRequirement` is client-level configuration on `EntClientConfig`, set
through the config lambda the `EntClient(driver) { ... }` constructor already
accepts:

```kotlin
val client = EntClient(driver) {
    transactionRequirement = TransactionRequirement.RequiredForMultiWrite
}
```

It defaults to `TransactionRequirement.Optional`. Every generated client derived
from a parent client must preserve the parent's `TransactionRequirement` — not
only the transaction-scoped client created by `client.withTransaction { tx -> ... }`,
but also the privacy-, validation-, and system-context scoped clients that
generated code creates for rule evaluation (the clients behind hook and rule
`ctx.client`). These scoped clients already copy hooks, privacy, and validation
from the parent field by field; they must copy `TransactionRequirement` the same
way. Otherwise a write issued through a hook's or rule's `ctx.client` would
silently run under the default `Optional` guardrail and bypass a configured
`RequiredForMultiWrite` or `RequiredForAllWrites`. The guardrail must apply
uniformly to every save, including saves issued from inside hooks and rules.

This setting is a runtime check, not an implicit transaction mechanism. All
transaction requirement checks must run after request-shape no-op
classification for update roots, but before hooks, field extraction/defaulting,
privacy, validation, driver reads, or driver writes. This includes configured
`TransactionRequirement` checks and semantic requirements selected by pending
operations, such as any generated link-table M2M mutation. If a save violates a
transaction requirement, generated code should throw
`TransactionRequiredException` immediately.

Per [ID-Based Update Roots](01-id-based-update-roots.md), a syntactically empty
update is classified as `NoChanges` by request shape before owner-row loads,
hooks, transaction requirement checks, privacy, validation, or driver
reads/writes. That classification runs first. A builder-requested empty update
reports `NoChanges` without requiring a transaction, even under
`TransactionRequirement.RequiredForAllWrites`, because it is not a write attempt
and must not perform observable work. This carve-out is specific to the
configured `TransactionRequirement` guardrails: a syntactically empty update has
no pending link-table M2M operations, so the M2M semantic transaction
requirement cannot apply, and it is never multi-write. Hook-cleared empty
updates are non-empty at `save()` start, so they are ordinary non-empty saves
for transaction-guardrail purposes and transaction requirement checks apply to
them normally.

Implementing the check requires the generated client or driver to expose whether
the current client is transaction-scoped. `RequiredForMultiWrite` applies to
saves that may perform more than one driver write, such as owner-row plus
link-table junction writes.

`RequiredForMultiWrite` should be classified from the requested operation shape
at the start of `save()`, before hooks, defaults, normalization, privacy,
validation, or driver reads/writes. Any pending generated link-table M2M mutation
counts as multi-write, even if normalization later produces no junction delta or
the save emits no owner-row update. Scalar/to-one saves count as multi-write only
when the generated save path is known to require more than one driver write.

## To-One FK Writes

To-one FK writes do not add a relationship write phase. They participate in the
selected create or update save pipeline as ordinary FK values (see the Create
save pipeline and the `ReadCurrent` and Pessimistic update save pipelines in
Generated Save Transaction Model). For update roots, the current owner row is
still loaded — or locked and read — before `beforeUpdate` hooks, according to
the selected `UpdateConsistency`. Syntactically empty update classification and
transaction/capability preflight precede the current-row load per RFC #1.

To-one-specific details:

- resolved FK assignment updates the scalar FK property; no separate
  relationship write phase is generated
- required edge checks run on the final FK value (create) or the effective patch
  values (update)
- writing a target id does not load the target row
- target LOAD privacy is not evaluated just because a target id appears in the
  mutation
- a `Pessimistic` to-one update additionally requires a transaction-scoped
  client and `supportsReadRowForUpdate`, and reads the owner row under a true
  row lock before `beforeUpdate` hooks (see Update Consistency Modes)

## Link-Table M2M Transaction Requirement

Link-table M2M helpers may issue multiple driver calls: owner-row updates,
junction reads, junction inserts, and junction deletes. They require a
transaction-scoped client so those calls share one transaction and one
owner-edge serialization boundary. Generated code must reject link-table M2M
saves outside an explicit transaction before hooks, privacy, validation, driver
reads, or driver writes.

All generated link-table M2M writes serialize the owner edge. Any save with
pending `add(...)`, `remove(...)`, or `set(...)` requires a transaction-scoped
client and a driver owner-edge serialization capability. A `ReadCurrent` M2M save
accepts either true row-lock support (`supportsReadRowForUpdate`) or the weaker
cooperative `supportsOwnerEdgeSerialization`; a `Pessimistic` M2M save requires
true row-lock support, like any other `Pessimistic` save (see Driver Strategy and
Update Consistency Modes). The transaction and capability requirements are
checked at the start of `save()`, before hooks, field extraction/defaulting,
privacy, validation, driver reads, or driver writes.

Before reading current junction rows or mutating the junction table, generated
code must serialize the owner-edge relationship. V1 may implement this by
locking the owner row, which can serialize all link-table edges on that owner.
That over-serialization is acceptable; the semantic requirement is at least
per-owner-edge serialization. If the save is not running inside an explicit
transaction, or if a driver cannot provide serialization for an edge, generated
code must fail deterministically before observable work.

Generated link-table M2M serialization is guaranteed only among generated
link-table M2M helpers, plus other code that uses the same locking discipline.
It cannot protect against direct junction repo writes, manual driver calls, or
database access outside entkt.

V1 should not expose weak or optimistic edge-set concurrency. Optimistic set
semantics need a concrete revision source, such as an owner version column, a
dedicated edge-revision row, or a driver capability like serializable
transactions with retry. Non-serialized semantics should remain available
through lower-level junction repo or manual driver writes, not through the
generated link-table edge helper API.

## Many-To-Many Pipeline

Many-to-many edge mutations require junction-table writes and therefore require
a transaction-scoped client:

1. start-of-save transaction and capability preflight runs first. If any
   generated link-table M2M operation is pending, generated code requires a
   transaction-scoped client — throwing `TransactionRequiredException` when the
   save is not transaction-scoped — and a driver locking capability:
   `supportsReadRowForUpdate` for a `Pessimistic` M2M save, or either
   `supportsReadRowForUpdate` or `supportsOwnerEdgeSerialization` for a
   `ReadCurrent` M2M save — throwing `UnsupportedDriverCapabilityException` when
   the driver lacks the required capability. Both checks run before hooks, before
   the mixed replacement/delta rejection in step 2, and before privacy,
   validation, driver reads, or driver writes
2. start-of-save preflight rejects mixed replacement/delta operations for the
   same edge, if that was not already rejected at the incompatible mutator call
   site, before hooks, privacy, validation, driver reads, or driver writes (per
   [Link-Table M2M Mutation Helpers](05-link-table-helpers.md))
3. the pending edge operation log is captured and the read-only `PendingEdgeOps`
   view that before hooks observe is computed from it. Before hooks cannot
   mutate pending edge operations, so this snapshot is fixed before hooks run
4. generated code serializes the owner edge and reads the current owner row
   before current junction rows are read or junction rows are mutated. The
   primitive choice is per driver, not per consistency mode: on a driver with
   `supportsReadRowForUpdate`, both `Pessimistic` and `ReadCurrent` M2M saves
   use the true row lock; only on a driver that supports only the cooperative
   `serializeOwnerEdgeAndRead` does a `ReadCurrent` M2M save use the cooperative
   primitive (`Pessimistic` is rejected at the capability gate in step 1). Either
   primitive serializes the owner edge against other generated M2M helpers; the
   true row lock additionally provides owner-row stability for `Pessimistic`
5. before hooks receive the normal scalar/FK mutation surface plus the read-only
   pending edge operation view from step 3 and the owner row read for this save
   as update `before` state
6. update defaults are applied to the post-hook scalar/FK patch to produce the
   effective scalar/FK patch, using the owner row read for this save as the
   fallback base for fields the builder and hooks did not change. Framework
   update defaults such as `updatedAt.updateDefaultNow()` are added here, even
   when no user scalar field changed
7. field validation and required edge checks run on the effective scalar/FK
   patch values
8. the owner after-state candidate is built from the owner row read for this
   save plus the effective scalar/FK patch
9. current junction rows are read and `EdgeChanges` is computed from the
   captured pending operation log and current link set
10. write privacy runs with the owner row read for this save, the requested and
    effective scalar/FK patches, the candidate, and computed edge changes
11. configured write validation runs with the same information
12. the owner row is updated when the effective scalar/FK patch is non-empty,
    including when it is non-empty only because of framework update defaults; an
    edge-only update whose effective scalar/FK patch is empty issues no owner-row
    update
13. junction rows are inserted/deleted/replaced
14. after hooks and return LOAD privacy run

The `PendingEdgeOps` view captured in step 3 and the `EdgeChanges` object
computed in step 9 are typed shapes defined authoritatively by
[Link-Table M2M Mutation Helpers](05-link-table-helpers.md). This RFC requires
only that before hooks receive a read-only `PendingEdgeOps` view and that privacy
and validation receive the normalized `EdgeChanges` object; it does not redefine
their field shapes.

Under `UpdateConsistency.ReadCurrent` — the default — the owner row read in
step 4 does not provide owner-row stability: it serializes the owner edge against
other generated M2M helpers, but it does not block ordinary `UPDATE`/`DELETE` on
the owner row, so another transaction may change the owner row's scalar fields,
or delete the owner row, between step 4 and the step 11–13 writes. Hooks, privacy,
and validation therefore observe a `before` state with the same staleness window
as a `ReadCurrent` scalar/to-one update (see
[ID-Based Update Roots](01-id-based-update-roots.md)). Callers that need the
checked owner state to stay stable through the write select
`UpdateConsistency.Pessimistic`, which adds the owner-row stability requirement on
top of the same edge serialization. Note that the *primitive* used at step 4 is
chosen per driver (per "Driver Capabilities" below), not per mode: on a driver
with `supportsReadRowForUpdate`, a `ReadCurrent` M2M save also uses the true row
lock — but the consistency contract still says the owner row may have been
deleted before step 11–13, because callers shouldn't rely on a side-effect of
the implementation primitive that's only present on some drivers.
The owner-edge serialization that protects the junction set is unchanged by the
mode; only owner-row stability differs.

For update, the owner id is already known. For create, the owner id may only be
known after insert for auto-increment ids, so create-time many-to-many mutation
should be a later phase until owner id availability and junction write ordering
are specified for multi-write creates.

The transaction must include edge serialization, the current owner-row read, the
current-state junction read, final candidate construction, `EdgeChanges`
computation, privacy checks, validation checks, any owner update, and all
junction writes. After hooks and returned LOAD privacy also run before the
caller's explicit transaction exits. If privacy, validation, the owner update,
junction inserts/deletes/replacements, after hooks, or returned LOAD privacy
fail, the owner update and junction database writes are rolled back only when the
failure propagates as an uncaught exception out of the caller's `withTransaction`
block — the driver rolls back a transaction when its block throws, not when
generated code returns normally. On throwing paths (`save()` / `saveOrThrow()`)
the failure propagates and the block throws, so rollback happens. On the
`saveOrError()` path the failure is caught and returned as `EntError`; the owner
and junction writes have already been issued to the transaction-scoped driver, so
they roll back only if the caller propagates the `Err` in a way that aborts the
transaction — for example `withTransactionOrError { ... }.bind()`, which re-throws
to roll back (see [Result Variants RFC](../tooling/entkt-result-variants-rfc.md)).
A plain `saveOrError()` inside a `withTransaction` block that returns normally
lets the writes commit.

Database rollback does not undo external side effects performed by before or
after hooks. Before hooks for owner-row-serialized update saves run after the
owner row is read — under whichever serialization primitive the driver
supplies, per "Driver Capabilities" — but before write privacy and validation
authorize the mutation.
After hooks may run before the caller's explicit transaction exits. Hooks that
send messages, write caches, or call external services should be idempotent or
use an outbox/after-commit pattern if those side effects must only happen after
the transaction commits. A dedicated after-commit hook can be considered
separately.

For update saves with pending link-table M2M operations, the generated update
root is the owner id. The owner row read for this save is the update `before`
state for hooks, privacy, and validation, and the fallback base for scalar/FK
fields the caller did not change. Link-table M2M helpers must not define a
separate hook-before-current-row-load update model.

For edge-only updates whose effective scalar/FK patch is empty — no builder or
hook scalar/FK changes and no framework update defaults — generated code must not
issue a no-op owner update. Instead, it returns the owner row read inside the
transaction, with relationship edges unloaded. If the owner row cannot be read,
`save()` returns `null` and no junction rows are read or mutated.

## Driver Strategy

To-one edge mutations under `UpdateConsistency.ReadCurrent` require no new driver
methods. `Pessimistic` to-one updates use the same `readRowForUpdate` capability
as link-table M2M helpers (see below).

Generated saves do not call `driver.withTransaction` implicitly. They use the
driver attached to the client they were created from: the normal driver for a
normal client, or the transaction-scoped driver for a client created by
`client.withTransaction { tx -> ... }`.

For link-table many-to-many mutations, prefer generated code using existing
driver methods against the junction table:

- `query(...)` to inspect existing junction rows for normalization
- `insert(...)` or `insertMany(...)` to add rows
- `deleteMany(...)` to remove rows

Beyond the row-lock capability below, only add dedicated driver APIs if
generated junction-table code becomes duplicated or inconsistent across drivers.

Using existing methods does not create an implicit transaction. Generated
link-table helper code calls those methods through the same current driver used
by the owner-row update. Because link-table helpers always require a
transaction-scoped client, owner updates and junction writes share one
transaction.

Generated link-table M2M helpers and `Pessimistic` updates require an explicit
transaction-scoped driver and a driver locking capability. V1 should add a
transaction state signal and two distinct locking operations — a true row lock
and a weaker cooperative owner-edge serialization:

```kotlin
interface Driver {
    val inTransaction: Boolean

    // True row lock. Blocks other transactions from updating or deleting
    // the row until this transaction ends. Required by
    // UpdateConsistency.Pessimistic.
    val supportsReadRowForUpdate: Boolean
    fun readRowForUpdate(table: String, id: Any): Row?

    // Cooperative owner-edge serialization. Serializes only against other
    // callers using the same discipline; does not block ordinary
    // UPDATE/DELETE. Sufficient for link-table M2M owner-edge
    // serialization, NOT for Pessimistic.
    val supportsOwnerEdgeSerialization: Boolean
    fun serializeOwnerEdgeAndRead(table: String, id: Any): Row?
}
```

`readRowForUpdate(table, id)` must provide genuine row-lock semantics: it locks
the row so that other transactions cannot update or delete it until this
transaction ends — equivalent to `SELECT ... FOR UPDATE` — and returns the
current row in one logical operation. `null` means the owner row does not exist.
A cooperative mechanism such as an advisory lock does **not** satisfy this
contract; that is what `serializeOwnerEdgeAndRead` is for.

`serializeOwnerEdgeAndRead(table, id)` is the weaker, cooperative counterpart
used only for link-table M2M owner-edge serialization. It must serialize the
owner-edge relationship against other callers that use the same discipline and
return the current owner row, but it is not required to block ordinary
`UPDATE`/`DELETE` from code outside that discipline. A driver may implement it
with `SELECT ... FOR UPDATE`, or with a weaker strategy such as an advisory lock
keyed by table and id plus an owner existence check and row read. `null` means
the owner row does not exist.

**Duration.** The serialization must hold from the call through the rest of the
save's transaction — at minimum across the current junction read, privacy and
validation checks, and the junction writes; in practice until the enclosing
transaction commits or rolls back. An implementation that releases the
serialization token at the end of the call (i.e., serializing only the row read
itself) does not satisfy the contract: a concurrent caller could observe the
same junction snapshot between this save's check and write phases and produce
the merged-relationship bug `set(...)` semantics is meant to rule out. Postgres
`pg_advisory_xact_lock(...)` satisfies this naturally — the lock is bound to the
transaction. Implementations that use non-transactional primitives must hold the
token explicitly until transaction end, and a transaction-scoped client is
required precisely so the driver has a transaction boundary to bind to.

The same "held until transaction end" rule applies to `readRowForUpdate` by the
"until this transaction ends" wording above; the explicit note here is for
`serializeOwnerEdgeAndRead` because cooperative locking primitives more often
ship with per-call (release-on-return) variants that would silently break the
contract.

Both methods are internal driver capabilities, intentionally table/id based to
match the existing raw `Driver` contract. Application code should not call them
as the edge mutation API; the typed application-facing API remains on generated
builders, such as `tags.add(...)`, `tags.remove(...)`, and `tags.set(...)`.

A `Pessimistic` update needs a true row lock; a link-table M2M save needs
owner-edge serialization, which a true row lock also satisfies:

```kotlin
val needsTrueRowLock = consistency == UpdateConsistency.Pessimistic
val needsOwnerEdgeSerialization = hasLinkTableM2MMutation
val requiresOwnerRowLock = needsTrueRowLock || needsOwnerEdgeSerialization
```

Generated code checks transaction state and locking capability at the start of
`save()`, before hooks or driver reads/writes:

```kotlin
if (requiresOwnerRowLock && !driver.inTransaction) {
    throw TransactionRequiredException("owner-row lock requires a transaction")
}
if (needsTrueRowLock && !driver.supportsReadRowForUpdate) {
    throw UnsupportedDriverCapabilityException(
        "Pessimistic update requires true row-lock support",
    )
}
if (needsOwnerEdgeSerialization &&
    !driver.supportsReadRowForUpdate &&
    !driver.supportsOwnerEdgeSerialization
) {
    throw UnsupportedDriverCapabilityException(
        "link-table M2M requires owner-edge serialization",
    )
}
```

After that guard has passed, generated code reads the current owner row before
before hooks, and — for link-table M2M saves — before reading current junction
rows or mutating the junction table. The primitive choice is **per driver**, not
per consistency mode: when the driver supports `readRowForUpdate`, all
owner-row reads use it (whether the save is `Pessimistic` or a `ReadCurrent`
M2M); the cooperative `serializeOwnerEdgeAndRead` is used only on drivers that
lack true row-lock support. This guarantees that two concurrent M2M-mutating
saves on the same owner take the *same* lock regardless of their consistency
modes — without it, a `Pessimistic` save's row lock and a concurrent
`ReadCurrent` save's advisory lock would not block one another, and the
merged-relationship bug that owner-edge serialization exists to prevent could
reintroduce itself across mixed-mode callers.

```kotlin
if (requiresOwnerRowLock) {
    ownerRow = (
        if (driver.supportsReadRowForUpdate) {
            // Pessimistic OR ReadCurrent M2M: the true row lock serves
            // both — and using one primitive per driver guarantees mutual
            // serialization between concurrent saves of any mode.
            driver.readRowForUpdate(Post.TABLE, post.id)
        } else {
            // Driver supports only cooperative owner-edge serialization
            // — that's ReadCurrent M2M (Pessimistic was already rejected
            // at the capability guard above).
            driver.serializeOwnerEdgeAndRead(Post.TABLE, post.id)
        }
    ) ?: return null
}

val before = Post.fromRow(ownerRow)
val candidate = buildCandidate(fallbackBase = before, dirtyScalarAndFkValues)
// link-table M2M only:
val currentLinks = driver.query(PostTag.TABLE, ownerIdFilter)
val edgeChanges = normalize(currentLinks, pendingEdgeOps)
// privacy(before, candidate[, edgeChanges])
// validation(before, candidate[, edgeChanges])
// optional owner update, junction writes
```

If the owner row cannot be read because it no longer exists, generated update
saves should return `null` (the `NotFound` result) without reading or mutating
junction rows, matching the existing `driver.update(...) ?: return null`
behavior. For edge-only updates with no scalar/FK changes, this owner-row read is
the owner existence check and generated code should not issue a no-op owner
update; it should return the owner row read inside the transaction, with edges
unloaded.

For link-table M2M updates and `Pessimistic` scalar/to-one updates, the owner row
read for this save is also the update `before` state and the fallback base for
scalar/FK fields the caller did not change.

**Post-read owner deletion under `ReadCurrent`.** A `ReadCurrent` M2M save
serializes the owner *edge* but does not lock the owner *row*, so another
transaction can delete the owner between the step 4 read and the step 11–13
junction writes. The visible behavior splits by save shape:

- **Mixed updates (scalar/FK + edge)**: the owner `UPDATE` in step 12 affects
  0 rows, generated code returns the `NotFound` result (mirroring the existing
  `driver.update(...) ?: return null` mapping), and step 13 junction writes are
  skipped.
- **Edge-only updates (no scalar/FK changes)**: there is no owner `UPDATE` to
  detect the missing row. Junction `INSERT`(s) in step 13 reference the now-gone
  owner id and the database surfaces a foreign-key violation; junction `DELETE`(s)
  for a non-existent owner are no-ops and don't surface anything. **V1 does not
  catch this FK violation**: the driver's raw exception propagates through both
  throwing and `saveOrError()` paths (e.g. a `org.postgresql.util.PSQLException`
  for the Postgres driver). `EntError` does not have a `ConstraintViolation`
  variant in V1 and generated `saveOrError()` only catches `EntException` /
  `PrivacyDeniedException` / `ValidationException`; constraint errors fall
  outside those. Callers who need a clean `NotFound` for the
  owner-deleted-post-read case on edge-only saves select
  `UpdateConsistency.Pessimistic`, which holds the owner row across the
  junction writes and rejects the concurrent delete.

Two follow-up improvements are deferred and intentionally out of V1 scope:

1. **A structured `EntError.ConstraintViolation` result variant** plus an
   `EntConstraintViolationException` wrapper, so generated `saveOrError()`
   maps the FK violation to `Err(EntError.ConstraintViolation)` instead of
   propagating the raw driver exception. This needs a driver-side capability
   to identify constraint failures and a runtime mapping; both are
   non-trivial and outside this RFC's scope.
2. **Remapping this specific FK violation to `NotFound`** (so an edge-only
   `ReadCurrent` M2M save matches the mixed-update shape's behavior). On
   top of (1), this requires the driver to introspect *which* constraint
   fired so the runtime can distinguish "owner gone" from a genuine
   target-side FK error.

Until both land, V1's contract is "the driver exception propagates"; pick
`UpdateConsistency.Pessimistic` if you need NotFound or other structured
results on the owner-deleted-post-read edge case.

The advisory-lock strategy is acceptable only for `serializeOwnerEdgeAndRead`
(link-table M2M owner-edge serialization), never for `readRowForUpdate`: a
cooperative advisory lock does not block ordinary `UPDATE`/`DELETE` and so cannot
provide the `Pessimistic` guarantee that the checked owner state will not change
before the write. A driver that can only serialize cooperatively reports
`supportsReadRowForUpdate = false` and `supportsOwnerEdgeSerialization = true`; it
can back link-table M2M saves but not `Pessimistic` updates.

Serializable transactions with retry may be modeled by a later, more abstract
driver capability. A driver with neither locking capability must reject generated
link-table M2M mutations; a driver without true row-lock support must reject
`Pessimistic` updates. Both rejections happen at the start of `save()`, before
hooks, privacy, validation, driver reads, or driver writes.

Through-entity edges do not use this direct junction-write path. They continue
to use the junction repo's normal create/update/delete pipeline.

## Rollout Plan

1. Preserve transaction-neutral generated save semantics. Normal clients should
   not open transactions implicitly; transaction-scoped clients should make all
   generated driver calls and rule-context client queries use the transaction
   driver.
2. Add runtime transaction requirement guardrails, including
   `RequiredForMultiWrite` and `RequiredForAllWrites`. These checks must run
   after request-shape no-op classification for update roots, but before hooks
   or any driver reads/writes.
3. Add driver transaction-state, true row-lock (`supportsReadRowForUpdate`), and
   cooperative owner-edge serialization (`supportsOwnerEdgeSerialization`)
   capabilities.
4. Require generated link-table M2M helpers to run on transaction-scoped clients
   and to serialize the owner-edge relationship before current junction rows are
   read.
5. Add the `UpdateConsistency.Pessimistic` update mode: the per-save
   `update(...)` selector defaulting to `ReadCurrent`, the transaction-scoped
   client and `supportsReadRowForUpdate` requirements, and the owner-row
   lock/read for scalar/to-one pessimistic updates.

## Test Requirements

Before implementation, add tests for:

- generated saves do not open transactions implicitly on normal clients
- saves called through a transaction-scoped client create privacy and validation
  contexts backed by the transaction-scoped driver, while preserving the caller
  privacy context for privacy and System-scoped LOAD-privacy bypass for
  validation
- saves called through a transaction-scoped client use the transaction-scoped
  driver/client for any follow-up read needed to hydrate the returned entity
- before and after hook `ctx.client` queries and writes use the
  transaction-scoped driver/client when the save runs on a transaction-scoped
  client, for every `beforeCreate`, `beforeUpdate`, `beforeSave`, `afterCreate`,
  and `afterUpdate` hook
- database writes a hook makes through `ctx.client` participate in the save's
  transaction and roll back when the failure propagates as an uncaught exception
  out of the caller's `withTransaction` block; on the `saveOrError()` path they
  commit unless the caller aborts the transaction (e.g. `withTransactionOrError`)
- `TransactionRequirement` is configured on `EntClientConfig` and defaults to
  `Optional`; every generated client derived from a parent — the
  transaction-scoped client from `withTransaction`, plus the privacy-,
  validation-, and system-context scoped clients behind hook and rule
  `ctx.client` — preserves the parent's `TransactionRequirement`
- a write issued through a hook or rule `ctx.client` is subject to the same
  `TransactionRequirement` guardrail as a write through the original client
- `TransactionRequirement.RequiredForMultiWrite` rejects qualifying multi-write
  saves outside a transaction at the start of `save()`, before hooks, privacy,
  validation, driver reads, or driver writes
- `TransactionRequirement.RequiredForMultiWrite` classifies any pending
  generated link-table M2M mutation as multi-write before hooks or normalization,
  even if it later produces no junction delta or owner-row update
- `TransactionRequirement.RequiredForAllWrites` rejects non-empty
  create/update/delete saves outside a transaction at the start of `save()`,
  before hooks, privacy, validation, driver reads, or driver writes
- `TransactionRequirement.RequiredForAllWrites` does not reject a syntactically
  empty update: request-shape no-op classification reports `NoChanges` first,
  without requiring a transaction
- link-table M2M `add`, `remove`, and `set` serialize per owner-edge
  relationship before reading current junction rows or mutating junction rows
- link-table M2M helpers throw `TransactionRequiredException` outside a
  transaction before hooks, privacy, validation, driver reads, or driver writes
- drivers that cannot support owner-edge serialization reject generated
  link-table M2M helpers at the start of `save()`, before hooks, privacy,
  validation, driver reads, or driver writes
- link-table M2M update returns `null` without reading or mutating junction rows
  when the owner row cannot be read because it no longer exists
- link-table M2M updates use the owner row read for this save as the hook,
  privacy/validation `before` state and fallback base for non-dirty scalar/FK
  fields
- link-table M2M before hooks run after the owner-row read and observe the owner
  row read for this save through the ID-based update hook context
- edge-only link-table M2M updates run before hooks, owner write privacy checks,
  validation, after hooks, and return LOAD privacy once
- link-table M2M saves roll back owner updates and junction database writes when
  after hooks or returned LOAD privacy throw and the exception propagates
  uncaught out of the caller's `withTransaction` block; on the `saveOrError()`
  path the writes commit unless the caller aborts via `withTransactionOrError`
- edge-only link-table M2M updates persist scalar/FK changes made by hooks but do
  not issue an empty owner-row update when no scalar/FK values changed
- edge-only link-table M2M updates apply owner `updateDefault` values, so an
  `updatedAt.updateDefaultNow()` field causes an owner-row update even when no
  user scalar field changed
- edge-only link-table M2M updates with no scalar/FK changes return the current
  owner row read inside the transaction after the owner-row read / owner-edge
  serialization, with edges unloaded
- `UpdateConsistency.ReadCurrent` is the default update mode; existing
  `update(id) { ... }` calls keep RFC #1 unlocked-read semantics
- `defaultUpdateConsistency` is configured on `EntClientConfig` and defaults to
  `UpdateConsistency.ReadCurrent`; every generated client derived from a parent
  — the transaction-scoped client from `withTransaction`, plus the privacy-,
  validation-, and system-context scoped clients behind hook and rule
  `ctx.client` — preserves the parent's `defaultUpdateConsistency`, and the
  per-save `consistency = ...` argument always overrides it
- `UpdateConsistency.Pessimistic` selected per save locks and reads the owner row
  with `readRowForUpdate` before before hooks, privacy, validation, and the
  owner-row update
- `Pessimistic` scalar/to-one updates with no pending link-table M2M operations
  require a transaction-scoped client and throw `TransactionRequiredException`
  outside a transaction, before hooks, privacy, validation, driver reads, or
  driver writes
- `Pessimistic` updates throw `UnsupportedDriverCapabilityException` when the
  driver does not report `supportsReadRowForUpdate`; it is not an `EntError`
  result variant — it throws on every path including `saveOrError()`, and
  `saveOrNull()` does not convert it to `null`
- `Pessimistic` updates (scalar/to-one or M2M) require true row-lock support; a
  driver that reports only `supportsOwnerEdgeSerialization` is rejected, while a
  `ReadCurrent` link-table M2M save accepts either `supportsReadRowForUpdate` or
  `supportsOwnerEdgeSerialization`
- the owner-row primitive is chosen per driver, not per consistency mode:
  on a driver with `supportsReadRowForUpdate`, both `Pessimistic` and
  `ReadCurrent` link-table M2M saves use `readRowForUpdate`; only on a
  driver without true row-lock support does a `ReadCurrent` link-table
  M2M save fall back to `serializeOwnerEdgeAndRead`. Test coverage
  must include the mixed-mode case: a `Pessimistic` save and a
  concurrent `ReadCurrent` link-table M2M save on the same owner
  serialize through the same primitive (no merged-relationship bug)
- a syntactically empty `Pessimistic` update reports `NoChanges` before the
  transaction-scoped client and row-lock capability checks
- `Pessimistic` updates return the `NotFound` result when `readRowForUpdate`
  finds no owner row, before hooks, privacy, validation, or the owner-row update
- `Pessimistic` updates use the locked owner row as the `before` state and the
  fallback base for non-dirty scalar/FK fields, keeping every other RFC #1 update
  phase ordering
- `UpdateConsistency` applies to link-table M2M update saves the same as to
  scalar/to-one update saves: it governs the *contract* for owner-row stability
  (`ReadCurrent` permits owner-row mutation/deletion between the read and the
  writes; `Pessimistic` does not). The implementation primitive at step 4 is
  chosen per driver, not per mode (see "Driver Capabilities"); the contract
  is the same regardless of which primitive a given driver uses
- a `ReadCurrent` link-table M2M update does not guarantee owner-row stability:
  the contract permits concurrent writers to change owner scalar fields or
  delete the owner row before the write — the RFC #1 `ReadCurrent` races. (On
  a driver that supports `readRowForUpdate`, the implementation's true row
  lock incidentally blocks those writes, but callers must not rely on that
  side-effect; advisory-only drivers don't.)
- post-read owner deletion under `ReadCurrent` M2M (reachable on
  advisory-only drivers, where step 4 uses `serializeOwnerEdgeAndRead` and
  does not block ordinary `DELETE`): when the owner is deleted by another
  transaction between the owner-row read and the junction writes, a *mixed*
  update (scalar/FK + edge) returns `NotFound` (the step-12 owner `UPDATE`
  affects 0 rows); an *edge-only* update surfaces the FK violation from the
  step-13 junction `INSERT`(s). V1 does not catch that FK violation — the
  driver's raw exception propagates through both throwing and `saveOrError()`
  paths (`EntError.ConstraintViolation` and a structured `saveOrError()`
  mapping for it are deferred follow-up work; see the "Post-read owner
  deletion under `ReadCurrent`" section above for details). Callers who
  need a clean `NotFound` here select `Pessimistic`. (On drivers with
  `supportsReadRowForUpdate`, step 4 uses the true row lock for *both*
  modes, so the post-read DELETE is blocked until the save commits and the
  race does not fire — but the contract permits it, so callers must not rely
  on the implementation side-effect.) Test coverage exercises both shapes
  (mixed vs edge-only) on an advisory-only driver fixture with a concurrent
  owner-delete arriving between read and write
- a `Pessimistic` link-table M2M update reads the owner row under a true row lock
  and requires `supportsReadRowForUpdate`
- link-table M2M owner-edge serialization is always applied regardless of the
  selected `UpdateConsistency`; selecting `ReadCurrent` does not weaken edge
  serialization and selecting `Pessimistic` does not add a second locking concept
