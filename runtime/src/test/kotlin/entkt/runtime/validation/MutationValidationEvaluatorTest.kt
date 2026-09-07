@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.runtime.privacy.Viewer
import entkt.runtime.validation.ValidationDecision.Invalid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MutationValidationEvaluatorTest {
    private data class Subject(val id: Long)

    private data class RuleItem(val subject: Subject)

    @Test
    fun `evaluator binds its rules and item adapter but receives its context per call`() {
        val ruleClient = Any()
        val ruleContext = ValidationRuleContext(ruleClient)
        val seenItems = mutableListOf<RuleItem>()
        val evaluator = mutationValidationEvaluatorForInternalUse<Any, Subject, RuleItem>(
            lifecycle = "Subject CREATE validation",
            rules = listOf(
                ValidationRule { context, item ->
                    assertSame(ruleContext, context)
                    assertSame(ruleClient, context.client)
                    seenItems += item
                    if (item.subject.id == 2L) Invalid("first") else ValidationDecision.Valid
                },
                ValidationRule { context, item ->
                    assertSame(ruleContext, context)
                    seenItems += item
                    if (item.subject.id == 2L) Invalid("second") else ValidationDecision.Valid
                },
            ),
            freshItem = { RuleItem(it.copy()) },
        )

        val evaluation = evaluator.evaluate(ruleContext, listOf(Subject(1L), Subject(2L)))

        assertEquals(listOf(Subject(1L)), evaluation.validSubjects())
        assertEquals(listOf("first", "second"), evaluation.firstInvalidOrNull()?.violations?.map { it.message })
        val secondSubjectItems = seenItems.filter { it.subject.id == 2L }
        assertNotSame(secondSubjectItems[0], secondSubjectItems[1])
        assertNotSame(secondSubjectItems[0].subject, secondSubjectItems[1].subject)
    }

    @Test
    fun `empty evaluation does not invoke rules or convert inputs`() {
        val evaluator = mutationValidationEvaluatorForInternalUse<Any, Subject, RuleItem>(
            lifecycle = "Subject UPDATE validation",
            rules = listOf(ValidationRule { _, _ -> error("Rule must not run") }),
            freshItem = { error("Input must not be converted") },
        )

        assertEquals(0, evaluator.evaluate(ValidationRuleContext(Any()), emptyList()).size)
    }

    @Test
    fun `primary and additional rules share each supplied context without retaining a client`() {
        val seenContexts = mutableListOf<ValidationRuleContext<Any>>()
        val evaluator = mutationValidationEvaluatorForInternalUse<Any, Subject, RuleItem>(
            lifecycle = "Subject UPDATE validation",
            rules = listOf(ValidationRule { context, _ ->
                seenContexts += context
                Invalid("primary")
            }),
            freshItem = ::RuleItem,
            additional = validationDecisionEvaluatorForInternalUse<Any, Subject, Subject>(
                lifecycle = "Subject UPDATE validation",
                rules = listOf(ValidationRule { context, _ ->
                    seenContexts += context
                    Invalid("additional")
                }),
                freshItem = { it },
            ),
        )
        val contexts = listOf(ValidationRuleContext(Any()), ValidationRuleContext(Any()))
        val subject = Subject(1)

        for (context in contexts) {
            val evaluation = evaluator.evaluate(context, listOf(subject))
            assertSame(subject, evaluation.firstInvalidOrNull()!!.subject)
            assertEquals(listOf("primary", "additional"), evaluation.firstInvalidOrNull()!!.violations.map { it.message })
            assertIs<Viewer.PrivacyBypass>(context.readViewerContext.viewer)
        }

        assertEquals(4, seenContexts.size)
        assertSame(contexts[0], seenContexts[0])
        assertSame(contexts[0], seenContexts[1])
        assertSame(contexts[1], seenContexts[2])
        assertSame(contexts[1], seenContexts[3])
    }
}
