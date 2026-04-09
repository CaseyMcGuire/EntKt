package entkt.schema

class FieldsBuilder {
    private val fields = mutableListOf<FieldBuilder>()

    fun string(name: String): FieldBuilder = FieldBuilder(name, FieldType.STRING).also { fields.add(it) }
    fun text(name: String): FieldBuilder = FieldBuilder(name, FieldType.TEXT).also { fields.add(it) }
    fun bool(name: String): FieldBuilder = FieldBuilder(name, FieldType.BOOL).also { fields.add(it) }
    fun int(name: String): FieldBuilder = FieldBuilder(name, FieldType.INT).also { fields.add(it) }
    fun long(name: String): FieldBuilder = FieldBuilder(name, FieldType.LONG).also { fields.add(it) }
    fun float(name: String): FieldBuilder = FieldBuilder(name, FieldType.FLOAT).also { fields.add(it) }
    fun double(name: String): FieldBuilder = FieldBuilder(name, FieldType.DOUBLE).also { fields.add(it) }
    fun time(name: String): FieldBuilder = FieldBuilder(name, FieldType.TIME).also { fields.add(it) }
    fun uuid(name: String): FieldBuilder = FieldBuilder(name, FieldType.UUID).also { fields.add(it) }
    fun bytes(name: String): FieldBuilder = FieldBuilder(name, FieldType.BYTES).also { fields.add(it) }
    fun enum(name: String): FieldBuilder = FieldBuilder(name, FieldType.ENUM).also { fields.add(it) }

    fun build(): List<Field> = fields.map { it.build() }
}

fun fields(block: FieldsBuilder.() -> Unit): List<Field> {
    return FieldsBuilder().apply(block).build()
}