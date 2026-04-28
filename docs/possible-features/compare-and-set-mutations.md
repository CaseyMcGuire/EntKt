# RFC: Compare-And-Set Mutations

## Status

Possible future feature. This is not implemented.

## Summary

Add first-class compare-and-set preconditions for generated update and delete
mutations.

This lets callers say "perform this write only if these fields still have these
expected values" without requiring a dedicated version column on every entity.

## Motivation

[Optimistic Locking](optimistic-locking.md) is a good fit when an entity wants a
single version field:

```sql
UPDATE posts
SET title = ?, version = version + 1
WHERE id = ? AND version = ?
```

That is not the only useful concurrency shape. Applications often need more
explicit write preconditions:

- publish a post only if `status == DRAFT`
- accept a friendship only if `status == PENDING`
- archive an object only if `deleted_at IS NULL`
- update a row only if `updated_at == previouslySeenValue`
- delete a row only if `owner_id == currentViewerId`

Compare-and-set lets callers express those preconditions directly.

## Non-Goals

- Do not replace database transactions.
- Do not add pessimistic locking.
- Do not support arbitrary cross-row or cross-table predicates in V1.
- Do not bypass privacy or validation.
- Do not require every entity to participate.

## Proposed Semantics

Generated update and delete builders can accept one or more expected-value
checks. The framework folds them into the write `WHERE` clause.

Example:

```sql
UPDATE posts
SET status = 'PUBLISHED', published_at = ?
WHERE id = ? AND status = 'DRAFT'
```

If no row matches, the mutation fails with a structured compare-and-set error.

## Possible API Shapes

### Explicit CAS Block

```kotlin
client.posts.update(post) {
    status = PostStatus.PUBLISHED
}.cas {
    expect(PostSchema.status, PostStatus.DRAFT)
}.save()
```

### Builder Method

```kotlin
client.posts.update(post) {
    status = PostStatus.PUBLISHED
}.expect(PostSchema.status, PostStatus.DRAFT)
 .save()
```

### Null Checks

```kotlin
client.posts.update(post) {
    deletedAt = Instant.now()
}.cas {
    expectNull(PostSchema.deletedAt)
}.save()
```

The API should stay narrow in V1:

- equality
- null / not-null checks

Range or arbitrary expression support can wait.

## Error Shape

The failure should be distinguishable from:

- privacy denial
- field/entity validation failure
- unique constraint failure
- optimistic-lock stale version error

Possible shape:

```kotlin
data class CompareAndSetFailed(
    val entity: String,
    val id: Any,
    val expectations: List<CasExpectation>,
)
```

Applications should be able to catch this specifically.

## Relationship To Optimistic Locking

[Optimistic Locking](optimistic-locking.md) remains useful as a common generated
default. Compare-and-set is broader:

- optimistic locking says "match one version field"
- compare-and-set says "match these explicit field expectations"

If both features land, optimistic locking could become sugar over
compare-and-set plus an auto-incremented version field.

## Relationship To Structured Mutation Pipeline

This feature fits best if compare-and-set is treated as part of the persistence
contract rather than as just another validation hook.

Recommended ordering:

1. normalize
2. validate
3. derive
4. persist with CAS preconditions in the `WHERE` clause
5. afterCommit

That keeps CAS as a storage-backed guarantee rather than a racy read-then-check
validation step.

## Example Use Cases

### State Transition Guard

```kotlin
client.friendships.update(friendship) {
    status = FriendshipStatus.ACCEPTED
}.cas {
    expect(FriendshipSchema.status, FriendshipStatus.PENDING)
}.save()
```

### Timestamp-Based Stale Write Detection

```kotlin
client.posts.update(post) {
    title = "New title"
}.cas {
    expect(PostSchema.updatedAt, post.updatedAt)
}.save()
```

### Soft Delete

```kotlin
client.posts.delete(post)
    .cas {
        expectNull(PostSchema.deletedAt)
    }
    .save()
```

## SQL / Driver Requirements

The compare-and-set behavior should be implemented in the database write, not by
running a separate preflight read.

That preserves correct concurrency semantics and avoids a read/write race
between the check and the update.

Drivers need enough metadata to render:

- `column = ?`
- `column IS NULL`
- possibly `column IS NOT NULL` if that is included in V1

## Test Requirements

Before implementation, add tests for:

- update succeeds when expectations match
- update fails when expectations do not match
- delete succeeds when expectations match
- delete fails when expectations do not match
- null expectations render correctly
- privacy and validation still run before the database write
- compare-and-set failures use a distinct structured error
- optimistic-lock-enabled entities can be expressed in terms of compare-and-set
  semantics without changing behavior
