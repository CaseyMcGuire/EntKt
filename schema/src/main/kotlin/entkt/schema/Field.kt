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
     * Kotlin `val` name of the schema property that produced this
     * field's backing builder. Populated by `EntSchema.finalize()`'s
     * declaration-name capture pass (declaration-name capture,
     * `docs/possible-features/edge-mutation/06-field-backed-fk-declaration-names.md`)
     * by walking the schema class's direct `val` properties via
     * `KProperty.javaField` (no getter invocation, so computed
     * getters / delegated properties are skipped without side
     * effects).
     *
     * Null when:
     * - no direct property on the concrete schema class references
     *   this field's builder (computed getter, delegated, inherited,
     *   mixin-backed declaration site, programmatic registration)
     * - the schema hasn't been finalized yet
     *
     * Codegen reads this only for **field-backed FK** edges
     * (`belongsTo(...).field(handle)`) to derive the generated FK
     * API name from the Kotlin val rather than from
     * `toCamelCase(column)`. Plain scalar fields keep their existing
     * `toCamelCase(name)`-derived property name regardless of
     * declarationName.
     */
    val declarationName: String? = null,
    /**
     * Native/rich storage metadata when this field is more than a plain
     * [FieldType] → SQL-type mapping (RFC "Native Database Column Types").
     * Null for ordinary scalar/enum fields. Set by builders via
     * `FieldBuilder.setNativeStorage(...)` and folded in by `build()`.
     */
    val storage: ColumnStorage? = null,
    /**
     * The `@Serializable` Kotlin class for a `FieldType.JSON` field. Null for
     * non-JSON fields. Set by `JsonFieldBuilder` via
     * `FieldBuilder.setJsonClass(...)`; codegen derives `X.serializer()` from it
     * (the schema module never touches the serializer, only the class).
     */
    val jsonClass: kotlin.reflect.KClass<*>? = null,
)