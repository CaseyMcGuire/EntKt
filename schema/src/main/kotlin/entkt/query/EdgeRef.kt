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
 *         val active: Column<Boolean> = ...
 *         val posts: EdgeRef<Post, PostQuery> = EdgeRef("posts") { PostQuery() }
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
 * The [T] type parameter (target entity) isn't used in the body — it's
 * carried purely so the call site reads as `EdgeRef<Post, PostQuery>`,
 * documenting both the related entity and its query type.
 */
class EdgeRef<T, Q : EdgeQuery>(
    val name: String,
    private val newQuery: () -> Q,
) {
    /** Predicate: this row has *some* row across [name]. */
    fun exists(): Predicate = Predicate.HasEdge(name)

    /**
     * Predicate: this row has at least one row across [name] matching
     * the wheres added in [block]. An empty block degenerates to
     * [exists]. Reuses the target's full query DSL — column refs,
     * and/or, and even nested edge predicates work inside.
     */
    fun has(block: Q.() -> Unit): Predicate {
        val inner = newQuery().apply(block).combinedPredicate()
            ?: return Predicate.HasEdge(name)
        return Predicate.HasEdgeWith(name, inner)
    }
}
