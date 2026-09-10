package entkt.query

/**
 * Contract implemented by generated query configuration scopes so that
 * [EdgeRef.has] can fold a scope's accumulated wheres into a single
 * predicate without depending on the generated class itself.
 *
 * The [E] type parameter is the target entity scope. The runtime folds
 * its predicates with AND, or returns null when none were configured.
 */
interface EdgeQuery<E : Any> {
    fun combinedPredicate(): Predicate<E>?
}
