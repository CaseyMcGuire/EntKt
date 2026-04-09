package entkt.schema

@Suppress("UNCHECKED_CAST")
abstract class FieldBuilder<T : FieldBuilder<T>>(
    private val name: String,
    private val type: FieldType,
) {
    private var optional: Boolean = false
    private var nillable: Boolean = false
    private var unique: Boolean = false
    private var immutable: Boolean = false
    private var sensitive: Boolean = false
    private var default: Any? = null
    private var updateDefault: Any? = null
    private var enumValues: List<String>? = null
    protected var validators: MutableList<Validator> = mutableListOf()
    private var comment: String? = null
    private var storageKey: String? = null

    private fun self(): T = this as T

    fun optional(): T = apply { optional = true }.let { self() }
    fun nillable(): T = apply { nillable = true }.let { self() }
    fun unique(): T = apply { unique = true }.let { self() }
    fun immutable(): T = apply { immutable = true }.let { self() }
    fun sensitive(): T = apply { sensitive = true }.let { self() }
    fun default(value: Any): T = apply { default = value }.let { self() }
    fun updateDefault(value: Any): T = apply { updateDefault = value }.let { self() }
    fun comment(text: String): T = apply { comment = text }.let { self() }
    fun storageKey(key: String): T = apply { storageKey = key }.let { self() }
    fun validate(validator: Validator): T = apply { validators.add(validator) }.let { self() }

    internal fun setEnumValues(values: List<String>) {
        this.enumValues = values
    }

    fun build(): Field = Field(
        name = name,
        type = type,
        optional = optional,
        nillable = nillable,
        unique = unique,
        immutable = immutable,
        sensitive = sensitive,
        default = default,
        updateDefault = updateDefault,
        enumValues = enumValues,
        validators = validators,
        comment = comment,
        storageKey = storageKey,
    )
}