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
 *  - [Q]: the target's generated query type, constrained to
 *    `EdgeQuery<Target>` so [has] can fold its inner block's wheres into
 *    a `Predicate<Target>` via `combinedPredicate()`.
 *
 * The primary constructor is marked `@EntktInternal` so application
 * code cannot fabricate an `EdgeRef` with arbitrary source/target type
 * arguments — only generated entity-companion code (which carries
 * `@file:OptIn(EntktInternal::class)`) instantiates `EdgeRef`. See
 * §"Constructor Visibility" for the cross-module rationale.
 */
class EdgeRef<Source : Any, Target : Any, Q : EdgeQuery<Target>> @EntktInternal constructor(
    val name: String,
    private val newQuery: () -> Q,
) {
    /** Predicate: this row has *some* row across [name]. */
    fun exists(): Predicate<Source> = Predicate.HasEdge(name)

    /**
     * Predicate: this row has at least one row across [name] matching
     * the wheres added in [block]. An empty block degenerates to
     * [exists]. Reuses the target's full query DSL — column refs,
     * and/or, and even nested edge predicates work inside.
     */
    fun has(block: Q.() -> Unit): Predicate<Source> {
        val inner = newQuery().apply(block).combinedPredicate()
            ?: return Predicate.HasEdge(name)
        return Predicate.HasEdgeWith<Source, Target>(name, inner)
    }
}
