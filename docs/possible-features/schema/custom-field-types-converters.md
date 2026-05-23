# RFC: Custom Field Types And Converters

## Status

Possible future feature. This is not implemented.

## Summary

Allow schema fields to use application-specific Kotlin types while storing
database-compatible values. Extend that same model to richer storage-backed
field types such as JSON, arrays, native database enums, and custom scalar
converters.

## Motivation

Applications often model domain values as Kotlin value classes or structured
types:

```kotlin
@JvmInline
value class Email(val value: String)
```

Without converters, schemas either expose primitive strings everywhere or
require manual mapping outside generated code.

Applications also often need richer database-backed values:

- JSON/JSONB settings or metadata
- string arrays and ID arrays
- database-native enums
- domain-specific value classes
- strongly typed IDs or slugs

If entkt supports these as first-class field declarations, schemas stay clear:
the Kotlin type is explicit, the storage type is explicit, and generated query
helpers can be added only where the driver can support them.

## Non-Goals

- Do not add a full serialization framework dependency.
- Do not make every field dynamically typed.
- Do not support arbitrary polymorphic conversion in the first version.
- Do not bypass field validation.
- Do not require every driver to support every storage type.
- Do not hide dialect-specific behavior behind portable-looking APIs.

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

For arrays:

```kotlin
array<String>("tags")
array<UUID>("mentioned_user_ids")
```

For native database enums:

```kotlin
databaseEnum<PostStatus>("status") {
    postgresName("post_status")
}
```

For custom scalar conversion with explicit storage metadata:

```kotlin
value<Slug>("slug") {
    storeAsString(
        encode = { it.value },
        decode = { Slug(it) },
    )
    maxLen(120)
    unique()
}
```

The declaration should always make the storage representation clear. A custom
field is not just a Kotlin type; it is a Kotlin type plus database storage
metadata.

## Query Helpers

Generated query helpers should reflect the field's declared capabilities:

```kotlin
Post.tags containsElement "kotlin"
Post.metadata containsJson SettingsFilter(featured = true)
Post.status eq PostStatus.Published
```

These helpers are driver-sensitive. For example, Postgres can support JSONB
containment and array containment natively, while another driver may not.
Unsupported helpers should fail through driver capability checks rather than
generating invalid SQL.

See [Driver Capability Matrix](../tooling/driver-capability-matrix.md).

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

For richer field types, the same boundary rule applies:

- entities expose the Kotlin/domain type
- mutations accept the Kotlin/domain type
- drivers receive the storage representation
- migrations use the declared storage type
- diagnostics show the field and storage type without exposing sensitive values

## Migration Behavior

Migration metadata must use storage types, not domain types:

```text
Slug -> text
Settings -> jsonb
Array<String> -> text[]
PostStatus -> post_status
```

If a storage type is dialect-specific, migration generation should require a
supporting driver capability and fail clearly when the active migration backend
cannot render it.

## Test Requirements

Before implementation, add tests for:

- create encodes custom values
- query hydrates custom values
- nullable custom fields work
- defaults work when supported
- validators can run before or after conversion as documented
- migration metadata uses the storage type
- JSON fields hydrate and persist through the configured serializer
- array fields preserve nullability and element type rules
- native enum migrations use the declared database enum name
- unsupported storage/query helpers fail with clear capability errors
