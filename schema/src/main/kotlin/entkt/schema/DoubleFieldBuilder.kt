package entkt.schema

class DoubleFieldBuilder internal constructor(name: String) : FieldBuilder<DoubleFieldBuilder, Double>(name, FieldType.DOUBLE) {
    fun default(value: Double): DoubleFieldBuilder = apply { setDefault(value) }
}
