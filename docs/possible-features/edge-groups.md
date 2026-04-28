# RFC: Edge Groups

## Status

Possible future feature. This is not implemented.

## Summary

Add a schema concept for mutually exclusive relationship states.

Edge groups model cases where an entity can be connected through exactly one
edge in a named group at a time.

## Motivation

Some relationship sets are not independent edges. They represent state:

- an invitation is either sent, accepted, declined, or expired
- a workflow item is assigned to one queue state
- a friendship request moves from pending to accepted or blocked

With plain edges, the schema can expose multiple independent relationships:

```kotlin
val pending = manyToMany<User>("pending")
val accepted = manyToMany<User>("accepted")
val blocked = manyToMany<User>("blocked")
```

Nothing in that shape says the same target cannot be in more than one state.

## Non-Goals

- Do not replace normal edges.
- Do not infer edge groups by naming convention.
- Do not hide the join table for groups that need payload columns.
- Do not implement graph-wide workflow engines.

## Proposed Schema API

Possible direct edge-group API:

```kotlin
val friendships = edgeGroup<User>("friendships") {
    val pending = state("pending")
    val accepted = state("accepted")
    val blocked = state("blocked")
}
```

For SQL-first schemas, the cleaner model may be a join entity with an explicit
state field:

```kotlin
class Friendship : EntSchema<Int>("friendships") {
    override fun id() = EntId.int()

    val requesterId = long("requester_id")
    val recipientId = long("recipient_id")
    val requester = belongsTo<User>("requester").field(requesterId).required()
    val recipient = belongsTo<User>("recipient").field(recipientId).required()
    val state = enum("state", FriendshipState::class)

    val byPair = index("idx_friendships_pair", requesterId, recipientId).unique()
}
```

Then generated helpers could expose state-specific relationships:

```kotlin
client.users.acceptedFriends(user)
client.users.pendingFriendRequests(user)
```

## Recommended Direction

Prefer the join-entity model for V1.

Reasons:

- it keeps table shape explicit
- state transitions are normal updates
- indexes and constraints are visible
- payload columns are easy to add later
- privacy and validation can inspect the full relationship row

A direct `edgeGroup` shorthand can be considered later if the join-entity
pattern becomes too repetitive.

## Generated Helpers

Generated state helpers should be query conveniences, not hidden storage:

```kotlin
client.friendships.query()
    .where(Friendship.requester.eq(user.id))
    .where(Friendship.state.eq(FriendshipState.Accepted))
    .all()
```

could become:

```kotlin
client.users.acceptedFriends(user).all()
```

Mutation helpers should make state transitions explicit:

```kotlin
client.friendships.update(friendship) {
    state = FriendshipState.Accepted
}.save()
```

## Test Requirements

Before implementation, add tests for:

- generated helpers query the expected group state
- uniqueness constraints prevent duplicate relationship rows
- state transitions use normal update privacy and validation
- grouped helpers do not bypass join-entity hooks
- eager loading grouped relationships preserves strict LOAD privacy
