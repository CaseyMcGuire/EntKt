package entkt.schema

data class Edge(
    val name: String,
    val type: EdgeType,
    val target: EntSchema,
    val unique: Boolean = false,
    val required: Boolean = false,
    val field: String? = null,
    val through: Through? = null,
    val comment: String? = null,
    val storageKey: String? = null,
    val ref: String? = null,
    val onDelete: OnDelete? = null,
)

data class Through(
    val name: String,
    val target: EntSchema,
    /** Junction edge name pointing at the source schema, for disambiguation. */
    val sourceEdge: String? = null,
    /** Junction edge name pointing at the target schema, for disambiguation. */
    val targetEdge: String? = null,
)