package entkt.runtime.query

/**
 * Edge-specific configuration handle returned by every generated
 * `load<Edge> { ... }` edge-load selection method.
 *
 * Ignoring the handle keeps the strict default: a LOAD-denied selected
 * target fails the whole root terminal with
 * `EntPrivacyDeniedException(SelectedEdgePath(steps), ...)`. Calling
 * [filterVisible] opts that exact edge into retaining only visible
 * targets — a denied to-one target produces `EdgeState.Loaded(null)`
 * and denied to-many targets are omitted from the non-null loaded
 * list, without scanning beyond the selected per-parent window to
 * replace them.
 *
 * The handle scopes the modifier to the edge whose `load` call
 * produced it, so it cannot accidentally change root-query privacy or
 * a sibling edge's posture. The setting is not inherited by nested
 * edge loads — each nested edge opts in independently — and it
 * suppresses only a returned LOAD-deny decision: edge-load query
 * rejection and ordinary privacy, driver, or materialization
 * exceptions remain terminal failures.
 *
 * [filterVisible] returns the parent query so a fluent chain may
 * continue.
 */
interface EdgeLoad<out ParentQuery> {
    fun filterVisible(): ParentQuery
}
