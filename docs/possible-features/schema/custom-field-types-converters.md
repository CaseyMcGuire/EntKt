# RFC: Custom Field Types And Converters

## Status

Possible future feature. This is not implemented.

## Summary

Allow schema fields to use application-specific Kotlin types while storing
database-compatible values.

## Motivation

Applications often model domain values as Kotlin value classes or structured
types:

```kotlin
@JvmInline
value class Email(val value: String)
```

Without converters, schemas either expose primitive strings everywhere or
require manual mapping outside generated code.

## Non-Goals

- Do not add a full serialization framework dependency.
- Do not make every field dynamically typed.
- Do not support arbitrary polymorphic conversion in the first version.
- Do not bypass field validation.

## Proposed Schema API

Example:

```kotlin
value<Email>("email") {
    storeAsString(
        encode = { it.value },
        decode = { Email(it) },
    )
}
```

For JSON-like values:

```kotlin
json<Settings>("settings", Settings.serializer())
```

JSON support may require a separate decision about dependencies.

## Generated Behavior

Generated entities expose the domain type:

```kotlin
data class User(
    val email: Email,
)
```

Generated row mapping converts at the boundary:

```kotlin
email = Email(row["email"] as String)
```

Generated write maps encode before hitting the driver:

```kotlin
"email" to email.value
```

## Test Requirements

Before implementation, add tests for:

- create encodes custom values
- query hydrates custom values
- nullable custom fields work
- defaults work when supported
- validators can run before or after conversion as documented
- migration metadata uses the storage type

