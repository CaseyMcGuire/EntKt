@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal

/** Correlated final privacy outcomes for one ordered subject batch. */
@EntktInternal
class PrivacyEvaluation<Subject> internal constructor(
    private val outcomes: List<PrivacyOutcome<Subject>>,
) : Iterable<PrivacyOutcome<Subject>> {
    /** Number of subjects evaluated. */
    val size: Int
        get() = outcomes.size

    /** Whether the evaluated batch was empty. */
    fun isEmpty(): Boolean = outcomes.isEmpty()

    /** First denied subject in encounter order, or `null` when all subjects were allowed. */
    fun firstDeniedOrNull(): PrivacyOutcome.Denied<Subject>? =
        outcomes.firstNotNullOfOrNull { outcome ->
            when (outcome) {
                is PrivacyOutcome.Allowed -> null
                is PrivacyOutcome.Denied -> outcome
            }
        }

    /** Every denied outcome in encounter order. */
    fun deniedOutcomes(): List<PrivacyOutcome.Denied<Subject>> =
        outcomes.mapNotNull { outcome ->
            when (outcome) {
                is PrivacyOutcome.Allowed -> null
                is PrivacyOutcome.Denied -> outcome
            }
        }

    /** Every allowed subject in encounter order. */
    fun allowedSubjects(): List<Subject> =
        outcomes.mapNotNull { outcome ->
            when (outcome) {
                is PrivacyOutcome.Allowed -> outcome.subject
                is PrivacyOutcome.Denied -> null
            }
        }

    override fun iterator(): Iterator<PrivacyOutcome<Subject>> = outcomes.iterator()
}
