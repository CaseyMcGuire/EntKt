package entkt.runtime.privacy

/**
 * Represents the identity of the current viewer performing an operation.
 */
sealed interface Viewer {
    /** An unauthenticated viewer. */
    data object Anonymous : Viewer
    /** An authenticated user identified by their primary key. */
    data class User(val id: Any) : Viewer

    /**
     * Trusted framework/application escape hatch that bypasses generated privacy
     * checks (LOAD / CREATE / UPDATE / DELETE). It does NOT bypass validation,
     * hooks, query interceptors, transactions, or database constraints. Use only
     * for seed data, migrations, validation reads, tests, and similarly explicit
     * internal operations — prefer the generated `bypassPrivacy_DANGEROUS(reason)`
     * client helper at application call sites. The required [reason] forces
     * authors to say why the escape hatch is being used.
     */
    data class PrivacyBypass(val reason: String) : Viewer {
        init {
            require(reason.isNotBlank()) {
                "Viewer.PrivacyBypass requires a non-blank reason"
            }
        }
    }
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
 * A stock rule that allows any operation on any entity. Because [PrivacyRule]
 * is contravariant in its context, this single value satisfies every typed rule
 * slot — `load(allowAll)`, `create(allowAll)`, `update(allowAll)`,
 * `delete(allowAll)` — on any schema, so a public or trusted entity doesn't need
 * a per-type "allow everything" rule.
 *
 * Under fail-closed privacy this is the explicit opt-in to "no restriction" for
 * an operation; use it deliberately.
 */
val allowAll: PrivacyRule<Any?> = PrivacyRule { PrivacyDecision.Allow }

/**
 * An entity-scoped policy that configures rules for entity operations
 * (privacy and validation) through a generated scope object. Policies
 * are registered per-entity on the generated `EntClient`.
 */
interface EntityPolicy<E, Scope> {
    fun configure(scope: Scope)
}
