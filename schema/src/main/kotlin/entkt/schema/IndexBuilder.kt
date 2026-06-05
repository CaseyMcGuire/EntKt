package entkt.schema

class IndexBuilder internal constructor(
    private val name: String,
    private val columns: List<IndexableColumn>,
    /** True when created via `postgresVectorIndex(...)` — gates `.hnsw()`/`.ivfflat()`. */
    private val isVectorIndex: Boolean = false,
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
        require(isVectorIndex) {
            "Index '$name': .hnsw()/.ivfflat() is only valid on a postgresVectorIndex(...), not a plain index()"
        }
        this.using = using
        this.opclasses = opclasses
        this.with = with
    }

    fun build(): Index {
        require(columns.isNotEmpty()) { "Index must have at least one field" }
        if (isVectorIndex) {
            // A pgvector index has no UNIQUE form, and needs an access method
            // (there is no default btree opclass for `vector`).
            require(!unique) { "pgvector index '$name' cannot be UNIQUE" }
            require(using != null) {
                "postgresVectorIndex('$name') requires an access method; call .hnsw(...) or .ivfflat(...)"
            }
        }
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
