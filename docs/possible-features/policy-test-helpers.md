# RFC: Policy Test Helpers

## Status

Possible future feature. This is not implemented.

## Summary

Generate lightweight helpers for testing privacy and validation policies
without writing full application integration tests.

## Motivation

Privacy and validation rules should be easy to test directly. Full integration
tests are valuable but slow and verbose. Rule-level tests should make common
assertions concise:

```kotlin
assertAllowed {
    viewer(alice)
    load(post)
}

assertDenied("only the author can update") {
    viewer(bob)
    update(post) { title = "Nope" }
}
```

## Non-Goals

- Do not replace integration tests.
- Do not require a database for pure rule tests.
- Do not mock generated code through reflection.
- Do not invent a separate policy engine.

## Proposed API

Generated helpers could be emitted under test fixtures or an optional test
support module:

```kotlin
class PostPolicyTestHarness(
    private val client: EntClient,
) {
    fun assertLoadAllowed(viewer: Viewer, entity: Post)
    fun assertLoadDenied(viewer: Viewer, entity: Post, reason: String? = null)
    fun assertCreateDenied(viewer: Viewer, block: PostCreate.() -> Unit)
}
```

For validation:

```kotlin
postPolicyHarness.assertUpdateRejected(post, "author cannot change") {
    authorId = otherUser.id
}
```

## Driver Strategy

The first version can use `InMemoryDriver` for generated test harnesses. This
keeps tests fast and avoids Testcontainers for every policy assertion.

Rules that require database-specific behavior should still be tested in a
driver integration suite.

## Test Requirements

Before implementation, add tests for:

- generated harness can assert allowed and denied LOAD
- generated harness can assert create/update/delete privacy
- generated harness can assert validation rejection
- denial reason matching is optional
- helper APIs work with dependency-injected rule objects

