package entkt.schema

data class Index(
    val name: String,
    val fields: List<String>,
    val unique: Boolean = false,
    val where: String? = null,
    /** Access method (`"hnsw"` / `"ivfflat"` for pgvector). Null = btree default. */
    val using: String? = null,
    /** Per-column operator class, e.g. `["vector_cosine_ops"]`. Null for btree. */
    val opclasses: List<String>? = null,
    /** Index storage params rendered `WITH (k = v, …)`, e.g. IVFFlat `{"lists":"100"}`. */
    val with: Map<String, String>? = null,
)
