@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy
import entkt.runtime.result.EntBatchRuleContractException

/** Correlated final privacy outcomes for one ordered subject batch. */
@EntktInternal
class PrivacyEvaluation<Subject> internal constructor(
    outcomes: List<PrivacyOutcome<Subject>>,
) : Iterable<PrivacyOutcome<Subject>> {
    private val outcomes: List<PrivacyOutcome<Subject>> = immutableListCopy(outcomes)

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

/** Attach final rule decisions to their subjects and reject malformed batch cardinality. */
internal fun <Subject> correlatePrivacyEvaluationForInternalUse(
    lifecycle: String,
    subjects: List<Subject>,
    decisions: List<PrivacyDecision>,
    unresolvedReason: String,
): PrivacyEvaluation<Subject> {
    val subjectSnapshot = subjects.toList()
    val decisionSnapshot = decisions.toList()
    if (decisionSnapshot.size != subjectSnapshot.size) {
        throw EntBatchRuleContractException(
            lifecycle = lifecycle,
            expectedSize = subjectSnapshot.size,
            actualSize = decisionSnapshot.size,
        )
    }
    return PrivacyEvaluation(
        subjectSnapshot.mapIndexed { index, subject ->
            when (val decision = decisionSnapshot[index]) {
                PrivacyDecision.Allow -> PrivacyOutcome.Allowed(subject)
                is PrivacyDecision.Deny -> PrivacyOutcome.Denied(subject, decision.reason)
                PrivacyDecision.Continue -> PrivacyOutcome.Denied(subject, unresolvedReason)
            }
        },
    )
}
