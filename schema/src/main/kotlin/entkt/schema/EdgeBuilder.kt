package entkt.schema

interface EdgeBuilderBase {
    fun build(): Edge
}

class BelongsToBuilder(
    private val name: String,
    private val target: EntSchema,
) : EdgeBuilderBase {
    private var required: Boolean = false
    private var unique: Boolean = false
    private var field: String? = null
    private var onDelete: OnDelete? = null
    private var ref: String? = null
    private var comment: String? = null

    fun required(): BelongsToBuilder = apply { required = true }
    fun unique(): BelongsToBuilder = apply { unique = true }
    fun field(name: String): BelongsToBuilder = apply { field = name }
    fun onDelete(action: OnDelete): BelongsToBuilder = apply { onDelete = action }
    fun ref(name: String): BelongsToBuilder = apply { ref = name }
    fun comment(text: String): BelongsToBuilder = apply { comment = text }

    override fun build(): Edge {
        if (onDelete == OnDelete.SET_NULL && required) {
            error(
                "onDelete SET_NULL is incompatible with required edges — " +
                    "edge '$name' cannot be both required (NOT NULL) and SET_NULL on delete",
            )
        }
        return Edge(
            name = name,
            target = target,
            kind = EdgeKind.BelongsTo(
                required = required,
                unique = unique,
                field = field,
                onDelete = onDelete,
            ),
            ref = ref,
            comment = comment,
        )
    }
}

class HasManyBuilder(
    private val name: String,
    private val target: EntSchema,
) : EdgeBuilderBase {
    private var ref: String? = null
    private var comment: String? = null

    fun ref(name: String): HasManyBuilder = apply { ref = name }
    fun comment(text: String): HasManyBuilder = apply { comment = text }

    override fun build(): Edge = Edge(
        name = name,
        target = target,
        kind = EdgeKind.HasMany,
        ref = ref,
        comment = comment,
    )
}

class HasOneBuilder(
    private val name: String,
    private val target: EntSchema,
) : EdgeBuilderBase {
    private var ref: String? = null
    private var comment: String? = null

    fun ref(name: String): HasOneBuilder = apply { ref = name }
    fun comment(text: String): HasOneBuilder = apply { comment = text }

    override fun build(): Edge = Edge(
        name = name,
        target = target,
        kind = EdgeKind.HasOne,
        ref = ref,
        comment = comment,
    )
}

class ManyToManyBuilder(
    private val name: String,
    private val target: EntSchema,
) : EdgeBuilderBase {
    private var through: Through? = null
    private var comment: String? = null

    fun through(junction: EntSchema): ManyToManyBuilder = apply {
        through = Through(junction)
    }

    fun through(
        junction: EntSchema,
        sourceEdge: String,
        targetEdge: String,
    ): ManyToManyBuilder = apply {
        through = Through(junction, sourceEdge, targetEdge)
    }

    fun comment(text: String): ManyToManyBuilder = apply { comment = text }

    override fun build(): Edge {
        val t = through
            ?: error("manyToMany edge '$name' must have a .through() junction schema")
        return Edge(
            name = name,
            target = target,
            kind = EdgeKind.ManyToMany(t),
            comment = comment,
        )
    }
}
