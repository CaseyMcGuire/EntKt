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

This RFC defines the independently implementable `ReadCurrent` update model. It
loads the current row for hooks, privacy, validation, and candidate construction,
then writes the changed fields. It does not serialize that read with the eventual
write.

Stronger update consistency modes, such as pessimistic owner-row locking, belong
in the transaction/locking RFC and build on top of this ID-based update root.

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

Under `ReadCurrent`, the current-row read is not locked, so another writer can
change the row between rule evaluation and the update write. Rules that require
the checked current state to remain stable until the write should use a stronger
consistency mode from the transaction/locking RFC.

The after-state candidate is built from the current row read before the write.
Because that read is not serialized with the write, untouched fields in the
persisted returned row may differ from the candidate if another writer changes
them before the update completes. Rules that require the checked candidate to
remain stable until write completion should use a stronger consistency mode from
the transaction/locking RFC.

The owner row can also disappear after the generated current-row read but before
the driver update. If `driver.update(...)` returns the missing-row result after
hooks, privacy, and validation have already run, generated code should return
the normal missing-row result and should not run `afterUpdate` or returned LOAD
privacy because there is no persisted updated entity to return.

This RFC intentionally removes `update(entity)` instead of turning it into a
hidden current-state channel or ambiguous compatibility path.

## Internal Current-Row Load Privacy

The generated current-row load used by `update(id)` is an internal write-path
load. It bypasses LOAD privacy and is not exposed directly to the caller. UPDATE
privacy is enforced after hooks and candidate construction. Returned LOAD privacy
still runs on the persisted returned entity before the entity is returned to
application code.

Before hooks are trusted application/framework code, not an authorization
boundary. They may observe the internal `before` row before UPDATE privacy runs
and should avoid external side effects that assume the mutation will be
authorized or committed.

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
entity object. Under `ReadCurrent`, it is an unlocked snapshot read before the
hook.

`patch` is a read-only snapshot of the requested patch accumulated before the
hook. It is not a live view. Writes through `mutation` do not change the `patch`
value visible inside the same hook. After each hook returns, generated code
merges mutation writes into the requested patch before constructing the next hook
context. Multiple hooks run sequentially, so later hooks observe earlier hook
writes through their own requested patch snapshots and mutation views.

The hook `client` uses the same scope as the save, including transaction-scoped
behavior when the update is called through a transaction-scoped client.

## Patch And Candidate Shape

Under ID-based update roots, generated builders and hooks produce an explicit
requested patch. The requested patch contains only fields and FKs changed by the
builder or hooks. For non-empty updates, generated code then applies framework
update defaults to produce the effective patch. Generated code applies the
effective patch to the loaded `before` row to build a full after-state candidate
for privacy and validation.

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

Update privacy and validation contexts should expose the loaded current row and
the effective patch, after framework update defaults have been applied. They
should also expose a full after-state write candidate built by applying the
effective patch to the current row:

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
database update should still write only effective patch/dirty fields, not every
value copied from `before`.

Generated update defaults apply only to real updates. After before hooks run, if
the requested patch contains no changed fields or FKs, generated code should
reject the save as an empty update. Empty updates skip update defaults,
field-shape checks, write privacy, validation, the driver update, `afterUpdate`
hooks, and returned LOAD privacy. They are not missing-row results and should not
return the loaded current entity.

For non-empty updates, generated update defaults, such as `updateDefaultNow()`,
are framework-added patch values. They are applied after before hooks and before
field-shape checks, required edge checks, and candidate construction. They are
included in the database write set and the after-state candidate even if the
builder did not assign that field. If the builder or hooks already set the
update-default field, that explicit value wins and the generated update default
is not applied.

Create candidates remain full write candidates. Requested patches are the
explicit mutation input. Effective patches are the database write set after
framework update defaults. Update candidates are full after-state snapshots
derived from the loaded current row plus the effective patch.

## Rule Derivation

Because update privacy and validation contexts expose a full after-state
candidate, candidate-only create rules may still be derived for updates.
Generated derivation should adapt the update context into the existing create
context using the update context's full after-state candidate.

The derived create rule receives a create-context adapter containing the update
after-state candidate and the same client and privacy context. It does not
receive `before` or `patch`. Rules that need to know which fields changed,
compare `before` to `candidate`, inspect update intent, or rely on create-only
assumptions should be written as explicit update rules.

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

Under `ReadCurrent`, a follow-up read is not serialized with other writers
unless the caller has already chosen a transaction-scoped client and a later RFC
adds stronger consistency semantics.

If the update runs on a transaction-scoped client, any follow-up read used to
hydrate the returned entity must use the same transaction-scoped driver/client,
so it observes the same transaction scope as the write.

After hooks run after the driver update successfully returns or hydrates the
persisted owner row, and before returned LOAD privacy. This preserves the
existing generated write pipeline order.

## Result Semantics

