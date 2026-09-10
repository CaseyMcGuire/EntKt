package entkt.runtime.query

/** Row-lock intent for this query's root table, never its traversal sources or eager edges. */
enum class QueryLockMode {
    None,
    ForUpdate,
}
