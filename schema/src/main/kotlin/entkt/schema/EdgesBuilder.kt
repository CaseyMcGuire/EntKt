package entkt.schema

class EdgesBuilder {
    private val edges = mutableListOf<EdgeBuilderBase>()

    fun belongsTo(name: String, target: EntSchema): BelongsToBuilder =
        BelongsToBuilder(name, target).also { edges.add(it) }

    fun hasMany(name: String, target: EntSchema): HasManyBuilder =
        HasManyBuilder(name, target).also { edges.add(it) }

    fun hasOne(name: String, target: EntSchema): HasOneBuilder =
        HasOneBuilder(name, target).also { edges.add(it) }

    fun manyToMany(name: String, target: EntSchema): ManyToManyBuilder =
        ManyToManyBuilder(name, target).also { edges.add(it) }

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
