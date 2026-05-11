package entkt.runtime

/**
 * Operations that can produce an [EntError]. Mirrors [PrivacyOperation]
 * so the two enums map 1:1 by name.
 */
enum class EntOperation {
    LOAD,
    CREATE,
    UPDATE,
    DELETE,
}

/**
 * A structured failure produced by a generated mutation. Each variant
 * carries the entity name (e.g. `"Post"`), the operation that failed,
 * and a human-readable message.
 */
sealed interface EntError {
    val entity: String
    val operation: EntOperation
    val message: String

    /**
     * The targeted owner row does not exist. Emitted by `update(id)`
     * when the internal current-row load returns no row, before any
     * hook, privacy, validation, or driver write runs.
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
     * Privacy denied the operation. Generated `saveOrError()` wraps
     * the existing [PrivacyDeniedException] into this variant so
     * callers can branch on outcome rather than catch exceptions.
     */
    data class PrivacyDenied(
        override val entity: String,
        override val operation: EntOperation,
        val reason: String,
        override val message: String = "$operation denied on $entity: $reason",
    ) : EntError

    /**
     * Validation rejected the mutation. Generated `saveOrError()`
     * wraps the existing [ValidationException] into this variant.
     */
    data class ValidationFailed(
        override val entity: String,
        override val operation: EntOperation,
        val violations: List<ValidationDecision.Invalid>,
        override val message: String =
            "Validation failed on $entity: ${violations.joinToString("; ") { it.message }}",
    ) : EntError
}
