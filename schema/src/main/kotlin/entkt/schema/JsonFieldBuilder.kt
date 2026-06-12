package entkt.schema

/**
 * Builder for a typed JSON column. Declares no
 * modifiers of its own and does not override the final `build()`: `.nullable()`
 * / `.comment()` are inherited and valid; `.unique()` is inherited but rejected
 * by `build()`, as is any default. Length/scalar modifiers live only on the
 * scalar builders, so they are absent here.
 *
 * The `@Serializable` Kotlin class is attached at registration via
 * `setJsonClass(...)` (see `EntSchema.registerJson`), mirroring how `enum`
 * attaches its `enumClass`. The value type parameter is `Any?` (the schema
 * module never references the user's class or its serializer); the generated
 * entity property is the supplied class, resolved by codegen from
 * `Field.jsonClass`.
 */
class JsonFieldBuilder internal constructor(name: String) :
    FieldBuilder<JsonFieldBuilder, Any?>(name, FieldType.JSON)
