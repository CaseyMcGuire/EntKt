package entkt.schema

class IndexBuilder(
    private val fields: List<String>,
) {
    private var unique: Boolean = false
    private var storageKey: String? = null

    fun unique(): IndexBuilder = apply { unique = true }
    fun storageKey(key: String): IndexBuilder = apply { storageKey = key }

    fun build(): Index = Index(
        fields = fields,
        unique = unique,
        storageKey = storageKey,
    )
}