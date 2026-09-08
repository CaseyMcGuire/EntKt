package entkt.schema

class StringFieldBuilder internal constructor(name: String) : FieldBuilder<StringFieldBuilder, String>(name, FieldType.STRING) {
    fun unique(): StringFieldBuilder = apply { setUnique() }
    fun default(value: String): StringFieldBuilder = apply { setDefault(value) }
}
