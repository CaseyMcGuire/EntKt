# RFC: Transaction And Locking Semantics For Edge Mutations

## Status

Possible future feature. This is not implemented.

Split out from [Edge Mutation API](edge-mutation-api.md).

## Summary

Define transaction-neutral generated saves, optional runtime transaction
guardrails, and the required transaction/locking semantics for generated
link-table M2M helpers.

Generated saves should not open transactions implicitly. They run in the client
scope they are called from. Link-table M2M helpers are the exception at the API
level: they require callers to use a transaction-scoped client because they issue
multiple driver calls and need owner-edge serialization.

This RFC assumes [ID-Based Update Roots](edge-mutation-id-based-update-roots.md)
and extends that baseline with `UpdateConsistency.Pessimistic`. Generated update
saves are rooted by id, not `update(entity)`. For pessimistic updates, the owner
row is locked and read before update hooks, privacy, validation, and writes.
Link-table M2M helpers reuse that owner-row lock/read instead of defining a
separate update root pipeline.

## Generated Save Transaction Model

Generated saves are transaction-neutral by default: they execute in the client
scope they are called from. A normal client does not open a transaction
implicitly. A transaction-scoped client created by
`client.withTransaction { tx -> ... }` causes generated driver calls, privacy
checks, validation checks, and rule-context `ctx.client` queries for that save
to use the transaction-scoped driver/client.

Transactions are required only when the selected operation semantics require
them, such as any generated link-table M2M helper, or when the client configures
a stricter `TransactionRequirement`.

The generic generated save pipeline is:

1. transaction/capability preflight
2. before hooks
3. field extraction/defaults or final-value computation
4. field validation and required edge checks
5. candidate construction
6. relationship reads and edge normalization when needed
7. privacy
8. validation
9. database writes
10. after hooks
11. returned LOAD privacy

Privacy contexts keep the caller's privacy context. Validation contexts keep the
existing System-scoped LOAD-privacy bypass. When a save runs on a
transaction-scoped client, those contexts must also use clients backed by the
transaction-scoped driver so rule code observes the same transaction snapshot as
the write it is authorizing or validating. When a save runs on a normal client,
contexts use the normal client/driver.

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

This setting is a runtime check, not an implicit transaction mechanism. All
transaction requirement checks must run at the start of `save()`, before hooks,
field extraction/defaulting, privacy, validation, driver reads, or driver
writes. This includes configured `TransactionRequirement` checks and semantic
requirements selected by pending operations, such as any generated link-table
M2M mutation. If a save violates a transaction requirement, generated code
should throw `TransactionRequiredException` immediately.

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

## To-One Preflight

To-one edge mutations do not need a relationship write phase, but they still use
the same start-of-save preflight discipline:

1. start-of-save transaction requirement checks run. For scalar/to-one saves,
   this primarily enforces configured guardrails such as
   `TransactionRequirement.RequiredForAllWrites`, and must throw before hooks or
   other observable work when the requirement is not met
2. before hooks run
3. entity or id assignment has already updated the FK property
4. final scalar/FK values are computed and field validation plus required edge
   checks run
5. the write candidate includes the final FK value
6. privacy and validation run in the caller's client scope
7. the owner row is inserted or updated
8. after hooks and return LOAD privacy run

## Link-Table M2M Transaction Requirement

Link-table M2M helpers may issue multiple driver calls: owner-row updates,
junction reads, junction inserts, and junction deletes. They require a
transaction-scoped client so those calls share one transaction and one
owner-edge serialization boundary. Generated code must reject link-table M2M
saves outside an explicit transaction before hooks, privacy, validation, driver
reads, or driver writes.

All generated link-table M2M writes use one serialization discipline. Any save
with pending `add(...)`, `addId(...)`, `remove(...)`, `removeId(...)`, `set(...)`,
or `setIds(...)` requires a transaction-scoped client and a driver row-lock
capability. The transaction and row-lock capability requirements are checked at
the start of `save()`, before hooks, field extraction/defaulting, privacy,
validation, driver reads, or driver writes.

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

