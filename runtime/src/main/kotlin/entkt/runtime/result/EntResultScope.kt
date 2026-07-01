package entkt.runtime.result

/**
 * Scope marker for the block passed to `EntClient.withTransactionOrError`.
 *
 * Inside the scope, callers compose `EntResult<T>` values using `bind()`:
 * each `bind()` either unwraps an `Ok(value)` or aborts the transaction
 * with the first `Err`. The framework catches the abort signal, rolls
 * back the driver-level transaction, and surfaces
 * `EntResult.Err(error)` to the caller.
 *
 * The scope is intentionally a class (not an `object`) so future
 * extensions can carry per-scope state (per-tx classifier overrides,
 * audit metadata, etc.) without breaking the public extension shape.
 */
/**
 * The constructor is public (rather than internal) only so generated
 * `EntClient.withTransactionOrError` code — which lives in the user's
 * own package and not the runtime module — can construct a scope.
 * Application code should never instantiate this directly; the scope
 * only makes sense inside a `withTransactionOrError` block where the
 * surrounding helper catches `AbortEntResultTransaction`.
 */
public class EntResultScope public constructor() {
    /**
     * Unwrap the receiver's value or abort the surrounding
     * `withTransactionOrError` block. The framework catches the abort
     * signal, rolls back the transaction, and returns
     * `EntResult.Err(error)` to the outer caller.
     *
     * Implemented via [AbortEntResultTransaction] — using a dedicated
     * marker exception instead of a sentinel return lets `bind()` work
     * from nested helper functions inside the scope without needing
     * non-local returns.
     */
    public fun <T> EntResult<T>.bind(): T = when (this) {
        is EntResult.Ok -> value
        is EntResult.Err -> throw AbortEntResultTransaction(error)
    }
}

/**
 * Internal marker exception thrown by [EntResultScope.bind] when the
 * receiver is an `Err`. The generated `withTransactionOrError` block
 * catches this, rolls back the transaction, and converts it back into
 * `EntResult.Err(error)`.
 *
 * Not part of the public API surface — application code should never
 * throw or catch this directly. It exists as a class only because
 * `bind()` needs *some* control-flow mechanism to short-circuit out
 * of nested helper calls, and Kotlin's non-local return doesn't cross
 * function boundaries.
 */
public class AbortEntResultTransaction public constructor(
    public val error: EntError,
) : RuntimeException("withTransactionOrError aborted: ${error.message}")
