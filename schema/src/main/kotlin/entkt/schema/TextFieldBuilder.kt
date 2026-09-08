package entkt.schema

class TextFieldBuilder internal constructor(name: String) : FieldBuilder<TextFieldBuilder, String>(name, FieldType.TEXT) {
    fun default(value: String): TextFieldBuilder = apply { setDefault(value) }
}
