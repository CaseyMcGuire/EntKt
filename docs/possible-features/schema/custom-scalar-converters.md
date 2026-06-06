# RFC: Custom Scalar Converters

## Status

Possible future feature. Not implemented. This RFC is intentionally separate from
[Native Database Column Types](../../implemented-features/schema/native-database-column-types.md): scalar converters
store ordinary database column types and should not require driver-specific bind or
decode behavior.

## Summary

Allow schemas to expose small application-domain types while storing primitive
database values. Example uses include `Email`, `Slug`, `TenantKey`, or value classes
that wrap a `String`, `Long`, or `UUID`.

The conversion is generated at the ent boundary:

- Writes encode the domain value to an existing scalar `FieldType`.
- Reads decode the scalar database value back to the domain type.
- Drivers continue binding and decoding ordinary primitives.

This keeps the API predictable: a converter is not a database-specific type, and a
database-native type is not a converter.

## Proposed API

```kotlin
@JvmInline
value class Email(val value: String)

class User : EntSchema("users") {
    val email = customScalar("email", Email::class)
        .storedAsText(
            encode = { it.value },
            decode = { Email(it) },
        )
        .unique()
}
```

Reusable mappings should also be possible:

```kotlin
object EmailMapping : ScalarMapping<Email, String> {
    override val storageType = FieldType.TEXT
    override fun encode(value: Email): String = value.value
    override fun decode(value: String): Email = Email(value)
}

val email = customScalar("email", Email::class).mappedBy(EmailMapping)
```

The exact names are open, but the shape should stay explicit: domain Kotlin type,
storage scalar type, encode function, decode function.

## Behavior

- Generated entity/create/update APIs expose the domain type (`Email`).
- `ColumnMetadata.type` remains the scalar storage type (`TEXT` in the example).
- A storage carrier records the conversion metadata for codegen, not for driver
  dispatch.
- `default(...)`, query predicates, indexes, and uniqueness use the encoded storage
  value.
- Decode failures should throw a field-named exception so corrupt rows are easy to
  identify.

## Non-Goals

- No driver plugin system.
- No generic "custom SQL type" support; use
  [Native Database Column Types](../../implemented-features/schema/native-database-column-types.md) for that.
- No implicit global converter lookup in the first cut.
- No serialization framework dependency.
- No polymorphic or dynamically typed values.

## Open Questions

- Should converters be inline lambdas only, reusable mapping objects only, or both?
- Should validation run on the domain value, the encoded value, or both?
- How should query helper generation expose scalar-specific operations like
  `startsWith` or `contains`?
- Should generated docs warn when a converter's `decode` can throw?

## Test Requirements

- Generated entity/create/update APIs use the domain type.
- Writes encode before passing values to the driver.
- Reads decode in `fromRow`.
- `unique()` and indexes render against the storage column.
- Decode failures include table and column context.
