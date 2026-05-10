# RFC: ID-Based Update Roots

## Status

Possible future feature. This is not implemented.

Split out from [Edge Mutation API](edge-mutation-api.md).

## Summary

Generated update APIs should identify the owner row by id, not by a caller-passed
entity object:

```kotlin
client.posts.update(postId) {
    title = "New title"
    setAuthor(alice)
}.save()
```

Generated code should remove the old `update(entity)` overload. Owner entities
may be passed to relationship setters as ID carriers, but owner-row updates
should be rooted only by ID.

## Motivation

`update(entity)` conflates two different concepts:

- identifying the row to update
- providing current owner state

That is surprising when the entity object is stale. For example, a scalar-only
update built from an old `Post` should not let hooks or rules reason over
`post.authorId` as if it were the current database value. It also should not
write untouched stale fields back to the database.

Using id-based update roots makes the contract explicit: the update root selects
which row to mutate. Current database state comes from a generated load during
save, not from a caller-passed entity object.

## Proposed API

The primary generated update shape should be ID-based:

```kotlin
client.posts.update(post.id) {
    title = "New title"
}.save()
```

Updates may request stronger consistency for the generated current-row read:

```kotlin
client.posts.update(post.id, consistency = UpdateConsistency.Pessimistic) {
    title = "New title"
}.save()
```

V1 should expose only two consistency modes:

```kotlin
enum class UpdateConsistency {
    ReadCurrent,
    Pessimistic,
}
```

`UpdateConsistency.ReadCurrent` is the default. It loads the current row for
hooks, privacy, validation, and candidate construction, then writes the changed
fields. It does not serialize that read with the eventual write.

`UpdateConsistency.Pessimistic` requires a transaction-scoped client and a driver
row-lock capability. Generated code uses the transaction/locking RFC's
`readRowForUpdate(table, id)` capability to lock and return the current owner row
before hooks, privacy, validation, and driver writes. If the save is not
transaction-scoped, or if the driver cannot lock and read the owner row,
generated code must fail before hooks, privacy, validation, or driver reads/writes.
If `readRowForUpdate` returns `null`, generated code returns the missing-row
result before hooks or rules run.

Entity arguments elsewhere also remain ID-only. For example, `setAuthor(alice)`
uses `alice.id`; it does not treat `alice` as loaded target state or evaluate
target LOAD privacy.

## Removed API

V1 should stop generating `update(entity)`:

```kotlin
client.posts.update(post) {
    title = "New title"
}.save()
```

Callers should pass the owner id instead:

```kotlin
client.posts.update(post.id) {
    title = "New title"
}.save()
```

There should be no deprecated compatibility overload and no ID-only sugar for
owner update roots. This is a breaking change, but it keeps the generated API
from implying that a stale entity object is current database state.

## Hooks, Privacy, And Validation

Generated update saves should load the current owner row before update hooks,
privacy, validation, and database writes. That loaded row is the update `before`
state. If the row no longer exists, the save returns the normal missing-row
result before hooks, privacy, validation, or writes run.

With `UpdateConsistency.ReadCurrent`, the current-row read is not locked, so
another writer can change the row between rule evaluation and the update write.
Rules that require the checked current state to remain stable until the write
should use `UpdateConsistency.Pessimistic`.

With `UpdateConsistency.Pessimistic`, the current owner row is locked before it
is exposed to hooks or rules, and the generated update write runs while the lock
is held by the caller's explicit transaction.

This RFC intentionally removes `update(entity)` instead of turning it into a
hidden current-state channel or ambiguous compatibility path.

## Update Hook Context

`beforeUpdate` hooks should receive a context object that separates current state
from writable mutation state:

```kotlin
data class PostUpdateHookContext(
    val client: EntClient,
    val before: Post,
    val patch: PostUpdatePatch,
    val mutation: PostUpdateMutation,
)
```

`before` is the owner row loaded by generated code. It is never a caller-passed
entity object. With `UpdateConsistency.ReadCurrent`, it is an unlocked snapshot
read before the hook. With `UpdateConsistency.Pessimistic`, it is the locked
owner row.

