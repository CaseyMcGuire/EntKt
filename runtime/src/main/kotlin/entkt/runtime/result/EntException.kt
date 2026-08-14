package entkt.runtime.result

/**
 * Base class for framework-created exceptions. Each typed subclass
 * carries its structured payload as direct properties — the supported
 * inspection surface. Callers do not parse exception messages, and no
 * subclass wraps a universal error payload.
 *
 * Framework-created exceptions retain ordinary JVM stack traces. This
 * is an intentional initial cost: the getOrThrow projections throw the
 * stored exception directly, and suppressing stack capture would make
 * privacy, validation, and other typed failures harder to diagnose.
 */
abstract class EntException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Marker for framework-classified privacy denials. Implemented by both
 * read and mutation denial exceptions so application boundaries can
 * apply one non-disclosure policy without erasing their distinct
 * payloads or mutation write-state information.
 *
 * This means a privacy rule returned a denial decision. An exception
 * thrown by privacy-rule application code is an unexpected operational
 * failure and does not implement this interface.
 */
sealed interface EntPrivacyFailure

/**
 * Base of every mutation failure stored in [MutationResult.Failed].
 * [writeState] records what EntKt knows about the mutation's database
 * effect when the failure was produced, so the raw result and the
 * [getOrThrow] projection expose the same retry-safety information.
 *
 * Sealed because every direct failure kind is owned by the runtime
 * module: application callbacks and third-party drivers preserve
 * custom exceptions as *causes* (via [EntUnexpectedMutationException])
 * rather than adding hierarchy members. This permits exhaustive `when`
 * handling; adding a new direct subtype is consequently a
 * source-breaking algebra change that must be recorded as such.
 *
 * Classification is positional, not type-based: only EntKt's own
 * classification points may place one of these directly in a
 * [MutationResult.Failed]. An exception crossing an application
 * callback boundary is foreign even if application code constructed an
 * EntKt exception type — it is wrapped in
 * [EntUnexpectedMutationException] with EntKt's own state assessment.
 */
sealed class EntMutationException(
    val writeState: MutationWriteState,
    message: String,
    cause: Throwable? = null,
) : EntException(message, cause)

/**
 * The mutation's target row does not exist. Produced by `update(id)`
 * pipelines when the target is absent at load time or vanished before
 * the write. Always [MutationWriteState.NotPersisted] by construction.
 *
 * Idempotent deletes never produce this: `delete(entity)` succeeds
 * when the row is already absent and `deleteById(id)` reports
 * `Success(false)`.
 */
class EntTargetAbsentException(
    val entityType: String,
    val key: EntityKey,
) : EntMutationException(
    MutationWriteState.NotPersisted,
    "$entityType with ${key.field}=${key.value} not found",
)

/**
 * A privacy decision surfaced by a mutation terminal was denied.
 *
 * [operation] identifies the privacy decision that was denied, not
 * necessarily the mutation terminal that initiated it: a pre-write
 * rejection carries the mutation's operation ([EntOperation.CREATE],
 * [EntOperation.UPDATE], [EntOperation.DELETE], or
 * [EntOperation.EDGE_MUTATION]), while returned-entity disclosure
 * denial from `saveAndLoad()` carries [EntOperation.LOAD]. [writeState]
 * independently reports the database effect — a returned-entity denial
 * after a committed write carries [MutationWriteState.Committed].
 *
 * [entityKey] is nullable because a create privacy denial may not yet
 * have a usable entity key. The key and the privacy-rule-supplied
 * [reason] are trusted diagnostic data; applications must not expose
 * them to untrusted clients without an explicit boundary mapping.
 *
 * A *read* LOAD denial uses [EntPrivacyDeniedException] instead.
 */
class EntMutationPrivacyDeniedException(
    writeState: MutationWriteState,
    val entityType: String,
    val operation: EntOperation,
    val entityKey: EntityKey?,
    val reason: String,
) : EntMutationException(
    writeState,
    "$operation denied on $entityType: $reason",
), EntPrivacyFailure

