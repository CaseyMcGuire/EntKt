package entkt.schema

interface EntMixin {
    fun fields(): List<Field> = emptyList()
    fun edges(): List<Edge> = emptyList()
    fun indexes(): List<Index> = emptyList()
}