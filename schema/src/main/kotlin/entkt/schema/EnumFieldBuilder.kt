package entkt.schema

class EnumFieldBuilder internal constructor(name: String) : FieldBuilder<EnumFieldBuilder, Enum<*>>(name, FieldType.ENUM) {
    fun default(value: Enum<*>): EnumFieldBuilder = apply { setDefault(value) }
}
