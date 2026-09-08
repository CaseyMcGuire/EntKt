package entkt.schema

class StringFieldBuilder internal constructor(name: String) : FieldBuilder<StringFieldBuilder, String>(name, FieldType.STRING) {
    fun default(value: String): StringFieldBuilder = apply { setDefault(value) }
}
