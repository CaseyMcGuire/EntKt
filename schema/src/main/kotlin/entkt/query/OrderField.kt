package entkt.query

/**
 * An order-by clause: a field name plus direction, scoped to an entity
 * [E]. The [E] type parameter is a compile-time witness only — it
 * carries no runtime data — so `OrderField<Post>` can't be passed where
 * `OrderField<User>` is expected.
 */
data class OrderField<E : Any>(
    val field: String,
    val direction: OrderDirection,
    /**
     * Native distance ordering (pgvector): when non-null, the driver renders
     * `field <operator> ?` and binds [DistanceOrder.operand] as a parameter
     * instead of plain `field direction`. Null for ordinary column ordering.
     */
    val distance: DistanceOrder? = null,
)

/**
 * A pgvector distance-ordering term: an operator from a **closed** set (never
 * caller-supplied SQL — cf. `Predicate.Op`) and the query operand bound as a
 * parameter, never inlined.
 */
data class DistanceOrder(val operator: VectorDistanceOperator, val operand: Any)

/** pgvector distance operators. [sql] is the operator token spliced into ORDER BY. */
enum class VectorDistanceOperator(val sql: String) {
    L2("<->"),
    Cosine("<=>"),
    /** pgvector's `<#>` is **negative** inner product (so `.asc()` is most-similar-first). */
    NegInnerProduct("<#>"),
}
