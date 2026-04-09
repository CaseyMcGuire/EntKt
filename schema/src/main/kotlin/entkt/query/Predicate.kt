package entkt.query

data class Predicate(
    val field: String,
    val op: Op,
    val value: Any?,
)

enum class Op {
    EQ,
    NEQ,
    GT,
    GTE,
    LT,
    LTE,
    IN,
    NOT_IN,
    IS_NULL,
    IS_NOT_NULL,
    CONTAINS,
    HAS_PREFIX,
    HAS_SUFFIX,
}