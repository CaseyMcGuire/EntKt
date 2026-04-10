package entkt.query

/**
 * Contract every generated `XQuery` class implements so that
 * [EdgeRef.has] can fold a query's accumulated wheres into a single
 * predicate without depending on the generated class itself.
 *
 * The codegen wires `combinedPredicate()` to AND the query's
 * `predicates` list together (or return null if the list is empty).
 */
interface EdgeQuery {
    fun combinedPredicate(): Predicate?
}