# RFC: Structured Error Model

## Status

Possible future feature. This is not implemented.

## Summary

Introduce a small structured error model behind entkt exceptions and future
non-throwing APIs.

The first version can keep throwing exceptions publicly while carrying typed
error data internally.

## Motivation

As entkt grows, users will need to distinguish failures such as:

- privacy denied
- validation rejected
- not found
- unique constraint failed
- stale optimistic lock
- migration conflict

String-only exceptions make this hard to handle consistently in Spring,
GraphQL, command-line tools, tests, and future Kotlin rich-error APIs.

## Non-Goals

- Do not remove throwing APIs.
- Do not require Kotlin rich errors.
- Do not add a functional result library dependency.
- Do not force every driver exception into a perfect typed hierarchy in the
  first version.

## Proposed Runtime API

Add a sealed error hierarchy:

```kotlin
sealed interface EntError {
    data class PrivacyDenied(
        val entity: String,
        val operation: PrivacyOperation,
        val reason: String,
    ) : EntError

    data class ValidationFailed(
        val entity: String,
        val field: String?,
        val reason: String,
    ) : EntError

    data class NotFound(
        val entity: String,
        val id: Any,
    ) : EntError

    data class ConstraintFailed(
        val entity: String,
        val constraint: String?,
        val reason: String,
    ) : EntError
}
```

Exceptions can wrap an `EntError`:

```kotlin
open class EntException(
    val error: EntError,
) : RuntimeException(error.toString())
```

`PrivacyDeniedException` can remain as a compatibility type while exposing
`error = EntError.PrivacyDenied(...)`.

## Future Explicit APIs

Later, generated methods could expose non-throwing variants:

```kotlin
client.posts.byIdOrError(id)
client.posts.query { ... }.allOrError()
client.posts.create { ... }.saveOrError()
```

These should be added only after the error model is stable.

## Test Requirements

Before implementation, add tests for:

- privacy exceptions carry `EntError.PrivacyDenied`
- validation exceptions carry `EntError.ValidationFailed`
- driver constraint failures map to `ConstraintFailed` when possible
- existing exception classes remain catchable
- structured error fields are stable enough for web error mappers

