package entkt.runtime.result

import entkt.query.EntktInternal

/**
 * The exhaustive result of a singular read or one entry in a collection read.
 *
 * A singular entity lookup declares `ReadResult<Entity?>`, where
 * `Success(null)` is authoritative absence. Collection terminals return
 * [ReadCollectionResult], which retains a [ReadResult] for each selected root.
 *
 * [Failed] means the read produced neither a value nor an
 * authoritative absence. LOAD denial is
 * `Failed(EntPrivacyDeniedException(...))`; other failures store the
 * original or framework-created typed exception. Projections such as
 * [getOrThrow] and [visibleOrNull] operate only on an already-produced
 * result — they never perform I/O or another driver call.
 */
sealed interface ReadResult<out T> {
    /** The read completed reliably; [value] is the declared payload. */
    data class Success<T>(val value: T) : ReadResult<T>

    /**
     * The read did not return a value or an authoritative absence.
     * [exception] is the ordinary exception EntKt observed or a
     * framework-created typed exception (e.g.
     * [EntPrivacyDeniedException], [EntQueryRejectedException]).
     *
     * Only EntKt constructs failures — the constructor and `copy()`
     * are internal so application code cannot fabricate one or alter
     * a real failure's exception. Generated code uses the
     * [failedForInternalUse] escape hatch.
     */
    @ConsistentCopyVisibility
    data class Failed internal constructor(
        val exception: Exception,
    ) : ReadResult<Nothing>

    companion object {
        /**
         * Constructs [Failed] for generated code, which compiles in
         * the application module and cannot reach the internal
         * constructor. Guarded by the error-level [EntktInternal]
         * opt-in: an API guardrail, not a security boundary. This is
         * also the initial test-fixture path for application tests
         * that exercise exhaustive `Failed` branches; such tests
         * accept responsibility for constructing a state EntKt could
         * actually produce.
         */
        @EntktInternal
        fun failedForInternalUse(
            exception: Exception,
        ): ReadResult<Nothing> = Failed(exception)
    }

    /**
     * Return the successful value — preserving its declared nullability,
     * so authoritative absence remains `null` — or throw the stored
     * exception directly. Framework-created exceptions retain their
     * structured diagnostics as payloads; there is no wrapper.
     */
    fun getOrThrow(): T = when (this) {
        is Success<T> -> value
        is Failed -> throw exception
    }
}

/**
 * Map *root* LOAD denial to authoritative absence, leaving every other
 * state unchanged:
 *
 * ```text
 * Success(value)                                -> Success(value)
 * Success(null)                                 -> Success(null)
 * Failed(EntPrivacyDeniedException(Root, ...))  -> Success(null)
 * Failed(EntPrivacyDeniedException(SelectedEdgePath)) -> unchanged
 * Failed(otherException)                        -> unchanged
 * ```
 *
 * A pure transformation: no I/O, no LOAD re-evaluation, no scanning
 * beyond the selected SQL window. It answers only whether the selected
 * root is visible — a denied selected-edge target does not become root
 * absence, so selecting an edge cannot change root presence into
 * apparent absence. The denial details are intentionally discarded;
 * callers that need them must inspect the original [ReadResult.Failed].
 *
 * Defined for nullable singular results only. Collection reads use
 * [deniedAsNull] to preserve denied roots as null entries without dropping rows.
 */
fun <T : Any> ReadResult<T?>.visibleOrNull(): ReadResult<T?> = when (this) {
    is ReadResult.Success -> this
    is ReadResult.Failed -> {
        val e = exception
        if (e is EntPrivacyDeniedException && e.origin is LoadDenialOrigin.Root) {
            ReadResult.Success(null)
        } else {
            this
        }
    }
}
