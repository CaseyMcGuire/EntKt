package entkt.schema

class TextFieldBuilder internal constructor(name: String) : FieldBuilder<TextFieldBuilder, String>(name, FieldType.TEXT) {
    fun minLen(min: Int): TextFieldBuilder = validate(Validators.minLen(min))
    fun maxLen(max: Int): TextFieldBuilder = validate(Validators.maxLen(max))
    fun notEmpty(): TextFieldBuilder = validate(Validators.notEmpty())
    fun match(pattern: Regex): TextFieldBuilder = validate(Validators.match(pattern))
    fun default(value: String): TextFieldBuilder = apply { setDefault(value) }
}
