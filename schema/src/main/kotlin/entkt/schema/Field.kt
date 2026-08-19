package entkt.schema

data class Field(
    val name: String,
    val type: FieldType,
    val nullable: Boolean = false,
    val unique: Boolean = false,
    val immutable: Boolean = false,
    val sensitive: Boolean = false,
    val default: Any? = null,
    val updateDefault: UpdateDefault? = null,
    val enumClass: kotlin.reflect.KClass<out Enum<*>>? = null,
    val validators: List<Validator> = emptyList(),
    val comment: String? = null,
    /**
     * Kotlin `val` name of the delegated schema property that declared
     * this field — the name every generated field API uses. [name] stays
     * the storage column; the two are independent.
     *
     * Bound by the field builder's `provideDelegate` during schema
     * construction. Nullable only before finalization: `finalize()`
     * rejects any registered builder that never bound, so codegen never
     * sees a null here.
     *
     * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
     */
    val declarationName: String? = null,
    /**
     * Native/rich storage metadata when this field is more than a plain
     * [FieldType] → SQL-type mapping.
     * Null for ordinary scalar/enum fields. Set by builders via
     * `FieldBuilder.setNativeStorage(...)` and folded in by `build()`.
     */
    val storage: ColumnStorage? = null,
    /**
     * The full Kotlin type of a `FieldType.JSON` field, including any type
     * arguments (`PetMetadata`, `List<HighlightRect>`, `Map<String, Point>`).
     * Null for non-JSON fields. Set at registration via
     * `FieldBuilder.setJsonType(...)`; codegen derives both the generated
     * property type and the kotlinx serializer expression from it (the schema
     * module never touches the serializer, only the type). A `KClass` alone
     * cannot represent type arguments — capturing the `KType` is what lets
     * `json<List<HighlightRect>>(...)` round-trip with the element type intact.
     */
    val jsonType: kotlin.reflect.KType? = null,
) {
    /**
     * Raw classifier class of [jsonType] (`List::class` for
     * `List<HighlightRect>`), or null for non-JSON fields. Registration
     * guarantees every classifier in [jsonType] is a concrete class, so for
     * JSON fields this never returns null.
     */
    val jsonClass: kotlin.reflect.KClass<*>?
        get() = jsonType?.classifier as? kotlin.reflect.KClass<*>
}