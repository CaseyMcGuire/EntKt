package entkt.schema

class EnumFieldBuilder(name: String) : FieldBuilder<EnumFieldBuilder>(name, FieldType.ENUM) {
    fun values(vararg values: String): EnumFieldBuilder = apply { setEnumValues(values.toList()) }
}