`saveOrNull()` should follow Kotlin's `*OrNull` convention narrowly: it returns
`null` for expected absence, not for arbitrary failures. For `update(id)`, that
expected absence is a missing owner row.

The missing-row result applies in two cases:

- the generated current-row load finds no owner row; hooks, privacy, validation,
  and writes do not run
- under `ReadCurrent`, the driver update returns no row because
  the owner was deleted after the current-row load; hooks, privacy, and
  validation may already have run, but `afterUpdate` and returned LOAD privacy do
  not run

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
final patch is still empty after before hooks, generated code should report a
no-changes result:

- `saveOrThrow()` throws `NoChangesException` or EntKt's standard no-changes
  exception
- `saveOrNull()` does not return `null`; it throws because the row exists and the
  failure is not expected absence
- `saveOrError()` returns `EntError.NoChanges`

## Relationship To Other RFCs

[To-One Assignment And Nullability](edge-mutation-to-one-assignment-nullability.md)
defines to-one setter and FK semantics. This RFC defines the owner-row update
root those setters run inside.

[Transaction And Locking Semantics](edge-mutation-transaction-locking-semantics.md)
may extend this baseline with stronger update consistency modes, such as
pessimistic owner-row locking, and defines transaction-scoped client behavior,
row-lock capabilities, and runtime transaction guardrails.

## Rollout Plan

1. Add ID-based update examples to new docs and RFCs.
2. Generate `update(id)` as the primary update root.
3. Remove the generated `update(entity)` overload.
4. Document the baseline `ReadCurrent` update model.
5. Update hooks, privacy, validation, and candidate docs so current owner state
   comes from generated loads, not caller-passed owner entities.

## Test Requirements

Before implementation, add tests for:

- `update(id)` is the primary generated update API
- generated repos do not expose an `update(entity)` overload
- update saves return the missing-row result before hooks, privacy, validation,
  or writes when the current-row load finds no owner row
- under `ReadCurrent`, a missing-row result from the driver
  update can occur after hooks, privacy, and validation have already run, but
  before `afterUpdate` or returned LOAD privacy
- missing owner rows map to `saveOrThrow()` throwing the standard missing-row
  exception, `saveOrNull()` returning `null`, and `saveOrError()` returning
  `EntError.NotFound`
- `saveOrNull()` returns `null` for missing owner rows, but not for privacy,
  validation, constraint, transaction, capability, or driver failures
- empty/no-op updates on existing rows report `NoChanges`, not `null` or the
  loaded current entity
- empty/no-op updates do not apply update defaults, such as `updatedAt`, and do
  not issue driver updates
- empty/no-op updates do not run write privacy, validation, `afterUpdate` hooks,
  or returned LOAD privacy
- empty/no-op updates map to `saveOrThrow()` throwing the standard no-changes
  exception, `saveOrNull()` throwing, and `saveOrError()` returning
  `EntError.NoChanges`
- non-empty updates apply update defaults to the requested patch to produce the
  effective patch unless the caller or hooks already set those fields
- non-empty update defaults are included in the database write set and
  after-state candidate when the builder changes another field
- explicit builder or hook assignment to an update-default field suppresses the
  generated update default
- update privacy and validation receive the loaded `before` row, the effective
  update patch, and a full after-state candidate
- hook contexts expose requested patch snapshots, while privacy, validation,
  driver writes, and candidate construction use the effective patch after update
  defaults
- the internal current-row load bypasses LOAD privacy, UPDATE privacy still runs
  before writes, and returned LOAD privacy runs before returning the entity
- `beforeUpdate` hooks may observe the internal `before` row before UPDATE
  privacy runs and are not an authorization boundary
- update derivation from create rules uses the full after-state candidate, while
  rules that need patch or `before` state are explicit update rules
- derived create rules receive a create-context adapter with the update
  after-state candidate and no `before` or `patch`
- `beforeUpdate` hooks receive a hook context with the loaded `before` row, a
  read-only patch view, and a restricted writable mutation view
- `beforeUpdate` hook `patch` is a snapshot at hook entry; same-hook mutation
  writes do not change `patch`, while later hooks see those writes
- nullable update patch fields distinguish untouched values from explicit
  `null` writes with `FieldPatch.Unset` and `FieldPatch.Set(null)`
- scalar-only updates do not write untouched FK or scalar values
- returned update entities reflect the persisted row, not synthesized fallback
  values
- returned entity follow-up reads use the same transaction-scoped driver/client
  when the update is called through a transaction-scoped client
- `afterUpdate` hooks run after the returned owner row is hydrated and before
  returned LOAD privacy
- `ReadCurrent` does not lock the current-row read
- under `ReadCurrent`, concurrent changes to untouched fields
  may appear in the returned row even though privacy and validation evaluated a
  candidate built from the earlier current-row read
- under `ReadCurrent`, if the owner row is deleted after the
  current-row read but before the driver update, the save returns the missing-row
  result and does not run `afterUpdate` or returned LOAD privacy
