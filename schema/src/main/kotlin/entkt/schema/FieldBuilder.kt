package entkt.schema

@Suppress("UNCHECKED_CAST")
abstract class FieldBuilder<Self : FieldBuilder<Self, V>, V> internal constructor(
    override val fieldName: String,
    private val type: FieldType,
) : FieldHandle<V> {
    override val fieldType: FieldType get() = type

    internal var frozen: Boolean = false
    @PublishedApi internal var declarationOwner: EntSchema? = null

    /**
     * Kotlin `val` name of the schema property that holds this
     * builder, captured by [EntSchema.finalize] via reflection over
     * `KProperty.javaField` (no getter invocation). Null until
     * capture runs or for builders that have no qualifying `val`
     * on the schema class (computed getters / delegated /
     * inherited / mixin-backed / programmatic registration).
     *
     * Propagated into [Field.declarationName] by [build].
     *
     * See `docs/possible-features/edge-mutation/06-field-backed-fk-declaration-names.md`.
     */
    internal var declarationName: String? = null

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

    fun build(): Field {
        if (immutable && updateDefault != null) {
            error("Field '$fieldName' cannot be both immutable and have an updateDefault — immutable fields are never updated")
        }
        // Native columns inherit the base modifier surface but reject the ones
        // that don't make sense (RFC §3). A UNIQUE index over a native value
        // such as a high-dimensional vector is broken.
        val nativeStorage = storage
        if (nativeStorage is ColumnStorage.Native && unique) {
            error("Field '$fieldName' is a native ${nativeStorage.typeName} column; .unique() is not supported")
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
        )
    }
}
