@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.rule.RuleBatch
import entkt.runtime.validation.ValidationDecision.Invalid
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MutationValidationEvaluatorTest {
    private data class Subject(val id: Long)

    private data class RuleItem(val subject: Subject)

    @Test
    fun `direct input constructor preserves subjects and context without a caller converter`() {
        val context = ValidationRuleContext(Any())
        val seen = mutableListOf<Subject>()
        val rules = mutableListOf<BatchValidationRule<Any, Subject>>(
            ValidationRule { suppliedContext, subject ->
                assertSame(context, suppliedContext)
                seen += subject
                ValidationDecision.Valid
            },
            batchValidationRule { suppliedContext, batch ->
                assertSame(context, suppliedContext)
                batch.decideEachIndexed { index, subject ->
                    seen += subject
                    if (index == 0) Invalid("first") else ValidationDecision.Valid
                }
            },
        )
        val evaluator = MutationValidationEvaluator(
            lifecycle = "Subject CREATE validation",
            rules = rules,
        )
        rules.clear()
        val subject = Subject(1)

        val evaluation = evaluator.evaluate(context, listOf(subject, subject))

        assertEquals(4, seen.size)
        seen.forEach { assertSame(subject, it) }
        assertSame(subject, evaluation.validSubjects().single())
        assertSame(subject, evaluation.firstInvalidOrNull()!!.subject)
        assertEquals(listOf("first"), evaluation.firstInvalidOrNull()!!.violations.map { it.message })
    }

    @Test
    fun `evaluator binds its rules and item adapter but receives its context per call`() {
        val ruleClient = Any()
        val ruleContext = ValidationRuleContext(ruleClient)
        val seenItems = mutableListOf<RuleItem>()
        val evaluator = MutationValidationEvaluator<Any, Subject>(
            lifecycle = "Subject CREATE validation",
            primary = ValidationDecisionEvaluator(
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
                freshItem = ::RuleItem,
            ),
        )

        val evaluation = evaluator.evaluate(ruleContext, listOf(Subject(1L), Subject(2L)))

        assertEquals(listOf(Subject(1L)), evaluation.validSubjects())
        assertEquals(listOf("first", "second"), evaluation.firstInvalidOrNull()?.violations?.map { it.message })
        val secondSubjectItems = seenItems.filter { it.subject.id == 2L }
        assertNotSame(secondSubjectItems[0], secondSubjectItems[1])
        assertSame(secondSubjectItems[0].subject, secondSubjectItems[1].subject)
    }

    @Test
    fun `empty evaluation does not invoke rules or convert inputs`() {
        val evaluator = MutationValidationEvaluator<Any, Subject>(
            lifecycle = "Subject UPDATE validation",
            primary = ValidationDecisionEvaluator<Any, Subject, RuleItem>(
                rules = listOf(ValidationRule { _, _ -> error("Rule must not run") }),
                freshItem = { error("Input must not be converted") },
            ),
            additional = ValidationDecisionEvaluator<Any, Subject, Subject>(
                rules = listOf(ValidationRule { _, _ -> error("Additional rule must not run") }),
                freshItem = { error("Additional input must not be converted") },
            ),
        )

        assertEquals(0, evaluator.evaluate(ValidationRuleContext(Any()), emptyList()).size)
    }

    @Test
    fun `primary and additional rules share each supplied context without retaining a client`() {
        val seenContexts = mutableListOf<ValidationRuleContext<Any>>()
        val evaluator = MutationValidationEvaluator<Any, Subject>(
            lifecycle = "Subject UPDATE validation",
            primary = ValidationDecisionEvaluator(
                rules = listOf(ValidationRule { context, _ ->
                    seenContexts += context
                    Invalid("primary")
                }),
                freshItem = ::RuleItem,
            ),
            additional = ValidationDecisionEvaluator<Any, Subject, Subject>(
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

    @Test
    fun `primary and additional exceptions including cancellation escape unchanged`() {
        for (failure in listOf(IllegalStateException("rule failed"), CancellationException("cancelled"))) {
            for (failInAdditional in listOf(false, true)) {
                val evaluator = MutationValidationEvaluator<Any, Subject>(
                    lifecycle = "Subject UPDATE validation",
                    rules = listOf(ValidationRule { _, _ ->
                        if (!failInAdditional) throw failure
                        Invalid("primary")
                    }),
                    additional = ValidationDecisionEvaluator<Any, Subject, Subject>(
                        rules = listOf(ValidationRule { _, _ -> throw failure }),
                        freshItem = { it },
                    ),
                )

                assertSame(failure, assertFails { evaluator.evaluate(ValidationRuleContext(Any()), listOf(Subject(1))) })
            }
        }
    }

    @Test
    fun `primary and additional contract failures use the parent lifecycle`() {
        val subject = Subject(1)
        val foreignDecisions = RuleBatch.from(listOf(subject)).decideEach { ValidationDecision.Valid }
        for (failInAdditional in listOf(false, true)) {
            val evaluator = MutationValidationEvaluator<Any, Subject>(
                lifecycle = "Subject UPDATE validation",
                rules = listOf(batchValidationRule { _, batch ->
                    if (failInAdditional) batch.decideEach { ValidationDecision.Valid } else foreignDecisions
                }),
                additional = ValidationDecisionEvaluator<Any, Subject, Subject>(
                    rules = listOf(batchValidationRule { _, _ -> foreignDecisions }),
                    freshItem = { it },
                ),
            )

            val failure = assertFailsWith<EntBatchRuleContractException> {
                evaluator.evaluate(ValidationRuleContext(Any()), listOf(subject))
            }

            assertEquals("Subject UPDATE validation", failure.lifecycle)
        }
    }
}
