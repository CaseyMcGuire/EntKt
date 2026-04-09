package entkt.schema

class IndexesBuilder {
    private val indexes = mutableListOf<IndexBuilder>()

    fun index(vararg fields: String): IndexBuilder =
        IndexBuilder(fields.toList()).also { indexes.add(it) }

    fun build(): List<Index> = indexes.map { it.build() }
}

fun indexes(block: IndexesBuilder.() -> Unit): List<Index> {
    return IndexesBuilder().apply(block).build()
}