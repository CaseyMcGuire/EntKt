package entkt.schema

class LongFieldBuilder internal constructor(name: String) : FieldBuilder<LongFieldBuilder, Long>(name, FieldType.LONG) {
    fun default(value: Long): LongFieldBuilder = apply { setDefault(value) }
}
