@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.result.EntBatchRuleContractException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class PrivacyEvaluationTest {
    private data class Subject(val value: Int)

    @Test
    fun `correlation attaches final decisions to the exact subjects`() {
        val first = Subject(1)
        val equalButDistinct = Subject(1)

        val evaluation = correlatePrivacyEvaluationForInternalUse(
            lifecycle = "Subject CREATE privacy",
            subjects = listOf(first, equalButDistinct),
            decisions = listOf(PrivacyDecision.Allow, PrivacyDecision.Deny("hidden")),
            unresolvedReason = "no create rule allowed access",
        )

        assertEquals(2, evaluation.size)
        assertSame(first, evaluation.allowedSubjects().single())
        val denied = evaluation.firstDeniedOrNull()
        assertIs<PrivacyOutcome.Denied<Subject>>(denied)
        assertSame(equalButDistinct, denied.subject)
        assertEquals("hidden", denied.reason)
    }

    @Test
    fun `correlation finalizes unresolved privacy as a denial`() {
        val subject = Subject(1)

        val evaluation = correlatePrivacyEvaluationForInternalUse(
            lifecycle = "Subject UPDATE privacy",
            subjects = listOf(subject),
            decisions = listOf(PrivacyDecision.Continue),
            unresolvedReason = "no update rule allowed access",
        )

        val denied = evaluation.firstDeniedOrNull()
        assertIs<PrivacyOutcome.Denied<Subject>>(denied)
        assertSame(subject, denied.subject)
        assertEquals("no update rule allowed access", denied.reason)
    }

    @Test
    fun `correlation rejects a result count that does not match its subjects`() {
        val exception = assertFailsWith<EntBatchRuleContractException> {
            correlatePrivacyEvaluationForInternalUse(
                lifecycle = "Subject DELETE privacy",
                subjects = listOf(Subject(1), Subject(2)),
                decisions = listOf(PrivacyDecision.Allow),
                unresolvedReason = "no delete rule allowed access",
            )
        }

        assertEquals("Subject DELETE privacy", exception.lifecycle)
        assertEquals(2, exception.expectedSize)
        assertEquals(1, exception.actualSize)
    }
}
