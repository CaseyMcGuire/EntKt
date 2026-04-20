package entkt.schema

class EdgeBuilder(
    private val name: String,
    private val type: EdgeType,
    private val target: EntSchema,
) {
    private var unique: Boolean = false
    private var required: Boolean = false
    private var field: String? = null
    private var through: Through? = null
    private var comment: String? = null
    private var storageKey: String? = null
    private var ref: String? = null
    private var onDelete: OnDelete? = null

    fun unique(): EdgeBuilder = apply { unique = true }
    fun required(): EdgeBuilder = apply { required = true }
    fun field(name: String): EdgeBuilder = apply { field = name }
    fun through(name: String, target: EntSchema): EdgeBuilder = apply { through = Through(name, target) }
    fun through(
        name: String,
        target: EntSchema,
        sourceEdge: String,
        targetEdge: String,
    ): EdgeBuilder = apply { through = Through(name, target, sourceEdge, targetEdge) }
    fun comment(text: String): EdgeBuilder = apply { comment = text }
    fun storageKey(key: String): EdgeBuilder = apply { storageKey = key }
    fun ref(name: String): EdgeBuilder = apply { ref = name }
    fun onDelete(action: OnDelete): EdgeBuilder = apply { onDelete = action }

    fun build(): Edge {
        if (onDelete != null) {
            require(unique) {
                "onDelete is only supported on unique (O2O) edges, but edge '$name' is not unique"
            }
            require(through == null) {
                "onDelete is not supported on edges with .through(), but edge '$name' uses .through()"
            }
            require(!(onDelete == OnDelete.SET_NULL && required)) {
                "onDelete SET_NULL is incompatible with required edges — " +
                    "edge '$name' cannot be both required (NOT NULL) and SET_NULL on delete"
            }
        }
        return Edge(
            name = name,
            type = type,
            target = target,
            unique = unique,
            required = required,
            field = field,
            through = through,
            comment = comment,
            storageKey = storageKey,
            ref = ref,
            onDelete = onDelete,
        )
    }
}