@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime

import entkt.runtime.hook.Hook
import entkt.runtime.hook.batchHook
import entkt.runtime.hook.runBatchHooksForInternalUse
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.privacy.evaluateBatchPrivacyRulesForInternalUse
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.batchValidationRule
import entkt.runtime.validation.evaluateBatchValidationRulesForInternalUse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BatchLifecycleEvaluationTest {

    @Test
    fun `privacy evaluation filters finalized items and retains original positions`() {
        val invocations = mutableListOf<List<Int>>()
        val rules = listOf(
            batchPrivacyRule<Int> { contexts ->
                invocations += contexts
                contexts.decide {
                    when (it) {
                        1 -> PrivacyDecision.Allow
                        3 -> PrivacyDecision.Deny("three")
                        else -> PrivacyDecision.Continue
                    }
                }
            },
            batchPrivacyRule<Int> { contexts ->
                invocations += contexts
                contexts.decide { PrivacyDecision.Deny("remaining $it") }
            },
        )

        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "Widget LOAD privacy",
            items = listOf(1, 2, 3, 4),
            rules = rules,
            freshContext = { it },
        )

        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(2, 4)), invocations)
        assertEquals(
            listOf(
                PrivacyDecision.Allow,
                PrivacyDecision.Deny("remaining 2"),
                PrivacyDecision.Deny("three"),
                PrivacyDecision.Deny("remaining 4"),
            ),
            decisions,
        )
    }

    @Test
    fun `privacy evaluation creates fresh contexts for every reached rule`() {
        var nextSnapshot = 0
        val seen = mutableListOf<List<Int>>()
        val rules = listOf(
            batchPrivacyRule<Int> { contexts ->
                seen += contexts
                contexts.decide { PrivacyDecision.Continue }
            },
            batchPrivacyRule<Int> { contexts ->
                seen += contexts
                contexts.decide { PrivacyDecision.Allow }
            },
        )

        evaluateBatchPrivacyRulesForInternalUse("Widget LOAD privacy", listOf(Unit), rules) { ++nextSnapshot }

        assertEquals(listOf(listOf(1), listOf(2)), seen)
    }

    @Test
    fun `privacy evaluation correlates duplicate values by position and skips later rules once resolved`() {
        var laterCalls = 0
        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "Widget LOAD privacy",
            items = listOf(7, 7),
            rules = listOf(
                batchPrivacyRule<Int> { contexts ->
                    contexts.decideIndexed { index, _ ->
                        if (index == 0) PrivacyDecision.Allow else PrivacyDecision.Deny("second")
                    }
                },
                batchPrivacyRule<Int> { contexts ->
                    laterCalls++
                    contexts.decide { PrivacyDecision.Allow }
                },
            ),
            freshContext = { it },
        )

        assertEquals(listOf(PrivacyDecision.Allow, PrivacyDecision.Deny("second")), decisions)
        assertEquals(0, laterCalls)
    }

    @Test
    fun `privacy decideIndexed uses the current active batch index`() {
        val laterBatches = mutableListOf<List<String>>()
        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "Widget LOAD privacy",
            items = listOf("same", "resolved", "same"),
            rules = listOf(
                batchPrivacyRule<String> { batch ->
                    batch.decide {
                        if (it == "resolved") PrivacyDecision.Allow else PrivacyDecision.Continue
                    }
                },
                batchPrivacyRule<String> { batch ->
                    laterBatches += batch
                    batch.decideIndexed { index, _ -> PrivacyDecision.Deny("active-$index") }
                },
            ),
            freshContext = { it },
        )

        assertEquals(listOf(listOf("same", "same")), laterBatches)
        assertEquals(
            listOf(
                PrivacyDecision.Deny("active-0"),
                PrivacyDecision.Allow,
                PrivacyDecision.Deny("active-1"),
            ),
            decisions,
        )
    }

    @Test
    fun `validation evaluation is rule-major and preserves violation order per item`() {
        val invocations = mutableListOf<List<String>>()
        val rules = listOf(
            batchValidationRule<String> { contexts ->
                invocations += contexts
                contexts.decide { ValidationDecision.Invalid("first $it") }
            },
            batchValidationRule<String> { contexts ->
                invocations += contexts
                contexts.decide {
                    if (it == "a") ValidationDecision.Valid else ValidationDecision.Invalid("second $it")
                }
            },
        )

        val violations = evaluateBatchValidationRulesForInternalUse(
            lifecycle = "Widget CREATE validation",
            items = listOf("a", "b"),
            rules = rules,
            freshContext = { it },
        )

        assertEquals(listOf(listOf("a", "b"), listOf("a", "b")), invocations)
        assertEquals(listOf("first a"), violations[0].map { it.message })
        assertEquals(listOf("first b", "second b"), violations[1].map { it.message })
    }

    @Test
    fun `validation evaluation creates fresh contexts for each item and rule`() {
        var nextSnapshot = 0
        val seen = mutableListOf<List<Int>>()
        val rules = listOf(
            batchValidationRule<Int> { contexts ->
                seen += contexts
                contexts.decide { ValidationDecision.Valid }
            },
            batchValidationRule<Int> { contexts ->
                seen += contexts
                contexts.decide { ValidationDecision.Valid }
            },
        )

        evaluateBatchValidationRulesForInternalUse(
            "Widget CREATE validation",
            listOf(Unit, Unit),
            rules,
        ) { ++nextSnapshot }

        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), seen)
    }

    @Test
    fun `no rules preserve one unresolved result slot per item`() {
        assertEquals(
            listOf(PrivacyDecision.Continue, PrivacyDecision.Continue),
            evaluateBatchPrivacyRulesForInternalUse<Int, Int>(
                "Widget LOAD privacy",
                listOf(1, 2),
                emptyList(),
            ) { it },
        )
        assertEquals(
            listOf(emptyList(), emptyList()),
            evaluateBatchValidationRulesForInternalUse<Int, Int>(
                "Widget CREATE validation",
                listOf(1, 2),
                emptyList(),
            ) { it },
        )
    }

    @Test
    fun `callbacks are skipped for empty inputs`() {
        var privacyCalls = 0
        var validationCalls = 0
        var hookCalls = 0

        val privacy = batchPrivacyRule<Int> { batch ->
            privacyCalls++
            batch.decide { PrivacyDecision.Allow }
        }
        val validation = batchValidationRule<Int> { batch ->
            validationCalls++
            batch.decide { ValidationDecision.Valid }
        }
        val hook = batchHook<Int> { hookCalls++ }

        assertEquals(
            emptyList(),
            evaluateBatchPrivacyRulesForInternalUse("privacy", emptyList<Int>(), listOf(privacy)) { it },
        )
        assertEquals(
            emptyList(),
            evaluateBatchValidationRulesForInternalUse("validation", emptyList<Int>(), listOf(validation)) { it },
        )
        runBatchHooksForInternalUse(emptyList<Int>(), listOf(hook))

        assertEquals(0, privacyCalls)
        assertEquals(0, validationCalls)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `privacy and validation evaluators reject decisions from another batch`() {
        var priorPrivacyDecisions: entkt.runtime.rule.RuleDecisions<PrivacyDecision>? = null
        val privacyDelegate = batchPrivacyRule<Int> { batch ->
            priorPrivacyDecisions ?: batch.decide { PrivacyDecision.Allow }.also {
                priorPrivacyDecisions = it
            }
        }
        val privacy = batchPrivacyRule<Int> { batch ->
            privacyDelegate.runBatch(batch).mapDecisions { it }
        }
        evaluateBatchPrivacyRulesForInternalUse("Widget LOAD privacy", listOf(1), listOf(privacy)) { it }
        val privacyException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchPrivacyRulesForInternalUse("Widget LOAD privacy", listOf(2), listOf(privacy)) { it }
        }
        assertEquals(true, privacyException.foreignBatchResult)

        var priorValidationDecisions: entkt.runtime.rule.RuleDecisions<ValidationDecision>? = null
        val validation = batchValidationRule<Int> { batch ->
            priorValidationDecisions ?: batch.decide { ValidationDecision.Valid }.also {
                priorValidationDecisions = it
            }
        }
        evaluateBatchValidationRulesForInternalUse("Widget CREATE validation", listOf(1), listOf(validation)) { it }
        val validationException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchValidationRulesForInternalUse(
                "Widget CREATE validation",
                listOf(2),
                listOf(validation),
            ) { it }
        }
        assertEquals(true, validationException.foreignBatchResult)
    }

    @Test
    fun `privacy and validation evaluators reject null Java decisions`() {
        val privacyException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchPrivacyRulesForInternalUse(
                "Widget LOAD privacy",
                listOf("widget"),
                listOf(BatchLifecycleJavaCompatibility.NULL_PRIVACY_DECISION_BATCH),
            ) { it }
        }
        assertEquals(0, privacyException.invalidDecisionIndex)
        assertEquals(1, privacyException.expectedSize)
        assertEquals(1, privacyException.actualSize)

        val validationException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchValidationRulesForInternalUse(
                "Widget CREATE validation",
                listOf("widget"),
                listOf(BatchLifecycleJavaCompatibility.NULL_VALIDATION_DECISION_BATCH),
            ) { it }
        }
        assertEquals(0, validationException.invalidDecisionIndex)
        assertEquals(1, validationException.expectedSize)
        assertEquals(1, validationException.actualSize)
    }

    @Test
    fun `privacy and validation evaluators reject null Java decision results`() {
        val privacyException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchPrivacyRulesForInternalUse(
                "Widget LOAD privacy",
                listOf("widget"),
                listOf(BatchLifecycleJavaCompatibility.NULL_PRIVACY_RESULT_BATCH),
            ) { it }
        }
        assertEquals(null, privacyException.actualSize)
        assertEquals(null, privacyException.invalidDecisionIndex)
        assertEquals(
            "Batch rule contract violation for Widget LOAD privacy: expected 1 decisions but received null",
            privacyException.message,
        )

        val validationException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchValidationRulesForInternalUse(
                "Widget CREATE validation",
                listOf("widget"),
                listOf(BatchLifecycleJavaCompatibility.NULL_VALIDATION_RESULT_BATCH),
            ) { it }
        }
        assertEquals(null, validationException.actualSize)
        assertEquals(null, validationException.invalidDecisionIndex)
        assertEquals(
            "Batch rule contract violation for Widget CREATE validation: expected 1 decisions but received null",
            validationException.message,
        )
    }

    @Test
    fun `callback exceptions escape unchanged and stop later rules`() {
        val failure = IllegalStateException("rule failed")
        var laterCalls = 0
        val thrown = assertFailsWith<IllegalStateException> {
            evaluateBatchPrivacyRulesForInternalUse(
                lifecycle = "Widget LOAD privacy",
                items = listOf(1),
                rules = listOf(
                    batchPrivacyRule<Int> { throw failure },
                    batchPrivacyRule<Int> { contexts ->
                        laterCalls++
                        contexts.decide { PrivacyDecision.Allow }
                    },
                ),
                freshContext = { it },
            )
        }

        assertSame(failure, thrown)
        assertEquals(0, laterCalls)
    }

    @Test
    fun `callback batches are immutable even when cast`() {
        val privacy = batchPrivacyRule<Int> { contexts ->
            assertFailsWith<ClassCastException> {
                @Suppress("UNCHECKED_CAST")
                (contexts as Any as MutableList<Int>).clear()
            }
            contexts.decide { PrivacyDecision.Allow }
        }
        val validation = batchValidationRule<Int> { contexts ->
            assertFailsWith<ClassCastException> {
                @Suppress("UNCHECKED_CAST")
                (contexts as Any as MutableList<Int>).clear()
            }
            contexts.decide { ValidationDecision.Valid }
        }
        val hook = batchHook<Int> { elements ->
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (elements as MutableList<Int>).clear()
            }
        }

        evaluateBatchPrivacyRulesForInternalUse("privacy", listOf(1), listOf(privacy)) { it }
        evaluateBatchValidationRulesForInternalUse("validation", listOf(1), listOf(validation)) { it }
        runBatchHooksForInternalUse(listOf(1), listOf(hook))
    }

    @Test
    fun `hooks run in registration order and scalar hooks retain element order`() {
        val events = mutableListOf<String>()
        val hooks = listOf(
            Hook<Int> { events += "scalar $it" },
            batchHook<Int> { elements -> events += "batch ${elements.joinToString()}" },
            Hook<Int> { events += "last $it" },
        )

        runBatchHooksForInternalUse(listOf(2, 1), hooks)

        assertEquals(
            listOf("scalar 2", "scalar 1", "batch 2, 1", "last 2", "last 1"),
            events,
        )
    }
}