1. start-of-save transaction requirement checks run. If any generated link-table
   M2M operation is pending, generated code requires a transaction-scoped client
   and throws `TransactionRequiredException` before hooks or driver reads/writes
   when the save is not transaction-scoped
2. generated code serializes the owner-edge relationship and reads the current
   owner row before current junction rows are read or junction rows are mutated
3. before hooks receive the normal scalar/FK mutation surface plus a read-only
   pending edge operation view and the locked current owner row as update
   `before` state
4. the pending edge operation log is captured
5. final scalar/FK values are computed using the locked current owner row as the
   fallback base for fields not changed by the builder or hooks. Field validation
   and required edge checks run on those final values
6. the scalar/FK candidate is built using the locked current owner row as the
   update `before` state
7. current junction rows are read and `EdgeChanges` is computed from the pending
   operation log and current link set
8. write privacy runs with the locked current owner row, candidate, and computed
   edge changes
9. configured write validation runs with the same information
10. the owner row is updated when scalar/FK values changed
11. junction rows are inserted/deleted/replaced
12. after hooks and return LOAD privacy run

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
throw, the owner update and junction database writes must roll back.

Database rollback does not undo external side effects performed by before or
after hooks. Before hooks for pessimistic update saves run after the owner row is
locked and read, but before write privacy and validation authorize the mutation.
After hooks may run before the caller's explicit transaction exits. Hooks that
send messages, write caches, or call external services should be idempotent or
use an outbox/after-commit pattern if those side effects must only happen after
the transaction commits. A dedicated after-commit hook can be considered
separately.

For update saves with pending link-table M2M operations, the generated update
root is the owner id. The locked current owner row is the update `before` state
for hooks, privacy, and validation, and the fallback base for scalar/FK fields
the caller did not change. Link-table M2M helpers must not define a separate
hook-before-current-row-load update model.

For edge-only updates with no scalar/FK changes, generated code must not issue a
no-op owner update. Instead, it returns the current owner row read inside the
transaction after the owner lock, with relationship edges unloaded. If the owner
row cannot be locked/read, `save()` returns `null` and no junction rows are read
or mutated.

## Driver Strategy

To-one edge mutations require no new driver methods.

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

Generated link-table M2M helpers require an explicit transaction-scoped driver
and a driver capability for owner-edge serialization. V1 should add a
transaction state signal and a low-level row-lock operation:

```kotlin
interface Driver {
    val inTransaction: Boolean
    val supportsReadRowForUpdate: Boolean
    fun readRowForUpdate(table: String, id: Any): Row?
}
```

`readRowForUpdate(table, id)` is an internal driver capability used by generated
code. It locks and returns the current row in one logical operation, equivalent
to `SELECT ... FOR UPDATE` on drivers that support that shape. `null` means the
owner row does not exist. The method is intentionally table/id based to match the
existing raw `Driver` contract. Application code should not call it as the edge
mutation API; the typed application-facing API remains on generated builders,
such as `tags.add(...)`, `tags.remove(...)`, and `tags.set(...)`.

Generated code checks transaction state and row-lock support at the start of
`save()`, before hooks or driver reads/writes:

```kotlin
if (hasLinkTableM2MMutation && !driver.inTransaction) {
    throw TransactionRequiredException("link-table edge mutation requires a transaction")
}
if (hasLinkTableM2MMutation && !driver.supportsReadRowForUpdate) {
    throw UnsupportedOperationException("link-table edge mutation requires row locking")
}
```

After that guard has passed, generated code locks and reads the current owner row
before reading current junction rows or mutating the junction table:

```kotlin
if (hasLinkTableM2MMutation) {
    lockedOwnerRow = driver.readRowForUpdate(Post.TABLE, post.id) ?: return null
}

val before = Post.fromRow(lockedOwnerRow)
val candidate = buildCandidate(fallbackBase = before, dirtyScalarAndFkValues)
val currentLinks = driver.query(PostTag.TABLE, ...)
val edgeChanges = normalize(currentLinks, pendingEdgeOps)
// privacy(before, candidate, edgeChanges)
// validation(before, candidate, edgeChanges)
// optional owner update, junction writes
```

