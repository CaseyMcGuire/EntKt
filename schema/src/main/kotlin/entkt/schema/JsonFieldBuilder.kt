package entkt.schema

/**
 * Builder for a typed JSON column. Declares no
 * modifiers of its own and does not override the final `build()`: `.nullable()`
 * / `.comment()` are inherited and valid; `.unique()` is inherited but rejected
 * by `build()`, as is any default. Length/scalar modifiers live only on the
 * scalar builders, so they are absent here.
 *
 * The full `@Serializable` Kotlin type (including any type arguments, e.g.
 * `List<HighlightRect>`) is attached at registration via `setJsonType(...)`
 * (see `EntSchema.registerJson`), mirroring how `enum` attaches its
 * `enumClass`. The value type parameter is `Any?` (the schema module never
 * references the user's class or its serializer); the generated entity
 * property is the supplied type, resolved by codegen from `Field.jsonType`.
 */
class JsonFieldBuilder internal constructor(name: String) :
    FieldBuilder<JsonFieldBuilder, Any?>(name, FieldType.JSON)
