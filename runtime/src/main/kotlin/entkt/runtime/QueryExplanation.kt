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
interface QueryExplanation
