package entkt.schema

@Suppress("UNCHECKED_CAST")
abstract class FieldBuilder<Self : FieldBuilder<Self, V>, V> internal constructor(
    override val fieldName: String,
    private val type: FieldType,
) : FieldHandle<V> {
    override val fieldType: FieldType get() = type

    internal var frozen: Boolean = false
    @PublishedApi internal var declarationOwner: EntSchema? = null

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
    protected var validators: MutableList<Validator> = mutableListOf()

    protected fun setUpdateDefault(value: UpdateDefault) {
        checkNotFrozen()
        this.updateDefault = value
    }
    private var comment: String? = null

    private fun self(): Self = this as Self

    fun nullable(): Self = apply { checkNotFrozen(); nullable = true }.let { self() }
    fun optional(): Self = nullable()
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

    fun build(): Field {
        if (immutable && updateDefault != null) {
            error("Field '$fieldName' cannot be both immutable and have an updateDefault — immutable fields are never updated")
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
        )
    }
}
