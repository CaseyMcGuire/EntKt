package entkt.schema

class EnumFieldBuilder(name: String) : FieldBuilder<EnumFieldBuilder>(name, FieldType.ENUM) {
    fun values(vararg values: String): EnumFieldBuilder = apply { setEnumValues(values.toList()) }
    fun default(value: String): EnumFieldBuilder = apply { setDefault(value) }
    fun default(value: Enum<*>): EnumFieldBuilder = apply { setDefault(value) }
}