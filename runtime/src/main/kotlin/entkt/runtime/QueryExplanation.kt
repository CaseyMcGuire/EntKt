package entkt.runtime

/**
 * Describes the query a driver would execute for a given set of
 * predicates, ordering, limit, and offset. Each driver provides its
 * own implementation — e.g. [PostgresQueryExplanation] exposes the
 * SQL string and bind parameters.
 *
 * Not `sealed` because driver implementations live in separate
 * modules (`postgres`, etc.) that cannot extend a sealed type
 * declared in `runtime`.
 */
interface QueryExplanation {
    /** Human-readable description of the query this driver would execute. */
    fun describe(): String

    companion object {
        /**
         * Non-empty placeholder list used by generated explain code for
         * IN predicates whose real values depend on parent query results.
         * Using a non-empty list ensures the driver renders the column
         * name in the SQL (e.g. `"author_id" IN (?)`) rather than
         * collapsing an empty IN to `FALSE`.
         */
        val EXPLAIN_PLACEHOLDER: List<Any> = listOf("<parent IDs>")
    }
}

/**
 * Returned by the default [Driver.explainQuery] and [Driver.explainCount]
 * implementations for drivers that don't override those methods.
 */
data class UnsupportedQueryExplanation(val driverName: String) : QueryExplanation {
    override fun describe(): String = "$driverName does not support explain"
}
