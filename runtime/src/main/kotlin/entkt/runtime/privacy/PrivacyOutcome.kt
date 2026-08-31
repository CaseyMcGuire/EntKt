package entkt.runtime.privacy

import entkt.query.EntktInternal

/** Final privacy outcome attached to the exact subject that was evaluated. */
@EntktInternal
sealed interface PrivacyOutcome<out Subject> {
    /** Subject whose rules produced this outcome. */
    val subject: Subject

    /** Subject permitted by its privacy rules. */
    class Allowed<Subject> internal constructor(
        override val subject: Subject,
    ) : PrivacyOutcome<Subject>

    /** Subject rejected by its privacy rules. */
    class Denied<Subject> internal constructor(
        override val subject: Subject,
        val reason: String,
    ) : PrivacyOutcome<Subject>
}
