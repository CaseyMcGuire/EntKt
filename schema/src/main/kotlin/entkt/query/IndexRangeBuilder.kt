package entkt.query

/**
 * Accumulates a range constraint for one indexed column inside an
 * `indexes...{ }` range block, e.g.
 *
 * ```kotlin
 * client.posts.indexes
 *     .authorId(authorId)
 *     .createdAt { gte(since); lt(until) }
 *     .query()
 * ```
 *
 * Generated index-helper stages construct one builder per range block,
 * run the caller's block against it, and fold [build]'s predicates into
 * the query (seeded before the caller's own `query { ... }` DSL) so the
 * range narrows the indexed seek the same way an explicit
 * `where(col gte since); where(col lt until)` pair would.
 *
 * A range block must add **at least one** bound and may include **at most
 * one** lower bound (`gt` or `gte`) and **at most one** upper bound (`lt`
 * or `lte`). An empty block or a duplicate same-side bound throws
 * [IllegalArgumentException] with a clear message rather than silently
 * producing an ambiguous query.
 */
class IndexRangeBuilder<E : Any, T : Comparable<T>>(
    private val column: ComparableColumn<E, T>,
) {
    private var lower: Predicate<E>? = null
    private var upper: Predicate<E>? = null

    /** Lower bound: column value strictly greater than [value]. */
    fun gt(value: T) = setLower(column.gt(value))

    /** Lower bound: column value greater than or equal to [value]. */
    fun gte(value: T) = setLower(column.gte(value))

    /** Upper bound: column value strictly less than [value]. */
    fun lt(value: T) = setUpper(column.lt(value))

    /** Upper bound: column value less than or equal to [value]. */
    fun lte(value: T) = setUpper(column.lte(value))

    private fun setLower(predicate: Predicate<E>) {
        require(lower == null) {
            "Index range block on '${column.name}' has more than one lower bound; " +
                "use at most one of gt/gte"
        }
        lower = predicate
    }

    private fun setUpper(predicate: Predicate<E>) {
        require(upper == null) {
            "Index range block on '${column.name}' has more than one upper bound; " +
                "use at most one of lt/lte"
        }
        upper = predicate
    }

    /**
     * The accumulated bound predicates, lower bound first. Throws when the
     * block added no bound at all.
     */
    fun build(): List<Predicate<E>> {
        require(lower != null || upper != null) {
            "Index range block on '${column.name}' must add at least one bound " +
                "(gt/gte/lt/lte)"
        }
        return listOfNotNull(lower, upper)
    }
}
