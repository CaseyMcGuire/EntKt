package entkt.query

/**
 * Narrow receiver type for [EdgeRef.has] blocks.
 *
 * Generated configuration scopes (`PostQueryScope`, `UserQueryScope`,
 * etc.) also offer ordering, bounds, and edge selections. Those have
 * no meaning inside `Edge.has { ... }`: the runtime lowers `has` into
 * an `EXISTS` subquery that takes only an inner predicate.
 *
 * `EdgePredicateScope<E>` exposes only the operations that have
 * meaning inside an edge-predicate block: `where(Predicate<E>)`.
 * Generated configuration scopes implement this interface alongside
 * [EdgeQuery]; `EdgeRef.has` declares its lambda with
 * `EdgePredicateScope<Target>` as the receiver, so wider DSL members
 * on the concrete scope are not in scope inside the block and
 * misuse (`has { orderBy(...) }`, `has { allOrThrow() }`) becomes a
 * compile error.
 *
 * Returns `EdgePredicateScope<E>` so callers who want to chain
 * `where(...).where(...)` can do so without leaking the concrete
 * configuration type out of the block; the block itself returns Unit, so
 * the return value is usually unused.
 */
interface EdgePredicateScope<E : Any> {
    /** AND another predicate into this query's where-list. */
    fun where(predicate: Predicate<E>): EdgePredicateScope<E>
}
