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
    None,
    Pessimistic,
}
```

`UpdateConsistency.None` is the default. It loads the current row for hooks,
privacy, validation, and candidate construction, then writes the changed fields.
It does not serialize that read with the eventual write.

`UpdateConsistency.Pessimistic` requires a transaction-scoped client and a driver
row-lock capability. Generated code locks and reads the owner row before hooks,
privacy, validation, and driver writes. If the save is not transaction-scoped, or
if the driver cannot lock the owner row, generated code must fail before hooks,
privacy, validation, or driver reads/writes.

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

With `UpdateConsistency.None`, the current-row read is not locked, so another
writer can change the row between rule evaluation and the update write. Rules
that require the checked current state to remain stable until the write should
use `UpdateConsistency.Pessimistic`.

With `UpdateConsistency.Pessimistic`, the current owner row is locked before it
is exposed to hooks or rules, and the generated update write runs while the lock
is held by the caller's explicit transaction.

This RFC intentionally removes `update(entity)` instead of turning it into a
hidden current-state channel or ambiguous compatibility path.

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

Create candidates remain full write candidates. Update patches are the explicit
mutation input; update candidates are full after-state snapshots derived from the
loaded current row plus that patch.

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

With `UpdateConsistency.None`, a follow-up read is not serialized with other
writers unless the caller has already chosen a transaction-scoped client. With
`UpdateConsistency.Pessimistic`, generated code should read the returned owner
row while the owner lock is still held.

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
4. Add `UpdateConsistency.None` and `UpdateConsistency.Pessimistic`.
5. Update hooks, privacy, validation, and candidate docs so current owner state
   comes from generated loads, not caller-passed owner entities.

## Test Requirements

Before implementation, add tests for:

- `update(id)` is the primary generated update API
- generated repos do not expose an `update(entity)` overload
- update saves load the current owner row before update hooks, privacy,
  validation, and writes, and return the missing-row result before those steps
  when the row does not exist
- update privacy and validation receive the loaded `before` row, the explicit
  update patch, and a full after-state candidate
- nullable update patch fields distinguish untouched values from explicit
  `null` writes with `FieldPatch.Unset` and `FieldPatch.Set(null)`
- scalar-only updates do not write untouched FK or scalar values
- returned update entities reflect the persisted row, not synthesized fallback
  values
- `UpdateConsistency.None` does not lock the current-row read
- `UpdateConsistency.Pessimistic` requires a transaction-scoped client and row
  lock support, and fails before hooks or driver reads/writes when unavailable
