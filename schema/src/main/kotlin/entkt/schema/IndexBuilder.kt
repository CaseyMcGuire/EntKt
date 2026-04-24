package entkt.schema

class IndexBuilder(
    private val fields: List<String>,
) {
    private var unique: Boolean = false
    private var storageKey: String? = null
    private var where: String? = null

    fun unique(): IndexBuilder = apply { unique = true }
    fun storageKey(key: String): IndexBuilder = apply { storageKey = key }
    fun where(clause: String): IndexBuilder = apply { where = clause }

    fun build(): Index {
        require(fields.isNotEmpty()) { "Index must have at least one field" }
        return Index(
        fields = fields,
        unique = unique,
        storageKey = storageKey,
        where = where,
    )
    }
}