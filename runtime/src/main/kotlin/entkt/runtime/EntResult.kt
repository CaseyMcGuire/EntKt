package entkt.runtime

/**
 * Structured outcome of a generated mutation. Returned by `saveOrError()`
 * methods on generated builders for callers that want to branch on
 * outcome rather than catch exceptions. Wraps every recognized
 * failure into an [EntError] variant (`NotFound`, `NoChanges`,
 * `PrivacyDenied`, `ValidationFailed`). Other exceptions propagate.
 */
sealed interface EntResult<out T> {
    data class Ok<T>(val value: T) : EntResult<T>
    data class Err(val error: EntError) : EntResult<Nothing>
}
