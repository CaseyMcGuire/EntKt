package entkt.runtime.result

import entkt.runtime.validation.ValidationDecision

/**
 * Operations that can produce an [EntError]. Mirrors [PrivacyOperation]
 * for the four CRUD operations so name-based mapping is 1:1, and
 * adds [QUERY] and [EDGE_MUTATION] for surfaces that have no
 * privacy-operation analog.
 */
enum class EntOperation {
    LOAD,
    QUERY,
    CREATE,
    UPDATE,
    DELETE,
    EDGE_MUTATION,
}

/**
 * A structured failure produced by a generated operation. Each variant
 * carries the entity name (e.g. `"Post"`), the operation that failed,
 * and a human-readable message. Returned through `EntResult.Err` from
 * `*OrError` API variants, and convertible to a matching
 * [EntException] subclass via [toException].
 */
sealed interface EntError {
    val entity: String
    val operation: EntOperation
    val message: String

    /**
     * The targeted owner row does not exist. Emitted by `update(id)`
     * when the internal current-row load returns no row, before any
     * hook, privacy, validation, or driver write runs. Also returned
     * from `byIdOrError` / `firstOrError` on absence.
     */
    data class NotFound(
        override val entity: String,
        override val operation: EntOperation,
        val id: Any? = null,
        override val message: String = "$entity with id=$id not found",
    ) : EntError

    /**
     * The mutation has nothing to write. Emitted by `update(id) {}` for
     * a syntactically empty update — the request shape contains no
     * field or FK changes, so no database write happens. Existence is
     * not checked, to avoid leaking whether the id exists.
     */
    data class NoChanges(
        override val entity: String,
        override val operation: EntOperation,
        val id: Any? = null,
        override val message: String = "$entity update for id=$id has no changes",
    ) : EntError

    /**
     * Privacy denied the operation. Generated `*OrError()` wraps the
     * existing [PrivacyDeniedException] into this variant so callers
     * can branch on outcome rather than catch exceptions.
     */
    data class PrivacyDenied(
        override val entity: String,
        override val operation: EntOperation,
        val reason: String,
        override val message: String = "$operation denied on $entity: $reason",
    ) : EntError

    /**
     * Validation rejected the mutation. Generated `*OrError()` wraps
     * the existing [ValidationException] into this variant, mapping
     * each [ValidationDecision.Invalid] to a [ValidationViolation].
     */
    data class ValidationFailed(
        override val entity: String,
        override val operation: EntOperation,
        val violations: List<ValidationViolation>,
        override val message: String =
            "Validation failed on $entity: ${violations.joinToString("; ") { it.message }}",
    ) : EntError

    /**
     * A database-level constraint (UNIQUE, FOREIGN KEY, CHECK, etc.)
     * rejected the write. Generated `*OrError()` wraps the driver's
     * underlying exception into this variant via [Driver.classifyException].
     *
     * @property constraint the constraint name when the driver can
     *   recover it (e.g. `"users_email_key"` on Postgres); `null` for
     *   drivers that don't expose constraint metadata.
     * @property field the column name when the driver can recover it.
     * @property code a driver-specific code (e.g. PostgreSQL SQLSTATE
     *   `"23505"` for unique violation).
     */
    data class ConstraintViolation(
        override val entity: String,
        override val operation: EntOperation,
        val constraint: String? = null,
        val field: String? = null,
        val code: String? = null,
        override val message: String,
    ) : EntError

    /**
     * Optimistic-concurrency or relationship-serialization conflict.
     * Not reachable from V1 generated code — reserved for a future
     * optimistic-locking support that introduces versioned writes. The
     * type is defined now so the [EntResult.Err] surface is stable.
     */
    data class Conflict(
        override val entity: String,
        override val operation: EntOperation,
        val code: String? = null,
        override val message: String,
    ) : EntError

    /**
     * Anything else from the driver — connection-lost, SQL syntax
     * error, type-conversion failure, etc. Fall-through for the
     * `classifyDriverError` helper when no more specific variant
     * matches. Preserves the underlying [cause] so callers can
     * inspect it (or re-throw via `toException()`).
     */
    data class DriverFailure(
        override val entity: String,
        override val operation: EntOperation,
        val cause: Throwable,
        override val message: String = cause.message ?: "driver failure",
    ) : EntError

