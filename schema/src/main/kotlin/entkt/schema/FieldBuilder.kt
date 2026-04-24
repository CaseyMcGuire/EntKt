package entkt.schema

@Suppress("UNCHECKED_CAST")
abstract class FieldBuilder<T : FieldBuilder<T>>(
    private val name: String,
    private val type: FieldType,
) {
    private var nullable: Boolean = false
    private var unique: Boolean = false
    private var immutable: Boolean = false
    private var sensitive: Boolean = false
    private var default: Any? = null
    private var updateDefault: UpdateDefault? = null
    private var enumValues: List<String>? = null
    private var enumClass: kotlin.reflect.KClass<out Enum<*>>? = null
    protected var validators: MutableList<Validator> = mutableListOf()

    protected fun setUpdateDefault(value: UpdateDefault) {
        this.updateDefault = value
    }
    private var comment: String? = null
    private var storageKey: String? = null

    private fun self(): T = this as T

    fun nullable(): T = apply { nullable = true }.let { self() }
    fun optional(): T = nullable()
    fun unique(): T = apply { unique = true }.let { self() }
    fun immutable(): T = apply { immutable = true }.let { self() }
    fun sensitive(): T = apply { sensitive = true }.let { self() }
    fun default(value: Any): T = apply { default = value }.let { self() }
    fun comment(text: String): T = apply { comment = text }.let { self() }
    fun storageKey(key: String): T = apply { storageKey = key }.let { self() }
    protected fun validate(validator: Validator): T = apply { validators.add(validator) }.let { self() }

    @PublishedApi
    internal fun setEnumValues(values: List<String>) {
        this.enumValues = values
    }

    @PublishedApi
    internal fun setEnumClass(klass: kotlin.reflect.KClass<out Enum<*>>) {
        this.enumClass = klass
    }

    fun build(): Field {
        if (immutable && updateDefault != null) {
            error("Field '$name' cannot be both immutable and have an updateDefault — immutable fields are never updated")
        }
        return Field(
        name = name,
        type = type,
        nullable = nullable,
        unique = unique,
        immutable = immutable,
        sensitive = sensitive,
        default = default,
        updateDefault = updateDefault,
        enumValues = enumValues,
        enumClass = enumClass,
        validators = validators,
        comment = comment,
        storageKey = storageKey,
    )
    }
}