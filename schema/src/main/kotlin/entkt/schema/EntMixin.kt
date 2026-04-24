package entkt.schema

interface EntMixin {
    fun fields(): List<Field> = emptyList()
    fun indexes(): List<Index> = emptyList()
}