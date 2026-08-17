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
                contexts.map {
                    when (it) {
                        1 -> PrivacyDecision.Allow
                        3 -> PrivacyDecision.Deny("three")
                        else -> PrivacyDecision.Continue
                    }
                }
            },
            batchPrivacyRule<Int> { contexts ->
                invocations += contexts
                contexts.map { PrivacyDecision.Deny("remaining $it") }
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
                contexts.map { PrivacyDecision.Continue }
            },
            batchPrivacyRule<Int> { contexts ->
                seen += contexts
                contexts.map { PrivacyDecision.Allow }
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
                    contexts.mapIndexed { index, _ ->
                        if (index == 0) PrivacyDecision.Allow else PrivacyDecision.Deny("second")
                    }
                },
                batchPrivacyRule<Int> { contexts ->
                    laterCalls++
                    contexts.map { PrivacyDecision.Allow }
                },
            ),
            freshContext = { it },
        )

        assertEquals(listOf(PrivacyDecision.Allow, PrivacyDecision.Deny("second")), decisions)
        assertEquals(0, laterCalls)
    }

    @Test
    fun `validation evaluation is rule-major and preserves violation order per item`() {
        val invocations = mutableListOf<List<String>>()
        val rules = listOf(
            batchValidationRule<String> { contexts ->
                invocations += contexts
                contexts.map { ValidationDecision.Invalid("first $it") }
            },
            batchValidationRule<String> { contexts ->
                invocations += contexts
                contexts.map {
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
                contexts.map { ValidationDecision.Valid }
            },
            batchValidationRule<Int> { contexts ->
                seen += contexts
                contexts.map { ValidationDecision.Valid }
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

        val privacy = batchPrivacyRule<Int> {
            privacyCalls++
            emptyList()
        }
        val validation = batchValidationRule<Int> {
            validationCalls++
            emptyList()
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
    fun `privacy and validation evaluators reject wrong decision counts`() {
        val privacyException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchPrivacyRulesForInternalUse(
                "Widget LOAD privacy",
                listOf(1, 2),
                listOf(batchPrivacyRule<Int> { listOf(PrivacyDecision.Allow) }),
            ) { it }
        }
        assertEquals("Widget LOAD privacy", privacyException.lifecycle)
        assertEquals(2, privacyException.expectedSize)
        assertEquals(1, privacyException.actualSize)

        val longPrivacyException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchPrivacyRulesForInternalUse(
                "Widget DELETE privacy",
                listOf(1),
                listOf(
                    batchPrivacyRule<Int> {
                        listOf(PrivacyDecision.Allow, PrivacyDecision.Allow)
                    },
                ),
            ) { it }
        }
        assertEquals("Widget DELETE privacy", longPrivacyException.lifecycle)
        assertEquals(1, longPrivacyException.expectedSize)
        assertEquals(2, longPrivacyException.actualSize)

        val validationException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchValidationRulesForInternalUse(
                "Widget CREATE validation",
                listOf(1),
                listOf(batchValidationRule<Int> { emptyList() }),
            ) { it }
        }
        assertEquals("Widget CREATE validation", validationException.lifecycle)
        assertEquals(1, validationException.expectedSize)
        assertEquals(0, validationException.actualSize)
    }

    @Test
    fun `privacy and validation evaluators reject null Java decisions`() {
        val privacyException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchPrivacyRulesForInternalUse(
                "Widget LOAD privacy",
                listOf("widget"),
                listOf(BatchLifecycleJavaCompatibility.NULL_PRIVACY_BATCH),
            ) { it }
        }
        assertEquals(0, privacyException.invalidDecisionIndex)
        assertEquals(1, privacyException.expectedSize)
        assertEquals(1, privacyException.actualSize)

        val validationException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchValidationRulesForInternalUse(
                "Widget CREATE validation",
                listOf("widget"),
                listOf(BatchLifecycleJavaCompatibility.NULL_VALIDATION_BATCH),
            ) { it }
        }
        assertEquals(0, validationException.invalidDecisionIndex)
        assertEquals(1, validationException.expectedSize)
        assertEquals(1, validationException.actualSize)
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
                        contexts.map { PrivacyDecision.Allow }
                    },
                ),
                freshContext = { it },
            )
        }

        assertSame(failure, thrown)
        assertEquals(0, laterCalls)
    }

    @Test
    fun `callback lists are immutable even when cast`() {
        val privacy = batchPrivacyRule<Int> { contexts ->
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (contexts as MutableList<Int>).clear()
            }
            contexts.map { PrivacyDecision.Allow }
        }
        val validation = batchValidationRule<Int> { contexts ->
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (contexts as MutableList<Int>).clear()
            }
            contexts.map { ValidationDecision.Valid }
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
