@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.runtime.result.EntBatchRuleContractException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class ValidationEvaluationTest {
    private data class Subject(val value: Int)

    @Test
    fun `correlation attaches collected violations to the exact subject`() {
        val first = Subject(1)
        val equalButDistinct = Subject(1)
        val firstViolation = ValidationDecision.Invalid("first")
        val secondViolation = ValidationDecision.Invalid("second")

        val evaluation = correlateValidationEvaluationForInternalUse(
            lifecycle = "Subject CREATE validation",
            subjects = listOf(first, equalButDistinct),
            violationsBySubject = listOf(emptyList(), listOf(firstViolation, secondViolation)),
        )

        assertEquals(2, evaluation.size)
        assertSame(first, evaluation.validSubjects().single())
        val invalid = evaluation.firstInvalidOrNull()
        assertIs<ValidationOutcome.Invalid<Subject>>(invalid)
        assertSame(equalButDistinct, invalid.subject)
        assertEquals(listOf(firstViolation, secondViolation), invalid.violations)
    }

    @Test
    fun `correlation snapshots a subjects violations`() {
        val violations = mutableListOf(ValidationDecision.Invalid("initial"))
        val evaluation = correlateValidationEvaluationForInternalUse(
            lifecycle = "Subject UPDATE validation",
            subjects = listOf(Subject(1)),
            violationsBySubject = listOf(violations),
        )

        violations += ValidationDecision.Invalid("later")

        assertEquals(1, evaluation.firstInvalidOrNull()?.violations?.size)
    }

    @Test
    fun `correlation rejects a result count that does not match its subjects`() {
        val exception = assertFailsWith<EntBatchRuleContractException> {
            correlateValidationEvaluationForInternalUse(
                lifecycle = "Subject DELETE validation",
                subjects = listOf(Subject(1), Subject(2)),
                violationsBySubject = listOf(emptyList()),
            )
        }

        assertEquals("Subject DELETE validation", exception.lifecycle)
        assertEquals(2, exception.expectedSize)
        assertEquals(1, exception.actualSize)
    }
}
