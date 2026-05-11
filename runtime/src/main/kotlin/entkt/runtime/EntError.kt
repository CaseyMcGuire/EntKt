package entkt.runtime

/**
 * Operations that can produce an [EntError]. Currently used by mutation
 * paths; LOAD will be added as the result-variant API expands.
 */
enum class EntOperation {
    CREATE,
    UPDATE,
    DELETE,
}

/**
 * A structured failure produced by a generated mutation. Each variant
 * carries the entity name (e.g. `"Post"`), the operation that failed,
 * and a human-readable message. Variants are introduced as the
 * generated save paths produce them; this minimal pair backs the
 * id-based update root semantics.
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
}