If the owner row cannot be locked and read because it no longer exists, generated
update saves should return `null` without reading or mutating junction rows,
matching the existing `driver.update(...) ?: return null` behavior. For edge-only
updates with no scalar/FK changes, this locked row is the owner existence check
and generated code should not issue a no-op owner update; it should return the
owner row read inside the transaction after the lock, with edges unloaded.

For link-table M2M updates, the locked owner row is also the update `before`
state and the fallback base for scalar/FK fields the caller did not change.

Drivers may implement `readRowForUpdate` with an equivalent internal strategy,
such as an advisory lock keyed by table and id plus an owner existence check and
row read, if it provides the same owner-edge serialization semantics inside the
active transaction.

Serializable transactions with retry may be modeled by a later, more abstract
driver capability. Drivers that cannot provide V1 row-lock semantics must reject
generated link-table M2M mutations at the start of `save()`, before hooks,
privacy, validation, driver reads, or driver writes.

Through-entity edges do not use this direct junction-write path. They continue
to use the junction repo's normal create/update/delete pipeline.

## Rollout Plan

1. Preserve transaction-neutral generated save semantics. Normal clients should
   not open transactions implicitly; transaction-scoped clients should make all
   generated driver calls and rule-context client queries use the transaction
   driver.
2. Add runtime transaction requirement guardrails, including
   `RequiredForMultiWrite` and `RequiredForAllWrites`. These checks must run at
   the start of `save()`, before hooks or any driver reads/writes.
3. Add driver transaction-state and row-lock capabilities.
4. Require generated link-table M2M helpers to run on transaction-scoped clients
   and to serialize the owner-edge relationship before current junction rows are
   read.

## Test Requirements

Before implementation, add tests for:

- generated saves do not open transactions implicitly on normal clients
- saves called through a transaction-scoped client create privacy and validation
  contexts backed by the transaction-scoped driver, while preserving the caller
  privacy context for privacy and System-scoped LOAD-privacy bypass for
  validation
- saves called through a transaction-scoped client use the transaction-scoped
  driver/client for any follow-up read needed to hydrate the returned entity
- `TransactionRequirement.RequiredForMultiWrite` rejects qualifying multi-write
  saves outside a transaction at the start of `save()`, before hooks, privacy,
  validation, driver reads, or driver writes
- `TransactionRequirement.RequiredForMultiWrite` classifies any pending
  generated link-table M2M mutation as multi-write before hooks or normalization,
  even if it later produces no junction delta or owner-row update
- `TransactionRequirement.RequiredForAllWrites` rejects create/update/delete
  saves outside a transaction at the start of `save()`, before hooks, privacy,
  validation, driver reads, or driver writes
- link-table M2M `add`, `remove`, `set`, `addId`, `removeId`, and `setIds`
  serialize per owner-edge relationship before reading current junction rows or
  mutating junction rows
- link-table M2M helpers throw `TransactionRequiredException` outside a
  transaction before hooks, privacy, validation, driver reads, or driver writes
- drivers that cannot support owner-edge serialization reject generated
  link-table M2M helpers at the start of `save()`, before hooks, privacy,
  validation, driver reads, or driver writes
- link-table M2M update returns `null` without reading or mutating junction rows
  when the owner row cannot be locked because it no longer exists
- link-table M2M updates use the locked current owner row as the hook,
  privacy/validation `before` state and fallback base for non-dirty scalar/FK
  fields
- link-table M2M before hooks run after owner-row lock/read and observe the
  locked current owner row through the ID-based update hook context
- edge-only link-table M2M updates run before hooks, owner write privacy checks,
  validation, after hooks, and return LOAD privacy once
- link-table M2M saves roll back owner updates and junction database writes when
  after hooks or returned LOAD privacy throw before the explicit transaction
  exits
- edge-only link-table M2M updates persist scalar/FK changes made by hooks but do
  not issue an empty owner-row update when no scalar/FK values changed
- edge-only link-table M2M updates apply owner `updateDefault` values, so an
  `updatedAt.updateDefaultNow()` field causes an owner-row update even when no
  user scalar field changed
- edge-only link-table M2M updates with no scalar/FK changes return the current
  owner row read inside the transaction after the owner lock, with edges unloaded
