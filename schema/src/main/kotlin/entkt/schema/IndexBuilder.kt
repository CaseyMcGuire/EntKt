package entkt.schema

class IndexBuilder internal constructor(
    private val name: String,
    private val columns: List<IndexableColumn>,
) {
    internal var frozen: Boolean = false

    private fun checkNotFrozen() {
        check(!frozen) { "Index cannot be modified after schema finalization" }
    }

    private var unique: Boolean = false
    private var where: String? = null
    private var using: String? = null
    private var opclasses: List<String>? = null
    private var with: Map<String, String>? = null

    fun unique(): IndexBuilder = apply { checkNotFrozen(); unique = true }
    fun where(clause: String): IndexBuilder = apply { checkNotFrozen(); where = clause }

    /**
     * Attach native index metadata (access method + per-column operator class +
     * optional storage params) — e.g. pgvector's `USING hnsw (col
     * vector_cosine_ops)`. Set by the `entkt.postgres.vector` `.hnsw()` /
     * `.ivfflat()` builders; folded into [Index] by [build].
     */
    @PublishedApi
    internal fun setVectorIndex(using: String, opclasses: List<String>, with: Map<String, String>?) {
        checkNotFrozen()
        this.using = using
        this.opclasses = opclasses
        this.with = with
    }

    fun build(): Index {
        require(columns.isNotEmpty()) { "Index must have at least one field" }
        return Index(
            name = name,
            fields = columns.map { it.fieldName },
            unique = unique,
            where = where,
            using = using,
            opclasses = opclasses,
            with = with,
        )
    }
}
