package entkt.postgres

import entkt.runtime.query.QueryExplanation

/**
 * Explanation returned by [PostgresDriver.explainQuery]. Exposes
 * the parameterized SQL string and the ordered logical argument
 * values associated with each `?` placeholder. These are the
 * pre-coercion values from the predicates, not the JDBC-specific
 * objects passed to `PreparedStatement.setXxx` at execution time.
 */
data class PostgresQueryExplanation(
    val sql: String,
    val args: List<Any?>,
) : QueryExplanation {
    override fun describe(): String = if (args.isEmpty()) sql else "$sql  args: $args"
}
