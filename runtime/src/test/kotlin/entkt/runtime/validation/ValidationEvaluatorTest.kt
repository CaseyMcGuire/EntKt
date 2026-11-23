@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.result.EntOperation
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.TestRuleClient
import entkt.runtime.validation.ValidationDecision.Invalid
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ValidationEvaluatorTest {
    private data class Input(val id: Long)

    private fun evaluator(
        vararg rules: BatchValidationRule<TestRuleClient, Input>,
        operation: EntOperation = EntOperation.UPDATE,
    ) = ValidationEvaluator("Subject", operation, rules.toList())

    @Test
    fun `scalar and batch rules share the supplied context and the original inputs`() {
        val context = ValidationRuleContext(TestRuleClient())
        val seen = mutableListOf<Input>()
        val evaluator = evaluator(
            ValidationRule { suppliedContext, input ->
                assertSame(context, suppliedContext)
                seen += input
                ValidationDecision.Valid
            },
            batchValidationRule { suppliedContext, batch ->
                assertSame(context, suppliedContext)
                batch.decideEachIndexed { index, input ->
                    seen += input
                    if (index == 0) Invalid("first") else ValidationDecision.Valid
                }
            },
        )
        val input = Input(1)

        val violations = evaluator.evaluate(context, listOf(input, input))

        assertEquals(4, seen.size)
        seen.forEach { assertSame(input, it) }
        assertEquals(listOf(listOf("first"), emptyList()), violations.map { it.map(Invalid::message) })
    }

    @Test
    fun `every rule runs for every input and violations keep rule order per input`() {
        val evaluator = evaluator(
            ValidationRule { _, input -> if (input.id == 2L) Invalid("first") else ValidationDecision.Valid },
            ValidationRule { _, input -> if (input.id == 2L) Invalid("second") else ValidationDecision.Valid },
        )

        val violations = evaluator.evaluate(ValidationRuleContext(TestRuleClient()), listOf(Input(1), Input(2)))

        assertEquals(listOf(emptyList(), listOf("first", "second")), violations.map { it.map(Invalid::message) })
    }

    @Test
    fun `no rules leave every input valid`() {
        val violations = evaluator().evaluate(ValidationRuleContext(TestRuleClient()), listOf(Input(1), Input(2)))

        assertEquals(listOf(emptyList(), emptyList()), violations)
    }

    @Test
    fun `empty evaluation does not invoke rules`() {
        val evaluator = evaluator(ValidationRule { _, _ -> error("Rule must not run") })

        assertTrue(evaluator.evaluate(ValidationRuleContext(TestRuleClient()), emptyList()).isEmpty())
    }

    @Test
    fun `each evaluation uses its supplied context without retaining a client`() {
        val seenContexts = mutableListOf<ValidationRuleContext<TestRuleClient>>()
        val evaluator = evaluator(
            ValidationRule { context, _ ->
                seenContexts += context
                Invalid("invalid")
            },
        )
        val contexts = listOf(ValidationRuleContext(TestRuleClient()), ValidationRuleContext(TestRuleClient()))

        for (context in contexts) {
            assertEquals(listOf("invalid"), evaluator.evaluate(context, listOf(Input(1))).single().map(Invalid::message))
            assertEquals("validation read", context.readViewerContext.reason)
        }

        assertEquals(2, seenContexts.size)
        assertSame(contexts[0], seenContexts[0])
        assertSame(contexts[1], seenContexts[1])
    }

    @Test
    fun `rule exceptions including cancellation escape unchanged`() {
        for (failure in listOf(IllegalStateException("rule failed"), CancellationException("cancelled"))) {
            val evaluator = evaluator(ValidationRule { _, _ -> throw failure })

            assertSame(failure, assertFails { evaluator.evaluate(ValidationRuleContext(TestRuleClient()), listOf(Input(1))) })
        }
    }

    @Test
    fun `contract failures name the entity and the configured operation`() {
        val input = Input(1)
        val foreignDecisions = RuleBatch.from(listOf(input)).decideEach { ValidationDecision.Valid }
        for (operation in listOf(EntOperation.CREATE, EntOperation.UPDATE, EntOperation.DELETE)) {
            val evaluator = evaluator(batchValidationRule { _, _ -> foreignDecisions }, operation = operation)

            val failure = assertFailsWith<EntBatchRuleContractException> {
                evaluator.evaluate(ValidationRuleContext(TestRuleClient()), listOf(input))
            }

            assertEquals("Subject ${operation.name} validation", failure.lifecycle)
            assertTrue(failure.foreignBatchResult)
        }
    }
}
