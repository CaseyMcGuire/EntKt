@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.query.EntktInternal
import entkt.runtime.internal.immutableListCopy
import entkt.runtime.result.EntBatchRuleContractException

/** Correlated final validation outcomes for one ordered subject batch. */
@EntktInternal
class ValidationEvaluation<Subject> internal constructor(
    outcomes: List<ValidationOutcome<Subject>>,
) : Iterable<ValidationOutcome<Subject>> {
    private val outcomes: List<ValidationOutcome<Subject>> = immutableListCopy(outcomes)

    /** Number of subjects evaluated. */
    val size: Int
        get() = outcomes.size

    /** Whether the evaluated batch was empty. */
    fun isEmpty(): Boolean = outcomes.isEmpty()

    /** First invalid subject in encounter order, or `null` when every subject was valid. */
    fun firstInvalidOrNull(): ValidationOutcome.Invalid<Subject>? =
        outcomes.firstNotNullOfOrNull { outcome ->
            when (outcome) {
                is ValidationOutcome.Valid -> null
                is ValidationOutcome.Invalid -> outcome
            }
        }

    /** Every invalid outcome in encounter order. */
    fun invalidOutcomes(): List<ValidationOutcome.Invalid<Subject>> =
        outcomes.mapNotNull { outcome ->
            when (outcome) {
                is ValidationOutcome.Valid -> null
                is ValidationOutcome.Invalid -> outcome
            }
        }

    /** Every valid subject in encounter order. */
    fun validSubjects(): List<Subject> =
        outcomes.mapNotNull { outcome ->
            when (outcome) {
                is ValidationOutcome.Valid -> outcome.subject
                is ValidationOutcome.Invalid -> null
            }
        }

    override fun iterator(): Iterator<ValidationOutcome<Subject>> = outcomes.iterator()
}

/** Attach collected rule violations to their subjects and reject malformed batch cardinality. */
internal fun <Subject> correlateValidationEvaluationForInternalUse(
    lifecycle: String,
    subjects: List<Subject>,
    violationsBySubject: List<List<ValidationDecision.Invalid>>,
): ValidationEvaluation<Subject> {
    val subjectSnapshot = subjects.toList()
    val violationSnapshot = violationsBySubject.map { it.toList() }
    if (violationSnapshot.size != subjectSnapshot.size) {
        throw EntBatchRuleContractException(
            lifecycle = lifecycle,
            expectedSize = subjectSnapshot.size,
            actualSize = violationSnapshot.size,
        )
    }
    return ValidationEvaluation(
        subjectSnapshot.mapIndexed { index, subject ->
            val violations = violationSnapshot[index]
            if (violations.isEmpty()) {
                ValidationOutcome.Valid(subject)
            } else {
                ValidationOutcome.Invalid(subject, violations)
            }
        },
    )
}
