package entkt.schema

class TextFieldBuilder internal constructor(name: String) : FieldBuilder<TextFieldBuilder, String>(name, FieldType.TEXT) {
    fun minLength(min: Int): TextFieldBuilder = validate(Validators.minLength(min))
    fun maxLength(max: Int): TextFieldBuilder = validate(Validators.maxLength(max))
    fun notEmpty(): TextFieldBuilder = validate(Validators.notEmpty())
    fun match(pattern: Regex): TextFieldBuilder = validate(Validators.match(pattern))
    fun default(value: String): TextFieldBuilder = apply { setDefault(value) }
}
