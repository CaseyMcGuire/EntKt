package entkt.schema

class FloatFieldBuilder internal constructor(name: String) : FieldBuilder<FloatFieldBuilder, Float>(name, FieldType.FLOAT) {
    fun unique(): FloatFieldBuilder = apply { setUnique() }
    fun default(value: Float): FloatFieldBuilder = apply { setDefault(value) }
}
