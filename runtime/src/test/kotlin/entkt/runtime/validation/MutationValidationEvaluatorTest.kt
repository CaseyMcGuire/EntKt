@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.runtime.validation.ValidationDecision.Invalid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MutationValidationEvaluatorTest {
    private data class Subject(val id: Long)

    private data class RuleItem(val subject: Subject)

    @Test
    fun `evaluator captures its rules client provider and fresh item adapter`() {
        val ruleClient = Any()
        var providerCalls = 0
        val seenItems = mutableListOf<RuleItem>()
        val evaluator = mutationValidationEvaluatorForInternalUse<Any, Subject, RuleItem>(
            lifecycle = "Subject CREATE validation",
            rules = listOf(
                ValidationRule { context, item ->
                    assertSame(ruleClient, context.client)
                    seenItems += item
                    if (item.subject.id == 2L) Invalid("first") else ValidationDecision.Valid
                },
                ValidationRule { _, item ->
                    seenItems += item
                    if (item.subject.id == 2L) Invalid("second") else ValidationDecision.Valid
                },
            ),
            ruleClientProvider = {
                providerCalls++
                ruleClient
            },
            freshItem = { RuleItem(it.copy()) },
        )

        val evaluation = evaluator.evaluate(listOf(Subject(1L), Subject(2L)))

        assertEquals(listOf(Subject(1L)), evaluation.validSubjects())
        assertEquals(listOf("first", "second"), evaluation.firstInvalidOrNull()?.violations?.map { it.message })
        assertEquals(1, providerCalls)
        val secondSubjectItems = seenItems.filter { it.subject.id == 2L }
        assertNotSame(secondSubjectItems[0], secondSubjectItems[1])
        assertNotSame(secondSubjectItems[0].subject, secondSubjectItems[1].subject)
    }

    @Test
    fun `empty evaluation does not resolve the rule client`() {
        var providerCalls = 0
        val evaluator = mutationValidationEvaluatorForInternalUse<Any, Subject, RuleItem>(
            lifecycle = "Subject UPDATE validation",
            rules = emptyList(),
            ruleClientProvider = {
                providerCalls++
                Any()
            },
            freshItem = ::RuleItem,
        )

        assertEquals(0, evaluator.evaluate(emptyList()).size)
        assertEquals(0, providerCalls)
    }
}
