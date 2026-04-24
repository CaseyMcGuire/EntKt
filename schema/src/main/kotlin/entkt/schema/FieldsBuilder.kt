package entkt.schema


class FieldsBuilder {
    @PublishedApi
    internal val fields = mutableListOf<FieldBuilder<*>>()

    fun string(name: String): StringFieldBuilder = StringFieldBuilder(name).also { fields.add(it) }
    fun text(name: String): TextFieldBuilder = TextFieldBuilder(name).also { fields.add(it) }
    fun bool(name: String): BoolFieldBuilder = BoolFieldBuilder(name).also { fields.add(it) }
    fun int(name: String): IntFieldBuilder = IntFieldBuilder(name).also { fields.add(it) }
    fun long(name: String): LongFieldBuilder = LongFieldBuilder(name).also { fields.add(it) }
    fun float(name: String): FloatFieldBuilder = FloatFieldBuilder(name).also { fields.add(it) }
    fun double(name: String): DoubleFieldBuilder = DoubleFieldBuilder(name).also { fields.add(it) }
    fun time(name: String): TimeFieldBuilder = TimeFieldBuilder(name).also { fields.add(it) }
    fun uuid(name: String): UuidFieldBuilder = UuidFieldBuilder(name).also { fields.add(it) }
    fun bytes(name: String): BytesFieldBuilder = BytesFieldBuilder(name).also { fields.add(it) }
    inline fun <reified E : Enum<E>> enum(name: String): EnumFieldBuilder =
        EnumFieldBuilder(name).also {
            it.setEnumClass(E::class)
            fields.add(it)
        }

    fun build(): List<Field> = fields.map { it.build() }
}

fun fields(block: FieldsBuilder.() -> Unit): List<Field> {
    return FieldsBuilder().apply(block).build()
}