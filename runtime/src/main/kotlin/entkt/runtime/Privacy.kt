package entkt.runtime

/**
 * Represents the identity of the current viewer performing an operation.
 */
sealed interface Viewer {
    /** An unauthenticated viewer. */
    data object Anonymous : Viewer
    /** An authenticated user identified by their primary key. */
    data class User(val id: Any) : Viewer
    /** A system-level caller that bypasses privacy checks. */
    data object System : Viewer
}

/**
 * Privacy context captured for a generated operation and threaded
 * through all privacy checks within that operation. Scalar operations
 * capture one context; bulk convenience methods may invoke the
 * provider once per item since they delegate to per-entity paths.
 */
data class PrivacyContext(
    val viewer: Viewer,
)

/**
 * The kind of operation being privacy-checked.
 */
enum class PrivacyOperation {
    LOAD,
    CREATE,
    UPDATE,
    DELETE,
}

/**
 * The result of evaluating a single privacy rule.
 *
 * - [Allow] — stop evaluation and permit the operation.
 * - [Continue] — defer to the next rule in the list.
 * - [Deny] — stop evaluation and reject the operation.
 */
sealed interface PrivacyDecision {
    data object Allow : PrivacyDecision
    data object Continue : PrivacyDecision
    data class Deny(val reason: String) : PrivacyDecision
}

/**
 * Thrown when a privacy rule denies an operation.
 */
class PrivacyDeniedException(
    val entity: String,
    val operation: PrivacyOperation,
    val reason: String,
) : RuntimeException("$operation denied on $entity: $reason")

/**
 * A single privacy rule that evaluates an operation context and returns
 * a [PrivacyDecision]. Rules are evaluated in order; the first non-[PrivacyDecision.Continue]
 * result wins.
 */
fun interface PrivacyRule<in C> {
    fun run(ctx: C): PrivacyDecision
}

/**
 * An entity-scoped policy that configures rules for entity operations
 * (privacy and validation) through a generated scope object. Policies
 * are registered per-entity on the generated `EntClient`.
 */
interface EntityPolicy<E, Scope> {
    fun configure(scope: Scope)
}
