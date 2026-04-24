package entkt.schema

class BoolFieldBuilder(name: String) : FieldBuilder<BoolFieldBuilder>(name, FieldType.BOOL) {
    fun default(value: Boolean): BoolFieldBuilder = apply { setDefault(value) }
}