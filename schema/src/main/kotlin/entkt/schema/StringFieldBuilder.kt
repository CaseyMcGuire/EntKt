package entkt.schema

class StringFieldBuilder internal constructor(name: String) : FieldBuilder<StringFieldBuilder, String>(name, FieldType.STRING) {
    fun minLength(min: Int): StringFieldBuilder = validate(Validators.minLength(min))
    fun maxLength(max: Int): StringFieldBuilder = validate(Validators.maxLength(max))
    fun notEmpty(): StringFieldBuilder = validate(Validators.notEmpty())
    fun match(pattern: Regex): StringFieldBuilder = validate(Validators.match(pattern))
    fun default(value: String): StringFieldBuilder = apply { setDefault(value) }
}
