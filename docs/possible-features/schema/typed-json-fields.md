# RFC: Typed JSON Fields

## Status

Possible future feature. Not implemented. This RFC is intentionally separate from
[Custom Scalar Converters](custom-scalar-converters.md) and
[Native Database Column Types](../../implemented-features/schema/native-database-column-types.md): JSON values need a
structured object mapper and dialect-specific storage choices, but should have a
small, obvious schema API.

## Summary

Add typed JSON fields that expose an application data class at the generated API
boundary while storing JSON in the database.

Preferred schema shape:

```kotlin
data class PetMetadata(
    val nickname: String?,
    val tags: List<String>,
)

class Pet : EntSchema("pets") {
    val metadata = json("pet_metadata", PetMetadata::class)
        .nullable()
}
```

This matches existing entkt style (`enum(name, KClass)`) and avoids a surprising
per-field serializer argument. The JSON mapper should be configured at the client or
codegen boundary, not hidden in a DSL overload.

## Proposed API

```kotlin
val client = EntClient(
    driver = PostgresDriver(dataSource),
    jsonMapper = KotlinxJsonMapper(json),
)
```

Schema:

```kotlin
val metadata = json("pet_metadata", PetMetadata::class)
```

Generated API:

```kotlin
client.pets.create {
    metadata = PetMetadata(nickname = "Mochi", tags = listOf("senior"))
}.save()
```

## Storage

Postgres should use `jsonb` by default. Other drivers may use their native JSON type
or text storage, but compatibility must be explicit through driver capabilities and
migration rendering.

The returned Kotlin object shape is the supplied `KClass`. entkt should not infer a
dynamic JSON object shape from data, nor expose untyped JSON through this API. If a
raw JSON value is needed, it should be a separate, clearly named API.

## Behavior

- Generated entity/create/update APIs expose `PetMetadata`.
- Writes encode `PetMetadata` through the configured mapper.
- Reads decode through the configured mapper.
- Nullable JSON fields round-trip `null`.
- Decode failures should include table and column context.
- JSON path predicates and JSON indexes are out of scope for the first cut.

## Non-Goals

- No per-field serializer argument in the primary API.
- No raw dynamic JSON as the default.
- No portable-looking JSON query DSL until dialect behavior is designed.
- No implicit dependency on one serialization library.

## Open Questions

- Where should the JSON mapper be configured: generated client constructor,
  driver options, or both?
- Should Postgres `jsonb` be the only first implementation?
- Should default values be allowed, and if so should they be encoded at codegen time
  or migration render time?
- What should the raw JSON escape hatch be called?

## Test Requirements

- Generated entity/create/update APIs use the supplied Kotlin type.
- Writes encode through the configured mapper.
- Reads decode through the configured mapper.
- Null values round-trip.
- Missing mapper configuration fails with a clear schema/client error.
