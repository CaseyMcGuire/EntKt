package entkt.schema

data class Edge(
    val name: String,
    val target: EntSchema,
    val kind: EdgeKind,
    val ref: String? = null,
    val comment: String? = null,
)

data class Through(
    val target: EntSchema,
    /** Junction edge name pointing at the source schema, for disambiguation. */
    val sourceEdge: String? = null,
    /** Junction edge name pointing at the target schema, for disambiguation. */
    val targetEdge: String? = null,
)
