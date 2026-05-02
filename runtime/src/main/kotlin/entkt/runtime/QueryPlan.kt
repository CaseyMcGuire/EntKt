package entkt.runtime

/**
 * A tree of [QueryExplanation]s describing every query a query
 * builder would execute — the root query plus any eager-loaded
 * edge subqueries (which may themselves have nested eager loads).
 *
 * Returned by the generated `explain()` method on query builders.
 */
data class QueryPlan(
    val root: QueryExplanation,
    val eagerQueries: Map<String, QueryPlan> = emptyMap(),
)
