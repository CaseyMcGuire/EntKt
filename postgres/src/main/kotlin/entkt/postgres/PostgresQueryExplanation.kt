package entkt.postgres

import entkt.runtime.QueryExplanation

/**
 * Explanation returned by [PostgresDriver.explainQuery]. Exposes
 * the parameterized SQL string and the ordered list of bind values
 * the driver would use to execute the query.
 */
data class PostgresQueryExplanation(
    val sql: String,
    val args: List<Any?>,
) : QueryExplanation
