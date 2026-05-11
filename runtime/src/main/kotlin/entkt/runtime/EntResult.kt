package entkt.runtime

/**
 * Structured outcome of a generated mutation. Returned by `saveOrError()`
 * methods on generated builders for callers that want to branch on
 * outcome rather than catch exceptions.
 *
 * The minimal hierarchy currently covers what the id-based update root
 * feature emits: [EntError.NotFound] and [EntError.NoChanges]. The
 * broader result-variants RFC will add `Err` variants for privacy
 * denial, validation failure, constraint violations, transaction and
 * driver errors, etc. Until then those failures still propagate as
 * their existing exception types from `saveOrError()`.
 */
sealed interface EntResult<out T> {
    data class Ok<T>(val value: T) : EntResult<T>
    data class Err(val error: EntError) : EntResult<Nothing>
}
