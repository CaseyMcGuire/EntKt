package entkt.schema

abstract class EntSchema {
    open fun id(): EntId = EntId.int()
    open fun fields(): List<Field> = emptyList()
    open fun edges(): List<Edge> = emptyList()
    open fun indexes(): List<Index> = emptyList()
    open fun mixins(): List<EntMixin> = emptyList()
}