@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime

import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.PrivacyOutcome

internal fun <Subject> privacyEvaluation(
    subjects: List<Subject>,
    decisions: List<PrivacyDecision> = List(subjects.size) { PrivacyDecision.Allow },
): PrivacyEvaluation<Subject> = PrivacyEvaluation(
    subjects.mapIndexed { index, subject ->
        when (val decision = decisions[index]) {
            PrivacyDecision.Allow -> PrivacyOutcome.Allowed(subject)
            is PrivacyDecision.Deny -> PrivacyOutcome.Denied(subject, decision.reason)
            PrivacyDecision.Continue -> PrivacyOutcome.Denied(subject, "no test rule allowed access")
        }
    },
)
