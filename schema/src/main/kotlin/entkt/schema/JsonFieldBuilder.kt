package entkt.schema

/**
 * Builder for a typed JSON column. Inherits `.nullable()`, `.immutable()`,
 * `.sensitive()`, and `.comment()`. Uniqueness and default modifiers are not
 * exposed for JSON fields.
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
