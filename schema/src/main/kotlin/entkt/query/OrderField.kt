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
)
