package entkt.query

/**
 * A predicate in a query's WHERE clause. Compound predicates are built
 * from leaves with [and]/[or]:
 *
 * ```
 * (User.active eq true) and (User.age gte 18)
 * ```
 */
sealed class Predicate {
    infix fun and(other: Predicate): Predicate = And(this, other)
    infix fun or(other: Predicate): Predicate = Or(this, other)

    /** A single field/op/value comparison. */
    data class Leaf(
        val field: String,
        val op: Op,
        val value: Any?,
    ) : Predicate()

    /** Conjunction of two predicates. */
    data class And(
        val left: Predicate,
        val right: Predicate,
    ) : Predicate()

    /** Disjunction of two predicates. */
    data class Or(
        val left: Predicate,
        val right: Predicate,
    ) : Predicate()

    /**
     * "Has any related row across [edge]." For required edges this is
     * trivially true; for optional edges it filters out rows whose FK is
     * null (or whose join-table row is missing).
     */
    data class HasEdge(val edge: String) : Predicate()

    /**
     * "Has at least one related row across [edge] that matches [inner]."
     * Lowered by the runtime into an EXISTS subquery (or join, depending
     * on the driver). [inner] is the AND of every where added to the
     * inner block.
     */
    data class HasEdgeWith(val edge: String, val inner: Predicate) : Predicate()
}

enum class Op {
    EQ,
    NEQ,
    GT,
    GTE,
    LT,
    LTE,
    IN,
    NOT_IN,
    IS_NULL,
    IS_NOT_NULL,
    CONTAINS,
    HAS_PREFIX,
    HAS_SUFFIX,
}