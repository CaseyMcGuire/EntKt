package entkt.schema

class IntFieldBuilder internal constructor(name: String) : FieldBuilder<IntFieldBuilder, Int>(name, FieldType.INT) {
    fun unique(): IntFieldBuilder = apply { setUnique() }
    fun default(value: Int): IntFieldBuilder = apply { setDefault(value) }
}
