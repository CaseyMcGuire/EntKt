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

    fun unique(): EdgeBuilder = apply { unique = true }
    fun required(): EdgeBuilder = apply { required = true }
    fun field(name: String): EdgeBuilder = apply { field = name }
    fun through(name: String, target: EntSchema): EdgeBuilder = apply { through = Through(name, target) }
    fun comment(text: String): EdgeBuilder = apply { comment = text }
    fun storageKey(key: String): EdgeBuilder = apply { storageKey = key }
    fun ref(name: String): EdgeBuilder = apply { ref = name }

    fun build(): Edge = Edge(
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
    )
}