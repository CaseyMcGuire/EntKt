package entkt.schema

class EdgesBuilder {
    private val edges = mutableListOf<EdgeBuilder>()

    fun to(name: String, target: EntSchema): EdgeBuilder =
        EdgeBuilder(name, EdgeType.TO, target).also { edges.add(it) }

    fun from(name: String, target: EntSchema): EdgeBuilder =
        EdgeBuilder(name, EdgeType.FROM, target).also { edges.add(it) }

    fun build(): List<Edge> = edges.map { it.build() }
}

fun edges(block: EdgesBuilder.() -> Unit): List<Edge> {
    return EdgesBuilder().apply(block).build()
}