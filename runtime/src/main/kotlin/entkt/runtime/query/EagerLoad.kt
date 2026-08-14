package entkt.runtime.query

/**
 * Edge-specific configuration handle returned by every generated
 * `with<Edge> { ... }` eager-load method.
 *
 * Ignoring the handle keeps the strict default: a LOAD-denied eager
 * target fails the whole root terminal with
 * `EntPrivacyDeniedException(EagerEdge(path), ...)`. Calling
 * [filterVisible] opts that exact edge into retaining only visible
 * targets — a denied to-one target produces `EdgeState.Loaded(null)`
 * and denied to-many targets are omitted from the non-null loaded
 * list, without scanning beyond the selected eager-load window to
 * replace them.
 *
 * The handle scopes the modifier to the edge whose `with` call
 * produced it, so it cannot accidentally change root-query privacy or
 * whichever eager edge happened to be configured most recently. The
 * setting is not inherited by nested eager loads — each nested edge
 * opts in independently — and it suppresses only a returned LOAD-deny
 * decision: eager-query rejection and ordinary privacy, driver, or
 * materialization exceptions remain terminal failures.
 *
 * [filterVisible] returns the parent query so a fluent chain may
 * continue.
 */
interface EagerLoad<out ParentQuery> {
    fun filterVisible(): ParentQuery
}
