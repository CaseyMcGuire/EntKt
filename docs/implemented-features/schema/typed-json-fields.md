# RFC: Typed JSON Fields

## Status

**Implemented** (Postgres `jsonb`). The sections below are the original
implementation contract, kept as a design record. User-facing docs are
[Schema -> Typed JSON Fields](../../02-schema.md#typed-json-fields-postgres-jsonb).
This RFC is intentionally separate from
[Custom Scalar Converters](../../possible-features/schema/custom-scalar-converters.md)
and [Native Database Column Types](native-database-column-types.md): JSON values
need dialect-specific storage choices and a small, obvious schema API.

V1 uses `kotlinx.serialization` directly. entkt will not introduce a generic
JSON mapper abstraction until there is a concrete need for more than one mapper.

### As-built notes

The implementation followed this contract closely. Specifics worth recording:

- **Generic types are supported (2026-07-04), superseding the original
  "wrap `List<PetMetadata>` in a concrete class" restriction below.** The
  restriction was never enforced — `json<List<Rect>>` was silently accepted and
  codegen emitted a raw `List` property (the reified overload captured only
  `T::class`, which erases type arguments), so reads could never round-trip to
  typed elements. The DSL now captures the full `KType`
  (`Field.jsonType`, via `typeOf<T>()`): the generated property is
  `List<Rect>`, and the `SCHEMA` literal registers a serializer built
  recursively from the type
  (`ListSerializer(Rect.serializer())`, `MapSerializer(...)`, `.nullable` for
  nullable arguments, `Box.serializer(arg)` for generic `@Serializable`
  classes). The `KClass` overload rejects classes with type parameters and
  points at the reified form; star/variance projections and unresolved type
  parameters are rejected at registration. `JsonColumnMetadata` gained a
  `typeName` string so driver errors can name `List<Rect>` rather than the
  erased `kotlin.collections.List`. User-facing docs:
  [Schema -> Typed JSON Fields](../../02-schema.md#typed-json-fields-postgres-jsonb).

- The narrow column ref is a standalone `JsonColumn<E, T>` /
  `NullableJsonColumn<E, T>` (in `entkt.query`) — it does **not** extend `Column`,
  so it inherits none of the scalar helpers. `isNull()` / `isNotNull()` are
  members on the nullable variant.
- Driver support is gated by a `Driver.supportsTypedJson()` capability +
  `checkTypedJsonSupported(schema)`, called in `register()` (parallel to the
  native-storage check); `PostgresDriver` returns true.
- The configured `Json` is a `PostgresDriver` constructor argument (default
  `Json.Default`); serializers come from `JsonColumnMetadata`, not the driver.
- Migrations diff only the `jsonb` SQL type + nullability (the migration-path
  schema carries no serializer), so a Kotlin-class or serializer change produces
  no migration. `json`/`jsonb` canonicalize equal so a plain `json` column
  doesn't read as drift.

## Summary

Add typed JSON fields that expose an application data class at the generated API
boundary while storing JSON in the database.

Preferred schema shape:

```kotlin
@Serializable
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
per-field serializer argument. JSON field types must be `@Serializable`; generated
code references the generated serializer so missing serialization support fails
at compile time instead of as a late database read failure.

## Proposed API

Applications using typed JSON fields must apply the Kotlin serialization compiler
plugin and have `kotlinx-serialization-json` available.

Schema:

```kotlin
@Serializable
data class PetMetadata(
    val nickname: String?,
    val tags: List<String>,
)

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

The returned Kotlin object shape is the supplied `KClass` *(as built: the
supplied `KType`, including type arguments — see as-built notes)*. entkt should
not infer a dynamic JSON object shape from data, nor expose untyped JSON
through this API. If a raw JSON value is needed, it should be a separate,
clearly named API.

## Behavior

- Generated entity/create/update APIs expose `PetMetadata`.
- Writes encode `PetMetadata` through `kotlinx.serialization`.
- Reads decode through `kotlinx.serialization`.
- Nullable JSON fields round-trip `null`.
- Decode failures should include table and column context.
- Nullable JSON columns expose null checks only in V1. Equality, membership,
  ordering, containment, path predicates, and JSON indexes are out of scope for
  the first cut.
- Generated `fromRow(row)` remains pure: the driver returns decoded Kotlin
  values, and `fromRow` casts them like other driver-decoded types.
- The generated runtime `ColumnMetadata` for a JSON column carries enough
  serialization metadata for the driver to encode/decode the field.
- Low-level driver writes of typed JSON values require registered column metadata;
  the driver should not guess serializers from runtime values.

## Non-Goals

- No per-field serializer argument in the primary API.
- No raw dynamic JSON as the default.
- No portable-looking JSON query DSL until dialect behavior is designed.
- No Jackson/Moshi/Gson mapper abstraction in V1.
- No JSON defaults in V1.
- No JSON path predicates, containment predicates, or JSON indexes in V1.
- No first-class generic or polymorphic JSON field API in V1.
- No automatic migration from Kotlin JSON type or serializer shape changes.

## Implementation Decisions

- Postgres is the only first implementation.
- Postgres stores typed JSON fields as `jsonb`.
- Postgres driver JSON configuration is a constructor option, defaulting to
  `Json.Default`:

  ```kotlin
  PostgresDriver(
      dataSource,
      autoDdl = true,
      json = Json {
          ignoreUnknownKeys = true
      },
  )
  ```

  The driver uses this `Json` instance for all typed JSON encode/decode.
  Generated metadata supplies serializers, not JSON configuration, and the schema
  DSL does not configure serialization behavior.
- The schema module should avoid a serialization dependency if possible. Runtime
  metadata uses `kotlinx-serialization-core`; the Postgres module uses
  `kotlinx-serialization-json` and owns the configured `Json` instance.
- Schema DSL keeps the preferred KClass shape:

  ```kotlin
  json("pet_metadata", PetMetadata::class)
  ```

  A reified convenience overload may also be generated/provided:

  ```kotlin
  json<PetMetadata>("pet_metadata")
  ```

- Generated code should reference `PetMetadata.serializer()` (or equivalent
  generated serializer access) so non-serializable classes fail clearly during
  compilation.
- JSON field types must be concrete serializable classes in V1. Generic top-level
  shapes such as `List<PetMetadata>` should be wrapped in an application data
  class instead of being accepted directly by the schema API. *(Superseded
  2026-07-04: generic shapes are accepted directly — see as-built notes.)*
- Polymorphic JSON is not a first-class API in V1. If a concrete field type's
  generated serializer works with the driver's configured `Json`, entkt treats it
  like any other serializable type, but V1 does not add sealed-type helpers,
  discriminator configuration, or polymorphic query behavior.
- Driver binding encodes values to a Postgres `jsonb` parameter. Driver decoding
  reads the JSON text and returns the decoded Kotlin value. Generated entity and
  repo code pass the typed Kotlin value through; database representation details
  stay in the driver.
- Nullable JSON columns use the normal nullable API and expose only null checks
  at query time in V1. Whole-document equality, `eq`, `neq`, `in`, `notIn`,
  ordering, containment, and path predicates are deferred.
- Generated JSON column references should use a narrow column type that is
  addressable for SQL identity and null checks without inheriting scalar equality,
  membership, ordering, string, enum, or comparable helpers.
- JSON columns support `.nullable()` in V1. The schema should reject JSON
  defaults, unique constraints, primary keys, and generated JSON indexes with a
  clear validation error. Database-specific JSON indexes may be added through
  manual migrations.
- Schema registration should fail clearly when a typed JSON field is registered
  with a driver that does not support typed JSON storage.
- Runtime JSON metadata should be explicit:

  ```kotlin
  data class JsonColumnMetadata(
      val klass: KClass<*>,
      val serializer: KSerializer<*>,
  )
  ```

  Generated runtime schema metadata should attach this to JSON columns:

  ```kotlin
  ColumnMetadata(
      name = "pet_metadata",
      type = FieldType.JSON,
      nullable = true,
      json = JsonColumnMetadata(
          klass = PetMetadata::class,
          serializer = PetMetadata.serializer(),
      ),
  )
  ```

  `FieldType.JSON` requires JSON metadata at runtime registration, and non-JSON
  fields must not carry JSON metadata. The configured `Json` instance belongs to
  the driver, not to column metadata.
- Raw low-level driver APIs should reject typed JSON writes when schema metadata
  is unavailable or incomplete. The error should point callers toward generated
  repos or explicit schema registration instead of silently attempting runtime
  serializer discovery.
- Write-time type mismatch errors should include the table, column, expected JSON
  class, and actual value class.
- Decode errors should include the table, column, and expected JSON class while
  preserving the original serialization exception as the cause.
- SQL `NULL` should bypass JSON decode. Nullable JSON fields return `null`; non-null
  JSON fields should use the same non-null error behavior as other field types.
- Migrations should only diff database-level JSON facts: column existence, SQL type,
  and nullability. Changes to the Kotlin class, property names, `@SerialName`
  annotations, serializer configuration, or application-level JSON shape do not
  produce automatic migrations.
- Postgres migration rendering should emit `jsonb`. Introspection may canonicalize
  both `json` and `jsonb` as JSON-compatible storage, but generated schema remains
  the source of truth for the Kotlin class and serializer because the database
  cannot reconstruct them.

## Test Requirements

- Generated entity/create/update APIs use the supplied Kotlin type.
- Generated code requires a kotlinx serializer for the supplied type.
- Writes encode through kotlinx.serialization.
- Reads decode through kotlinx.serialization.
- Null values round-trip.
- Postgres DDL renders `jsonb`.
- JSON defaults, unique constraints, primary keys, and generated indexes are
  rejected.
- Decode failures include table and column context.
- JSON fields do not expose equality, membership, ordering, string, or comparable
  query helpers.
- Registering a typed JSON field with an unsupported driver fails with a clear
  schema registration error.
- Low-level typed JSON writes without registered serializer metadata fail with a
  clear configuration error.
- Kotlin JSON class or serializer-shape changes do not produce automatic
  migrations.
