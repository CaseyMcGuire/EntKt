@file:OptIn(EntktInternal::class)

package entkt.query

/**
 * A typed reference to an edge declared on a generated entity. Lets
 * callers express edge predicates that the runtime later lowers into
 * EXISTS subqueries or joins.
 *
 * Emitted on the entity companion alongside column refs:
 *
 * ```
 * data class User(...) {
 *     companion object {
 *         val active: Column<User, Boolean> = ...
 *         val posts: EdgeRef<User, Post, PostQuery> = EdgeRef("posts") { PostQuery() }
 *     }
 * }
 * ```
 *
 * Usage:
 *
 * ```
 * // "users who have any post"
 * client.users.query { where(User.posts.exists()) }
 *
 * // "users with at least one published post"
 * client.users.query {
 *     where(User.posts.has { where(Post.published eq true) })
 * }
 * ```
 *
 * Type parameters:
 *  - [Source]: the source entity the predicate is scoped to. `has` /
 *    `exists` return `Predicate<Source>`, matching the source query's
 *    expected predicate scope.
 *  - [Target]: the target entity reached across [name]. Threaded into
 *    `Predicate.HasEdgeWith<Source, Target>` so the generated walker can
 *    recover the target type from the edge name (via the edge-name-
 *    validated unchecked cast described in
 *    `docs/possible-features/query/phantom-typed-query-scopes.md`
 *    §"Edge-Predicate Walker").
 *  - [Q]: the target's generated query type. Constrained to both
 *    `EdgeQuery<Target>` (so [has] can fold the block's wheres into
 *    a `Predicate<Target>` via `combinedPredicate()`) AND
 *    `EdgePredicateScope<Target>` (the narrow lambda receiver type
 *    that hides the target's wider DSL from inside `has { ... }`).
 *
 * The primary constructor is marked `@EntktInternal` so application
 * code cannot fabricate an `EdgeRef` with arbitrary source/target type
 * arguments — only generated entity-companion code (which carries
 * `@file:OptIn(EntktInternal::class)`) instantiates `EdgeRef`. See
 * §"Constructor Visibility" for the cross-module rationale.
 */
class EdgeRef<Source : Any, Target : Any, Q> @EntktInternal constructor(
    val name: String,
    private val newQuery: () -> Q,
) where Q : EdgeQuery<Target>, Q : EdgePredicateScope<Target> {
    /** Predicate: this row has *some* row across [name]. */
    fun exists(): Predicate<Source> = Predicate.HasEdge(name)

    /**
     * Predicate: this row has at least one row across [name] matching
     * the wheres added in [block]. An empty block degenerates to
     * [exists].
     *
     * The block receiver is the narrow [EdgePredicateScope] rather
     * than the concrete query type, so only `where(Predicate<Target>)`
     * is in scope. The target's wider DSL (`orderBy`, `limit`,
     * `offset`, traversal `queryX()`, eager loaders, terminal
     * operations) is unreachable inside the block — those don't
     * lower into the runtime's `HasEdgeWith` EXISTS subquery and
     * previously would have either silently been dropped or thrown
     * via `NoopDriver` at terminal-call time. Nested edge predicates
     * still work because they go through `Edge.has { ... }` /
     * `Edge.exists()`, which return `Predicate<E>` and pass the
     * receiver check.
     */
    fun has(block: EdgePredicateScope<Target>.() -> Unit): Predicate<Source> {
        val q = newQuery()
        q.block()
        val inner = q.combinedPredicate() ?: return Predicate.HasEdge(name)
        return Predicate.HasEdgeWith<Source, Target>(name, inner)
    }
}
