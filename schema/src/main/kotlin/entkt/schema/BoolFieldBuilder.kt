package entkt.schema

class BoolFieldBuilder internal constructor(name: String) : FieldBuilder<BoolFieldBuilder, Boolean>(name, FieldType.BOOL) {
    fun default(value: Boolean): BoolFieldBuilder = apply { setDefault(value) }
}
