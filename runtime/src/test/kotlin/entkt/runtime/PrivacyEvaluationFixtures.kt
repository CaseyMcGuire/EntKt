@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime

import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.correlatePrivacyEvaluationForInternalUse

internal fun <Subject> privacyEvaluation(
    subjects: List<Subject>,
    decisions: List<PrivacyDecision> = List(subjects.size) { PrivacyDecision.Allow },
): PrivacyEvaluation<Subject> = correlatePrivacyEvaluationForInternalUse(
    lifecycle = "test privacy",
    subjects = subjects,
    decisions = decisions,
    unresolvedReason = "no test rule allowed access",
)
