package entkt.schema

data class EntId(
    val type: FieldType,
    val autoIncrement: Boolean = false,
) {
    companion object {
        fun int(): EntId = EntId(type = FieldType.INT, autoIncrement = true)
        fun long(): EntId = EntId(type = FieldType.LONG, autoIncrement = true)
        fun uuid(): EntId = EntId(type = FieldType.UUID)
        fun string(): EntId = EntId(type = FieldType.STRING)
    }
}