    /**
     * `firstVisibleOrNull` / `visibleAll` / `visibleAllOrError`
     * exhausted the configured `EntClientConfig.visibleOverfetchLimit`
     * without finding (or fully populating) the visible result set.
     *
     * - `firstVisibleOrNull()` returns `null` on this case (it doesn't
     *   surface the cap).
     * - `visibleAll()` returns the partial filtered list silently.
     * - `visibleAllOrError()` returns `Err(OverfetchCapExceeded)` so
     *   the caller can distinguish "fully scanned and survived" from
     *   "scanned to the limit, may have missed visible rows".
     *
     * The cap is the only field — there's no underlying driver
     * exception. The error is procedural ("the in-process privacy
     * filter ran out of rope"), not a database failure.
     */
    data class OverfetchCapExceeded(
        override val entity: String,
        override val operation: EntOperation,
        val cap: Int,
        override val message: String = "Visible-only read exhausted overfetch cap " +
            "(visibleOverfetchLimit=$cap) before producing a complete result; " +
            "raise the cap on EntClientConfig or narrow the predicate",
    ) : EntError

    /**
     * The mutation's database write committed (or, inside a still-open
     * transaction, is staged to commit), but the post-write LOAD
     * privacy check on the returned entity denied the viewer. The
     * caller cannot read what they just wrote.
     *
     * This is **distinct from [PrivacyDenied]**: a `PrivacyDenied(CREATE)`
     * means the write was rejected before it happened, but a
     * `WriteSucceededLoadDenied(CREATE)` means the write happened and
     * the caller just can't see the result. Without the separate
     * variant, both would land as `PrivacyDenied` and callers treating
     * `Err` as "operation didn't happen" would be wrong for the second
     * case.
     *
     * Surfaced by `create { ... }.saveOrError()` and
     * `update(id) { ... }.saveOrError()` only; the DELETE path has no
     * post-write LOAD check.
     *
     * The [id] is the newly-written entity's id (always populated by
     * codegen — the wrap happens at a scope where the hydrated entity
     * is in scope). Useful for callers who want to schedule
     * compensating work or audit the write they can't see.
     *
     * **Transaction semantics:** the helper does not roll back; if
     * the caller was inside a plain `withTransaction { ... }` the row
     * commits even though `Err` was returned. Inside
     * `withTransactionOrError { ... }.bind()` the abort rolls back as
     * usual. Same caveat as `createManyOrError` per-row Err.
     */
    data class WriteSucceededLoadDenied(
        override val entity: String,
        override val operation: EntOperation,
        val id: Any?,
        val reason: String,
        override val message: String =
            "$entity write succeeded but post-write LOAD denied: $reason",
    ) : EntError

    /**
     * A read-path interceptor called `scope.reject(...)` to refuse
     * the query before driver execution. The [interceptor] field
     * carries the rejecting interceptor's stable registration name
     * (or `"framework:<id>"` for framework-owned interceptors) so
     * callers and tests can branch on which interceptor refused.
     *
     * Surfaced through `*OrError` reads as `Err(QueryRejected)`
     * and through `*OrThrow` / non-result `visible*` reads as
     * [EntQueryRejectedException]. See the Read-Path Interceptors
     * contract for the full per-API outcome mapping.
     */
    data class QueryRejected(
        override val entity: String,
        override val operation: EntOperation,
        val reason: String,
        val code: String? = null,
        val interceptor: String,
        override val message: String = reason,
    ) : EntError

    /**
     * Convert this error to the matching [EntException] subclass.
     * Used by [EntResult.getOrThrow] and example handlers;
     * also useful for code that receives an `EntResult` from a
     * boundary and wants to fall back to throwing semantics for
     * unhandled variants.
     */
    fun toException(): EntException = when (this) {
        is NotFound -> EntNotFoundException(this)
        is NoChanges -> EntNoChangesException(this)
        is PrivacyDenied -> EntPrivacyDeniedException(this)
        is ValidationFailed -> EntValidationException(this)
        is ConstraintViolation -> EntConstraintViolationException(this)
        is Conflict -> EntConflictException(this)
        is DriverFailure -> EntDriverException(this)
        is OverfetchCapExceeded -> EntOverfetchCapExceededException(this)
        is WriteSucceededLoadDenied -> EntWriteSucceededLoadDeniedException(this)
        is QueryRejected -> EntQueryRejectedException(this)
    }
}

/**
 * A single validation violation surfaced through
 * [EntError.ValidationFailed]. The canonical wire-level shape that
 * `*OrError` consumers branch on; the legacy [ValidationDecision.Invalid]
 * type stays in place for the validation-rule DSL side. Use
 * [ValidationDecision.Invalid.toValidationViolation] to convert between
 * them.
 */
public data class ValidationViolation(
    val message: String,
    val field: String? = null,
    val code: String? = null,
)

/**
 * Project a [ValidationDecision.Invalid] (the validation-rule DSL's
 * native shape) onto the [ValidationViolation] (the `EntError`
 * consumer shape). The two types carry the same three fields; the
 * split keeps the rule-side and the result-side surfaces
 * independently versionable.
 */
public fun ValidationDecision.Invalid.toValidationViolation(): ValidationViolation =
    ValidationViolation(message = message, field = field, code = code)
