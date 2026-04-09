package entkt.schema

class FieldBuilder(
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
    private var validators: MutableList<Validator> = mutableListOf()
    private var comment: String? = null
    private var storageKey: String? = null

    fun optional(): FieldBuilder = apply { optional = true }
    fun nillable(): FieldBuilder = apply { nillable = true }
    fun unique(): FieldBuilder = apply { unique = true }
    fun immutable(): FieldBuilder = apply { immutable = true }
    fun sensitive(): FieldBuilder = apply { sensitive = true }
    fun default(value: Any): FieldBuilder = apply { default = value }
    fun updateDefault(value: Any): FieldBuilder = apply { updateDefault = value }
    fun values(vararg values: String): FieldBuilder = apply { enumValues = values.toList() }
    fun comment(text: String): FieldBuilder = apply { comment = text }
    fun storageKey(key: String): FieldBuilder = apply { storageKey = key }
    fun validate(validator: Validator): FieldBuilder = apply { validators.add(validator) }
    fun minLen(min: Int): FieldBuilder = validate(Validators.minLen(min))
    fun maxLen(max: Int): FieldBuilder = validate(Validators.maxLen(max))
    fun notEmpty(): FieldBuilder = validate(Validators.notEmpty())
    fun match(pattern: Regex): FieldBuilder = validate(Validators.match(pattern))
    fun min(min: Number): FieldBuilder = validate(Validators.min(min))
    fun max(max: Number): FieldBuilder = validate(Validators.max(max))
    fun positive(): FieldBuilder = validate(Validators.positive())
    fun negative(): FieldBuilder = validate(Validators.negative())
    fun nonNegative(): FieldBuilder = validate(Validators.nonNegative())

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