package entkt.schema

class StringFieldBuilder(name: String) : FieldBuilder<StringFieldBuilder>(name, FieldType.STRING) {
    fun minLen(min: Int): StringFieldBuilder = validate(Validators.minLen(min))
    fun maxLen(max: Int): StringFieldBuilder = validate(Validators.maxLen(max))
    fun notEmpty(): StringFieldBuilder = validate(Validators.notEmpty())
    fun match(pattern: Regex): StringFieldBuilder = validate(Validators.match(pattern))
}