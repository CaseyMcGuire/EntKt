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

If an `update(entity)` overload remains during migration, it should be deprecated
and treated strictly as ID-only sugar for `update(entity.id)`. The passed entity
must not provide current-state fallback values for hooks, privacy, validation, or
candidate construction.

## Motivation

`update(entity)` conflates two different concepts:

- identifying the row to update
- providing current owner state

That is surprising when the entity object is stale. For example, a scalar-only
update built from an old `Post` should not let hooks or rules reason over
`post.authorId` as if it were the current database value. It also should not
write untouched stale fields back to the database.

Using id-based update roots makes the contract explicit: the update root selects
which row to mutate. Current database state must be loaded intentionally.

## Proposed API

The primary generated update shape should be ID-based:

```kotlin
client.posts.update(post.id) {
    title = "New title"
}.save()
```

Entity arguments elsewhere also remain ID-only. For example, `setAuthor(alice)`
uses `alice.id`; it does not treat `alice` as loaded target state or evaluate
target LOAD privacy.

## Compatibility

V1 may either remove `update(entity)` outright or keep it temporarily as a
deprecated overload:

```kotlin
client.posts.update(post) {
    title = "New title"
}.save()
```

If kept, this overload must behave exactly like:

```kotlin
client.posts.update(post.id) {
    title = "New title"
}.save()
```

No other fields from `post` are used as current state, fallback state, dirty
state, or rule input.

## Hooks, Privacy, And Validation

Hooks, privacy, and validation for ID-based updates should reason over explicit
mutation input and generated write candidates, not stale owner entity snapshots.
If a rule needs current database state, it must query or reload that state
explicitly. A future structured current-state mutation path can provide a
first-class API for that use case.

This RFC intentionally avoids making `update(entity)` a hidden current-state
channel.

## Returned Entity State

The returned entity should still reflect the persisted row after the update. If
the driver update path does not return a full row, generated code may perform a
follow-up read. It must not synthesize returned untouched values from a stale
entity argument.

## Relationship To Other RFCs

[To-One Assignment And Nullability](edge-mutation-to-one-assignment-nullability.md)
defines to-one setter and FK semantics. This RFC defines the owner-row update
root those setters run inside.

[Transaction And Locking Semantics](edge-mutation-transaction-locking-semantics.md)
can later define stronger current-state or transactional rule-evaluation paths.

## Rollout Plan

1. Add ID-based update examples to new docs and RFCs.
2. Generate `update(id)` as the primary update root.
3. Remove `update(entity)`, or keep it temporarily as deprecated ID-only sugar.
4. Update hooks, privacy, validation, and candidate docs so caller-passed owner
   entities are not described as current-state inputs.

## Test Requirements

Before implementation, add tests for:

- `update(id)` is the primary generated update API
- `update(entity)`, if present during migration, uses only `entity.id`
- `update(entity)` does not expose other entity fields as current-state fallback
  values to hooks, privacy, validation, or candidates
- scalar-only updates do not write untouched stale FK or scalar values from a
  caller-passed entity
- returned update entities reflect the persisted row, not stale fields from a
  caller-passed entity
