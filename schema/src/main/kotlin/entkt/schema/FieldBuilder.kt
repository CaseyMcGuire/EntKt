package entkt.schema

import kotlin.reflect.KProperty

@Suppress("UNCHECKED_CAST")
abstract class FieldBuilder<Self : FieldBuilder<Self, V>, V> internal constructor(
    override val fieldName: String,
    private val type: FieldType,
) : FieldHandle<V> {
    override val fieldType: FieldType get() = type

    internal var frozen: Boolean = false
    @PublishedApi internal var declarationOwner: EntSchema? = null

    /**
     * Kotlin `val` name of the schema property this builder is
     * delegated to — the name every generated field API uses.
     *
     * Set by [provideDelegate] while the property is constructed, so a
     * builder declared with `by` carries its declaration name from that
     * moment on. Null for a builder that was registered but never bound
     * to a qualifying property (`val x = string(...)`), which schema
     * finalization rejects.
     *
     * Propagated into [Field.declarationName] by [build].
     *
     * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
     */
    internal var declarationName: String? = null

    /**
     * The concrete [EntMixin] class this builder was bound inside, or
     * null when it bound directly on the schema class.
     *
     * Mixin fields register on the host schema but have no property of
     * that name on the host, so finalization exempts them from the
     * "declared directly on the concrete schema class" check. Recording
     * the class rather than a flag lets `include(...)` verify each
     * mixin's own declarations without seeing a nested mixin's.
     */
    internal var declarationMixinClass: kotlin.reflect.KClass<*>? = null

    /** True when this builder was bound inside an [EntMixin]. */
    internal val declarationFromMixin: Boolean get() = declarationMixinClass != null

    /**
     * Bind this builder to the Kotlin property declaring it, and hand
     * the property the builder itself as its delegate.
     *
     * Declared on the self-typed base so **one** operator pair covers
     * every field builder subclass while preserving each one's concrete
     * static type: `val title by string("t")` has type
     * [StringFieldBuilder], so `.maxLength(64)` still resolves, and a
     * handle passed to `index(...)`, `.field(...)`, or an inverse
     * reference keeps its exact type.
     */
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): Self {
        declarationName = bindDeclarationName(
            property = property,
            kind = "Field",
            storageName = fieldName,
            owner = declarationOwner,
            existing = declarationName,
            thisRef = thisRef,
            mixinAllowed = true,
            onMixinBinding = { declarationMixinClass = it },
        )
        return self()
    }

    /** Reading the delegated property yields the builder itself. */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Self = self()

    protected fun checkNotFrozen() {
        check(!frozen) { "Field '$fieldName' cannot be modified after schema finalization" }
    }

    private var nullable: Boolean = false
    private var unique: Boolean = false
    private var immutable: Boolean = false
    private var sensitive: Boolean = false
    private var default: Any? = null
    private var updateDefault: UpdateDefault? = null
    private var enumClass: kotlin.reflect.KClass<out Enum<*>>? = null
    private var storage: ColumnStorage? = null
    private var jsonType: kotlin.reflect.KType? = null
    protected var validators: MutableList<Validator> = mutableListOf()

    protected fun setUpdateDefault(value: UpdateDefault) {
        checkNotFrozen()
        this.updateDefault = value
    }
    private var comment: String? = null

    private fun self(): Self = this as Self

    fun nullable(): Self = apply { checkNotFrozen(); nullable = true }.let { self() }
    fun unique(): Self = apply { checkNotFrozen(); unique = true }.let { self() }
    fun immutable(): Self = apply { checkNotFrozen(); immutable = true }.let { self() }
    fun sensitive(): Self = apply { checkNotFrozen(); sensitive = true }.let { self() }
    protected fun setDefault(value: Any) { checkNotFrozen(); default = value }
    fun comment(text: String): Self = apply { checkNotFrozen(); comment = text }.let { self() }
    protected fun validate(validator: Validator): Self = apply { checkNotFrozen(); validators.add(validator) }.let { self() }

    @PublishedApi
    internal fun setEnumClass(klass: kotlin.reflect.KClass<out Enum<*>>) {
        this.enumClass = klass
    }

    /**
     * Attach native storage metadata (e.g. Postgres `pgvector`). Set once at
     * registration (mirrors [setEnumClass]); folded into [Field] by [build].
     */
    @PublishedApi
    internal fun setNativeStorage(s: ColumnStorage) {
        this.storage = s
    }

    /** Native storage attached to this field (e.g. pgvector), or null. */
    internal val nativeStorage: ColumnStorage? get() = storage

    /**
     * Attach the full Kotlin type (including type arguments) for a typed JSON
     * field. Set once at registration (mirrors [setEnumClass]); folded into
     * [Field] by [build].
     */
    @PublishedApi
    internal fun setJsonType(type: kotlin.reflect.KType) {
        this.jsonType = type
    }

    fun build(): Field {
        if (immutable && updateDefault != null) {
            error("Field '$fieldName' cannot be both immutable and have an updateDefault — immutable fields are never updated")
        }
        // Native columns inherit the base modifier surface but reject the ones
        // that don't make sense. A UNIQUE index over a native value
        // such as a high-dimensional vector is broken.
        val nativeStorage = storage
        if (nativeStorage is ColumnStorage.Native && unique) {
            error("Field '$fieldName' is a native ${nativeStorage.typeName} column; .unique() is not supported")
        }
        // Typed JSON columns inherit the base modifier surface but reject the
        // ones deferred in V1: a UNIQUE/default over a whole jsonb document.
        if (type == FieldType.JSON) {
            if (unique) error("Field '$fieldName' is a JSON column; .unique() is not supported")
            if (default != null) error("Field '$fieldName' is a JSON column; defaults are not supported")
        }
        // Non-finite IEEE values (NaN / ±Infinity) have no portable SQL
        // literal — Postgres needs the quoted-cast form, and generated
        // Kotlin would emit a bare `NaN` token. Reject them at the source
        // rather than producing broken DDL or code downstream.
        val d = default
        if ((d is Double && !d.isFinite()) || (d is Float && !d.isFinite())) {
            error("Field '$fieldName' default must be a finite number, got $d")
        }
        if (enumClass != null && default is Enum<*>) {
            require((default as Enum<*>)::class == enumClass) {
                "Field '$fieldName' default must be a ${enumClass!!.simpleName} constant, " +
                    "got ${(default as Enum<*>)::class.simpleName}"
            }
        }
        return Field(
            name = fieldName,
            type = type,
            nullable = nullable,
            unique = unique,
            immutable = immutable,
            sensitive = sensitive,
            default = default,
            updateDefault = updateDefault,
            enumClass = enumClass,
            validators = validators,
            comment = comment,
            declarationName = declarationName,
            storage = storage,
            jsonType = jsonType,
        )
    }
}
