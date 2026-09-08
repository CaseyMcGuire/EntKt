package entkt.schema

class FloatFieldBuilder internal constructor(name: String) : FieldBuilder<FloatFieldBuilder, Float>(name, FieldType.FLOAT) {
    fun default(value: Float): FloatFieldBuilder = apply { setDefault(value) }
}
