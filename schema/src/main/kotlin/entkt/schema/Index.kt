package entkt.schema

data class Index(
    val name: String,
    val fields: List<String>,
    val unique: Boolean = false,
    val where: String? = null,
)
