package entkt.schema

class EnumFieldBuilder(name: String) : FieldBuilder<EnumFieldBuilder>(name, FieldType.ENUM) {
    fun default(value: Enum<*>): EnumFieldBuilder = apply { setDefault(value) }
}