`patch` is a read-only view of pending changes accumulated before the hook.
`mutation` is the restricted writable update mutation view. Writing to
`mutation` updates the pending patch. Multiple hooks run sequentially, so later
hooks observe earlier hook writes through their patch and mutation views.

The hook `client` uses the same scope as the save, including transaction-scoped
behavior when the update is called through a transaction-scoped client.

## Patch And Candidate Shape

Under ID-based update roots, generated builders and hooks produce an explicit
update patch. The patch contains only fields and FKs changed by the builder or
hooks. Generated code applies that patch to the loaded `before` row to build a
full after-state candidate for privacy and validation.

Generated update patches should use an explicit tri-state wrapper so nullable
values are not ambiguous:

```kotlin
sealed interface FieldPatch<out T> {
    data object Unset : FieldPatch<Nothing>
    data class Set<T>(val value: T) : FieldPatch<T>
}
```

For example:

```kotlin
data class PostUpdatePatch(
    val title: FieldPatch<String> = FieldPatch.Unset,
    val body: FieldPatch<String?> = FieldPatch.Unset,
    val authorId: FieldPatch<UUID> = FieldPatch.Unset,
)
```

`FieldPatch.Unset` means the field or FK was untouched and should not be
written. `FieldPatch.Set(value)` means the mutation explicitly writes that value.
For nullable fields and nullable FKs, `FieldPatch.Set(null)` means the caller or
a hook explicitly clears the value.

Update privacy and validation contexts should expose both the loaded current row
and the generated patch. They should also expose a full after-state write
candidate built by applying the patch to the current row:

```kotlin
data class PostUpdatePrivacyContext(
    val privacy: PrivacyContext,
    val client: EntClient,
    val before: Post,
    val patch: PostUpdatePatch,
    val candidate: PostWriteCandidate,
)
```

`candidate` represents the writable owner-row values after the mutation. The
database update should still write only patch/dirty fields plus generated update
defaults, not every value copied from `before`.

Generated update defaults apply only to real updates. After before hooks run, if
the patch contains no changed fields or FKs, generated code should treat the save
as an empty update. Empty updates skip update defaults, field-shape checks, write
privacy, validation, the driver update, and `afterUpdate` hooks. They still run
returned LOAD privacy before returning the loaded current entity. This makes an
empty update a true no-op without turning the update API into a LOAD privacy
bypass.

For non-empty updates, generated update defaults, such as `updateDefaultNow()`,
are framework-added patch values. They are applied after before hooks and before
field-shape checks, required edge checks, and candidate construction. They are
included in the database write set and the after-state candidate even if the
builder did not assign that field. If the builder or hooks already set the
update-default field, that explicit value wins and the generated update default
is not applied.

Create candidates remain full write candidates. Update patches are the explicit
mutation input; update candidates are full after-state snapshots derived from the
loaded current row plus that patch.

## Rule Derivation

Because update privacy and validation contexts expose a full after-state
candidate, candidate-only create rules may still be derived for updates.
Generated derivation should adapt the update context into the existing create
context using the update context's full after-state candidate.

Derived create rules do not receive patch information. Rules that need to know
which fields changed, compare `before` to `candidate`, or inspect update intent
should be written as explicit update rules that use `before` and `patch`.

## Future Optimistic Locking

V1 should not model optimistic locking without an explicit version-field schema
contract. A future RFC can add a version marker, for example:

```kotlin
val version = int("version").version()
```

and then support an optimistic mode that updates with a version predicate and
increments the version in the same write. Without a declared version field,
optimistic locking would have to compare arbitrary columns, changed fields,
timestamps, or database-specific row versions, which is less portable and easier
to misunderstand.

## Returned Entity State

The returned entity should still reflect the persisted row after the update. If
the driver update path does not return a full row, generated code may perform a
follow-up read. It must not synthesize returned untouched values from update
input.

With `UpdateConsistency.ReadCurrent`, a follow-up read is not serialized with
other writers unless the caller has already chosen a transaction-scoped client.
With `UpdateConsistency.Pessimistic`, generated code should read the returned
owner row while the owner lock is still held.

If the update runs on a transaction-scoped client, any follow-up read used to
hydrate the returned entity must use the same transaction-scoped driver/client,
regardless of `UpdateConsistency`.

