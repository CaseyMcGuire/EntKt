package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy

/** Final validation outcome attached to the exact subject that was evaluated. */
@EntktInternal
sealed interface ValidationOutcome<out Subject> {
    /** Subject whose rules produced this outcome. */
    val subject: Subject

    /** Subject accepted by all of its validation rules. */
    class Valid<Subject> internal constructor(
        override val subject: Subject,
    ) : ValidationOutcome<Subject>

    /** Subject rejected by one or more validation rules. */
    class Invalid<Subject> internal constructor(
        override val subject: Subject,
        violations: List<ValidationDecision.Invalid>,
    ) : ValidationOutcome<Subject> {
        val violations: List<ValidationDecision.Invalid> = immutableListCopy(violations)

        init {
            require(this.violations.isNotEmpty()) {
                "an invalid validation evaluation requires at least one violation"
            }
        }
    }
}
