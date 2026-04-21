# RFC: Edge Mutation API

## Status

Possible future feature. This is not implemented.

## Summary

Generate relationship mutation helpers so callers can connect, disconnect,
and replace edges without manually setting foreign key fields or editing join
tables.

## Motivation

entkt already generates typed edge metadata and eager-loading APIs. Mutation
APIs still tend to expose storage details:

```kotlin
client.posts.create {
    authorId = user.id
}.save()
```

An edge mutation API would let applications express graph changes directly:

```kotlin
client.posts.create {
    title = "Hello"
    author.connect(user)
}.save()
```

## Non-Goals

- Do not hide required edge validation.
- Do not bypass privacy or validation.
- Do not support arbitrary graph saves in the first version.
- Do not add cascading create for nested objects in the first version.

## Proposed API

For to-one edges:

```kotlin
client.posts.update(post) {
    author.connect(alice)
    author.disconnect()
}.save()
```

For to-many or many-to-many edges:

```kotlin
client.posts.update(post) {
    tags.add(kotlinTag)
    tags.remove(oldTag)
    tags.set(listOf(kotlinTag, ormTag))
}.save()
```

Generated edge mutators should be typed. A `Post.author` mutator should accept
`User`, not an arbitrary entity.

## Enforcement Semantics

Edge mutations must participate in the same write pipeline as scalar fields:

1. before hooks
2. candidate construction
3. validation
4. privacy
5. database writes
6. after hooks

Candidates should include resulting foreign key values and relationship
changes so validation and privacy rules can inspect them.

## Test Requirements

Before implementation, add tests for:

- required to-one `connect` sets the FK
- optional to-one `disconnect` clears the FK
- required to-one `disconnect` rejects
- M2M `add`, `remove`, and `set` update the junction table
- edge mutation changes are visible to validation and privacy rules
- hooks fire once for the owning entity mutation

