package entkt.runtime.query

/**
 * The loaded state of one eager-loadable edge on a returned generated
 * entity.
 *
 * `Unloaded` means the query did not request the edge — no `with{Edge}`
 * clause was configured, so nothing is known about the related rows.
 * `Loaded(value)` means the query requested the edge and [value] is the
 * result: a non-null `List` for to-many and many-to-many edges
 * (`Loaded(emptyList())` when no rows matched), or a nullable target for
 * `hasOne` / `belongsTo` edges (`Loaded(null)` when the edge was
 * requested but no target was returned — eager-load predicates, read
 * interceptors, `limit(0)`, or a positive offset can all exclude the
 * target even under a required foreign key).
 *
 * Generated `Edges` properties default to [Unloaded]; eager loading
 * assigns [Loaded] for every returned parent whose edge was requested.
 * The wrapper makes loaded-empty and unloaded distinct without nested
 * nullable checks — see [loadedOrNull] for the state-preserving
 * accessor, [valueOrNull] for the deliberately lossy one, and
 * [requireLoaded] for callers that treat an unloaded edge as a
 * programming error.
 */
sealed interface EdgeState<out T> {
    data object Unloaded : EdgeState<Nothing>
    data class Loaded<out T>(val value: T) : EdgeState<T>
}

/**
 * True when this edge was eager-loaded — the state is [EdgeState.Loaded],
 * whatever its value (including a loaded `null` or an empty list).
 */
val EdgeState<*>.isLoaded: Boolean
    get() = this is EdgeState.Loaded

/**
 * The [EdgeState.Loaded] wrapper, or `null` when the edge was not
 * loaded. Returns the wrapper rather than its value so a loaded to-one
 * edge with no target — `Loaded(null)` — stays distinguishable from
 * [EdgeState.Unloaded]. Use [valueOrNull] when collapsing the two is
 * intentional.
 */
fun <T> EdgeState<T>.loadedOrNull(): EdgeState.Loaded<T>? = when (this) {
    is EdgeState.Loaded -> this
    EdgeState.Unloaded -> null
}

/**
 * The loaded value, or `null` when the edge was not loaded. This is
 * the deliberately lossy convenience: for a to-one edge it collapses
 * "not loaded" and "loaded, no related row" into one `null`. Callers
 * that need that distinction use [loadedOrNull] instead.
 */
fun <T> EdgeState<T>.valueOrNull(): T? = when (this) {
    is EdgeState.Loaded -> value
    EdgeState.Unloaded -> null
}

/**
 * The loaded value — including a loaded `null` for a to-one edge with
 * no returned target. Throws [EdgeNotLoadedException] when the edge was
 * not eager-loaded; requiring an edge the query never requested is a
 * programming error, not an operational query failure.
 */
fun <T> EdgeState<T>.requireLoaded(): T = when (this) {
    is EdgeState.Loaded -> value
    EdgeState.Unloaded -> throw EdgeNotLoadedException()
}

/**
 * Thrown by [requireLoaded] when the edge was not eager-loaded. A
 * caller reaching this forgot the `with{Edge}` clause on the query —
 * a programming error, so this deliberately propagates as an exception
 * and has no `EntError` / `EntResult` representation. It sits outside
 * the `EntException` hierarchy, whose `Ent` prefix this project
 * reserves for the structured error family.
 */
class EdgeNotLoadedException : IllegalStateException(
    "Edge was not loaded; eager-load it before calling requireLoaded()",
)
