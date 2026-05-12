# RFC: Generated Test Fixtures

## Status

Possible future feature. This is not implemented.

## Summary

Generate fixture helpers for creating valid test data with minimal boilerplate.

## Motivation

Integration tests repeatedly need to create users, posts, comments, and
relationships. Generated fixtures can make tests shorter and keep them aligned
with schema changes.

## Non-Goals

- Do not replace application-specific factories.
- Do not generate random data by default.
- Do not bypass privacy unless explicitly configured.
- Do not hide required field choices in production code.

## Proposed API

Example:

```kotlin
val fixtures = EntFixtures(client)

val alice = fixtures.user {
    name = "Alice"
}

val post = fixtures.post {
    author = alice
    published = false
}
```

Generated defaults can satisfy required fields:

```kotlin
fixtures.user()
```

For fields without obvious defaults, generated fixtures should require
callers to provide values or fail with a clear message.

## Privacy Behavior

Fixtures should use normal public APIs by default so privacy and validation
still run.

An explicit system helper can be available for seed data:

```kotlin
fixtures.asSystem {
    user()
    post()
}
```

## Test Requirements

Before implementation, add tests for:

- fixture creates valid entities with defaults
- required fields without defaults fail clearly
- edge fields can be assigned by entity object
- privacy runs by default
- system fixture mode uses `Viewer.System` explicitly
- generated fixtures update when schema fields change

