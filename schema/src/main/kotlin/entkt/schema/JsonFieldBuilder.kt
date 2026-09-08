package entkt.schema

/**
 * Builder for a typed JSON column. Declares no
 * modifiers of its own and does not override the final `build()`: `.nullable()`
 * / `.comment()` are inherited and valid; `.unique()` is inherited but rejected
 * by `build()`, as is any default. Length/scalar modifiers live only on the
 * scalar builders, so they are absent here.
 *
 * The full Kotlin type (including any type arguments, e.g.
 * `List<HighlightRect>`; `@Serializable` under the default kotlinx JSON
 * mapper) is attached at registration via `setJsonType(...)`
 * (see `EntSchema.registerJson`), mirroring how `enum` attaches its
 * `enumClass`. [T] preserves that type on the schema field handle without
 * requiring a serializer; codegen still uses `Field.jsonType` to resolve
 * the generated property type and serialization metadata.
 */
class JsonFieldBuilder<T : Any> internal constructor(name: String) :
    FieldBuilder<JsonFieldBuilder<T>, T>(name, FieldType.JSON)
