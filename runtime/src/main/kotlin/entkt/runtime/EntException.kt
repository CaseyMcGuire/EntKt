package entkt.runtime

/**
 * Base class for exceptions thrown by generated save/throwing code
 * paths. Each subclass wraps a specific [EntError] variant. Callers
 * that want structured failure handling can pattern-match on
 * `exception.error`; callers that just want to bubble up can catch
 * [EntException].
 *
 * Throwable subclasses can't be generic on the JVM, so each subclass
 * exposes a narrowed accessor for its variant (e.g.
 * [EntNotFoundException.notFound]) instead of overriding [error].
 *
 * The optional [cause] parameter exists for [EntDriverException],
 * which forwards the underlying driver exception so callers can
 * inspect the original Throwable. All other subclasses default to
 * `cause = null` since their failure source is the framework, not an
 * underlying exception.
 */
abstract class EntException(
    val error: EntError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)

class EntNotFoundException(val notFound: EntError.NotFound) : EntException(notFound)

class EntNoChangesException(val noChanges: EntError.NoChanges) : EntException(noChanges)

class EntPrivacyDeniedException(
    val privacyDenied: EntError.PrivacyDenied,
) : EntException(privacyDenied)

class EntValidationException(
    val validationFailed: EntError.ValidationFailed,
) : EntException(validationFailed)

class EntConstraintViolationException(
    val constraintViolation: EntError.ConstraintViolation,
) : EntException(constraintViolation)

class EntConflictException(val conflict: EntError.Conflict) : EntException(conflict)

/**
 * Wraps a driver-level failure that wasn't classified as a more
 * specific variant (e.g. [EntError.ConstraintViolation]). The
 * underlying driver exception is forwarded as the JVM exception's
 * `cause`, so standard exception-chain tooling (`getCause()`,
 * `printStackTrace()`) preserves it.
 */
class EntDriverException(
    val driverFailure: EntError.DriverFailure,
) : EntException(driverFailure, cause = driverFailure.cause)

/**
 * Thrown by `firstVisibleOrThrow` / `visibleAllOrThrow` (if those
 * variants exist) and by `EntResult.getOrThrow` when the underlying
 * error is [EntError.OverfetchCapExceeded]. The cap and entity name
 * are preserved on `overfetchCapExceeded.cap` /
 * `overfetchCapExceeded.entity`.
 */
class EntOverfetchCapExceededException(
    val overfetchCapExceeded: EntError.OverfetchCapExceeded,
) : EntException(overfetchCapExceeded)
