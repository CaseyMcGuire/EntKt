package entkt.runtime

/**
 * Structured outcome of a generated operation. Returned by `*OrError()`
 * methods on generated repos, queries, and builders for callers that
 * want to branch on outcome rather than catch exceptions. Wraps every
 * recognized failure into an [EntError] variant; programming-error
 * exceptions ([TransactionRequiredException],
 * [UnsupportedDriverCapabilityException], `IllegalStateException` from
 * builder misuse) deliberately propagate as exceptions instead of
 * collapsing to [Err].
 */
sealed interface EntResult<out T> {
    data class Ok<T>(val value: T) : EntResult<T>
    data class Err(val error: EntError) : EntResult<Nothing>
}

/**
 * Unwrap [Ok] or throw the matching [EntException]. Use this when the
 * caller is happy to fall back to throwing semantics for any
 * unhandled error variant, e.g. in service code that handles a few
 * `Err` cases explicitly but treats everything else as a bug.
 */
public fun <T> EntResult<T>.getOrThrow(): T = when (this) {
    is EntResult.Ok<T> -> value
    is EntResult.Err -> throw error.toException()
}

/**
 * Collapse [EntResult] to `T?` narrowly: return the value on [Ok],
 * `null` only for [EntError.NotFound] (the "expected absence" case),
 * and throw the matching [EntException] for every other [Err]
 * variant (privacy denial, validation failure, constraint violation,
 * conflict, driver failure, no changes, overfetch cap).
 *
 * Mirrors the contract for direct-call `*OrNull` APIs — `null` means
 * absence, not arbitrary failure.
 */
public fun <T> EntResult<T>.getOrNullForAbsenceOnly(): T? = when (this) {
    is EntResult.Ok<T> -> value
    is EntResult.Err -> when (val e = error) {
        is EntError.NotFound -> null
        else -> throw e.toException()
    }
}

/**
 * Functor `map`. Returns [Err] unchanged. Useful for projecting an
 * [Ok] value to a different shape without breaking the result chain.
 */
public inline fun <T, R> EntResult<T>.map(transform: (T) -> R): EntResult<R> =
    when (this) {
        is EntResult.Ok<T> -> EntResult.Ok(transform(value))
        is EntResult.Err -> this
    }

/**
 * Monadic bind. Returns [Err] unchanged. Use for chaining operations
 * that each return their own [EntResult]; the first [Err] short-circuits
 * the chain.
 *
 * Inside `withTransactionOrError { tx -> ... }`, prefer `.bind()` —
 * `flatMap` doesn't trigger transaction rollback, while `.bind()` does.
 */
public inline fun <T, R> EntResult<T>.flatMap(
    transform: (T) -> EntResult<R>,
): EntResult<R> = when (this) {
    is EntResult.Ok<T> -> transform(value)
    is EntResult.Err -> this
}
