package entkt.schema

class EdgesBuilder {
    private val edges = mutableListOf<EdgeBuilder>()

    fun to(name: String, target: EntSchema): EdgeBuilder =
        EdgeBuilder(name, EdgeType.TO, target).also { edges.add(it) }

    fun from(name: String, target: EntSchema): EdgeBuilder =
        EdgeBuilder(name, EdgeType.FROM, target).also { edges.add(it) }

    fun build(): List<Edge> {
        val built = edges.map { it.build() }
        val seen = mutableSetOf<String>()
        for (edge in built) {
            require(seen.add(edge.name)) {
                "Duplicate edge name '${edge.name}' — edge names must be unique per schema"
            }
        }
        return built
    }
}

fun edges(block: EdgesBuilder.() -> Unit): List<Edge> {
    return EdgesBuilder().apply(block).build()
}