After hooks run after the driver update successfully returns or hydrates the
persisted owner row, and before returned LOAD privacy. This preserves the
existing generated write pipeline order.

## Result Semantics

`saveOrNull()` should follow Kotlin's `*OrNull` convention narrowly: it returns
`null` for expected absence, not for arbitrary failures. For `update(id)`, that
expected absence is a missing owner row. Generated code must detect the missing
row before hooks, privacy, validation, or driver writes run.

For missing owner rows:

- `saveOrThrow()` throws `NotFoundException` or EntKt's standard missing-row
  exception
- `saveOrNull()` returns `null`
- `saveOrError()` returns `EntError.NotFound`

`saveOrNull()` should not swallow privacy denial, validation failure, constraint
violations, transaction requirement failures, unsupported driver capabilities, or
driver/database failures. Those errors should throw on throwing paths or be
reported as structured errors by `saveOrError()`.

An empty or no-op update is not a missing row. If the owner row exists and the
mutation is otherwise valid, `saveOrNull()` should return the current/persisted
entity rather than `null`.

## Relationship To Other RFCs

[To-One Assignment And Nullability](edge-mutation-to-one-assignment-nullability.md)
defines to-one setter and FK semantics. This RFC defines the owner-row update
root those setters run inside.

[Transaction And Locking Semantics](edge-mutation-transaction-locking-semantics.md)
defines transaction-scoped client behavior, row-lock capabilities, and runtime
transaction guardrails used by `UpdateConsistency.Pessimistic`.

## Rollout Plan

1. Add ID-based update examples to new docs and RFCs.
2. Generate `update(id)` as the primary update root.
3. Remove the generated `update(entity)` overload.
4. Add `UpdateConsistency.ReadCurrent` and `UpdateConsistency.Pessimistic`.
5. Update hooks, privacy, validation, and candidate docs so current owner state
   comes from generated loads, not caller-passed owner entities.

## Test Requirements

Before implementation, add tests for:

- `update(id)` is the primary generated update API
- generated repos do not expose an `update(entity)` overload
- update saves load the current owner row before update hooks, privacy,
  validation, and writes, and return the missing-row result before those steps
  when the row does not exist
- missing owner rows map to `saveOrThrow()` throwing the standard missing-row
  exception, `saveOrNull()` returning `null`, and `saveOrError()` returning
  `EntError.NotFound`
- `saveOrNull()` returns `null` for missing owner rows, but not for privacy,
  validation, constraint, transaction, capability, or driver failures
- empty/no-op updates on existing rows return the current/persisted entity rather
  than `null`
- empty/no-op updates do not apply update defaults, such as `updatedAt`, and do
  not issue driver updates
- empty/no-op updates do not run write privacy, validation, or `afterUpdate`
  hooks, but do run returned LOAD privacy before returning the loaded entity
- non-empty updates apply update defaults to the patch unless the caller or hooks
  already set those fields
- non-empty update defaults are included in the database write set and
  after-state candidate when the builder changes another field
- explicit builder or hook assignment to an update-default field suppresses the
  generated update default
- update privacy and validation receive the loaded `before` row, the explicit
  update patch, and a full after-state candidate
- update derivation from create rules uses the full after-state candidate, while
  rules that need patch or `before` state are explicit update rules
- `beforeUpdate` hooks receive a hook context with the loaded `before` row, a
  read-only patch view, and a restricted writable mutation view
- nullable update patch fields distinguish untouched values from explicit
  `null` writes with `FieldPatch.Unset` and `FieldPatch.Set(null)`
- scalar-only updates do not write untouched FK or scalar values
- returned update entities reflect the persisted row, not synthesized fallback
  values
- returned entity follow-up reads use the same transaction-scoped driver/client
  when the update is called through a transaction-scoped client
- `afterUpdate` hooks run after the returned owner row is hydrated and before
  returned LOAD privacy
- `UpdateConsistency.ReadCurrent` does not lock the current-row read
- `UpdateConsistency.Pessimistic` requires a transaction-scoped client and row
  lock support, and fails before hooks or driver reads/writes when unavailable
