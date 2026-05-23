package entkt.query

/**
 * Narrow receiver type for [EdgeRef.has] blocks.
 *
 * The generated query class for an entity (`PostQuery`, `UserQuery`,
 * etc.) carries the full DSL — `where`, `orderBy`, `limit`, `offset`,
 * traversal `queryX()`, eager `with{Edge}`, and terminal operations
 * like `allOrThrow`. Most of those are meaningless inside a
 * `Edge.has { ... }` block: the runtime lowers `has` into an `EXISTS`
 * subquery that takes only an inner predicate, so `orderBy` / `limit`
 * / `offset` were silently dropped, and terminal calls would hit
 * `NoopDriver` and throw.
 *
 * `EdgePredicateScope<E>` exposes only the operations that have
 * meaning inside an edge-predicate block: `where(Predicate<E>)`.
 * Generated query classes implement this interface alongside
 * [EdgeQuery]; `EdgeRef.has` declares its lambda with
 * `EdgePredicateScope<Target>` as the receiver, so wider DSL members
 * on the concrete query are not in scope inside the block and
 * misuse (`has { orderBy(...) }`, `has { allOrThrow() }`) becomes a
 * compile error.
 *
 * Returns `EdgePredicateScope<E>` so callers who want to chain
 * `where(...).where(...)` can do so without leaking the concrete
 * query type out of the block; the block itself returns Unit, so
 * the return value is usually unused.
 */
interface EdgePredicateScope<E : Any> {
    /** AND another predicate into this query's where-list. */
    fun where(predicate: Predicate<E>): EdgePredicateScope<E>
}
