package entkt.schema

data class Index(
    val fields: List<String>,
    val unique: Boolean = false,
    val storageKey: String? = null,
)