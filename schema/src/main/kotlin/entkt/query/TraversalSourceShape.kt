package entkt.query

/**
 * The source query's post-interceptor shape, embedded in a shaped
 * traversal bridge ([Predicate.HasEdgeFromShape] /
 * [Predicate.HasM2MEdgeFromShape]) by generated `queryX()` methods.
 *
 * Traversal follows the source query as written: the driver lowers
 * this shape into a source-id subquery (or an equivalent form) so
 * source `where`, `orderBy`, `limit`, and `offset` select exactly the
 * source rows that are traversed. A driver must not silently fall
 * back to a predicate-only lowering that ignores [orderBy] / [limit]
 * / [offset].
 *
 * [selectedColumn] is the column on [table] the traversal joins
 * through — the source's id column when the target row carries the
 * FK (e.g. `users.id` for `User.queryPosts()`), or the source's FK
 * column when traversing child-to-parent (e.g. `posts.author_id` for
 * `Post.queryAuthor()`). For many-to-many traversal it is the source
 * column the junction table references.
 *
 * The phantom type parameter [E] is the *source* entity scope:
 * [predicates] and [orderBy] are typed against the source, not the
 * candidate entity the enclosing bridge predicate filters.
 *
 * [flags] carries the source step's public [QueryFlag]s for
 * diagnostics and forward compatibility; flags are consumed by
 * interceptors before the driver runs, so drivers ignore them.
 */
data class TraversalSourceShape<E : Any>(
    val table: String,
    val selectedColumn: String,
    val predicates: List<Predicate<E>>,
    val orderBy: List<OrderField<E>>,
    val limit: Int?,
    val offset: Int?,
    val flags: Set<QueryFlag>,
)
