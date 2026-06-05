package entkt.postgres.vector

import entkt.query.Column
import entkt.query.DistanceOrder
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.VectorDistanceOperator

/**
 * A pending pgvector distance-ordering term, produced by [cosineDistance] /
 * [l2Distance] / [innerProduct] on a vector column. Call [asc] / [desc] to turn
 * it into an `OrderField` for `query().orderBy(...)`.
 */
class VectorDistance<E : Any> internal constructor(
    private val field: String,
    private val operator: VectorDistanceOperator,
    private val operand: PgVector,
) {
    /** Order by ascending distance — nearest (most similar) first. */
    fun asc(): OrderField<E> = OrderField(field, OrderDirection.ASC, DistanceOrder(operator, operand))

    /** Order by descending distance — farthest first. */
    fun desc(): OrderField<E> = OrderField(field, OrderDirection.DESC, DistanceOrder(operator, operand))
}

/** L2 (Euclidean) distance to [query] — pgvector `<->`. Smaller is more similar. */
fun <E : Any> Column<E, PgVector>.l2Distance(query: PgVector): VectorDistance<E> =
    VectorDistance(name, VectorDistanceOperator.L2, query)

/** Cosine distance to [query] — pgvector `<=>`. Smaller is more similar. */
fun <E : Any> Column<E, PgVector>.cosineDistance(query: PgVector): VectorDistance<E> =
    VectorDistance(name, VectorDistanceOperator.Cosine, query)

/**
 * Inner-product ordering against [query] — pgvector `<#>`, which is the
 * **negative** inner product. Use `.asc()` for most-similar-first (rows with
 * the largest raw inner product come first).
 */
fun <E : Any> Column<E, PgVector>.innerProduct(query: PgVector): VectorDistance<E> =
    VectorDistance(name, VectorDistanceOperator.NegInnerProduct, query)