/**
 * Validation rejected the mutation. Always
 * [MutationWriteState.NotPersisted] by construction — validation runs
 * before persistence. [violations] is never empty.
 */
class EntValidationException(
    val entityType: String,
    val operation: EntOperation,
    val violations: List<ValidationViolation>,
) : EntMutationException(
    MutationWriteState.NotPersisted,
    "Validation failed on $entityType",
) {
    init {
        require(violations.isNotEmpty()) {
            "EntValidationException requires at least one violation"
        }
    }
}

/**
 * A recognized database constraint (UNIQUE, FOREIGN KEY, CHECK, …)
 * rejected the write and the framework-owned write transaction was
 * confirmed rolled back — hence always
 * [MutationWriteState.NotPersisted] by construction. The original
 * driver exception is preserved as [cause]; [driverCode] is the
 * driver-specific code (e.g. PostgreSQL SQLSTATE `"23505"`).
 */
class EntConstraintViolationException(
    val entityType: String,
    val operation: EntOperation,
    val constraint: String?,
    val field: String?,
    val driverCode: String?,
    message: String,
    cause: Exception,
) : EntMutationException(MutationWriteState.NotPersisted, message, cause)

/**
 * An expected concurrency or compare-and-set conflict. Always
 * [MutationWriteState.NotPersisted] by construction — recognized
 * conflicts are statement-level failures whose effect did not persist.
 * A conflict detected from an affected-row count may have no
 * underlying [cause].
 */
class EntConflictException(
    val entityType: String,
    val operation: EntOperation,
    val code: String?,
    message: String,
    cause: Exception? = null,
) : EntMutationException(MutationWriteState.NotPersisted, message, cause)

/**
 * An unclassified failure during mutation terminal execution: an
 * exception crossing an application callback boundary (hook,
 * validator, normalizer, derivation, privacy rule), an unrecognized
 * driver or materialization exception, an unavailable capability
 * discovered during execution, or an EntKt invariant failure. The
 * original exception is always preserved as [cause]; [writeState] is
 * EntKt's own assessment at the failing execution boundary — never a
 * state claim read from the foreign exception.
 */
class EntUnexpectedMutationException(
    writeState: MutationWriteState,
    cause: Exception,
) : EntMutationException(
    writeState,
    "Unexpected mutation failure with write state $writeState",
    cause,
)

/**
 * A read-path interceptor rejected the query before driver execution.
 * Stored in [ReadResult.Failed] by canonical data terminals; `explain*`
 * terminals instead record it on their diagnostic
 * [entkt.runtime.query.QueryPlan]. [interceptor] is the rejecting
 * interceptor's stable registration name (or `"framework:<id>"`).
 */
class EntQueryRejectedException(
    val entityType: String,
    val reason: String,
    val code: String?,
    val interceptor: String,
) : EntException(reason)

/**
 * LOAD privacy denied a read. [origin] identifies whether the
 * terminal's root selection ([LoadDenialOrigin.Root]) or an eagerly
 * loaded target ([LoadDenialOrigin.EagerEdge]) produced the denial;
 * root privacy completes before eager loading begins, so one exception
 * never mixes both. [denials] is never empty: a singular read carries
 * exactly one keyed denial, a strict collection read carries one per
 * denied root row in encountered query order, and a strict eager
 * denial carries exactly the first denied target.
 *
 * This exception proves more than `Success(null)`: it says a selected
 * row existed and was denied. Applications must not expose that
 * distinction — or the key and reason payloads — to untrusted clients
 * without an explicit boundary mapping. The payload never contains
 * hydrated entities or non-key field values.
 */
class EntPrivacyDeniedException(
    val origin: LoadDenialOrigin,
    val denials: List<PrivacyDenial>,
) : EntException(
    "LOAD denied for ${denials.size} ${denials.singleOrNull()?.entityType ?: "entities"}",
), EntPrivacyFailure {
    init {
        require(denials.isNotEmpty()) {
            "EntPrivacyDeniedException requires at least one denial"
        }
    }
}
