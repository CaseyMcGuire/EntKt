@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime

import entkt.runtime.hook.ActionHook
import entkt.runtime.hook.batchActionHook
import entkt.runtime.hook.HookRunner
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.privacy.evaluateBatchPrivacyRulesForInternalUse
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.ValidationRuleContext
import entkt.runtime.validation.batchValidationRule
import entkt.runtime.validation.evaluateBatchValidationRulesForInternalUse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BatchLifecycleEvaluationTest {

    private val viewerContext = PrivacyRuleContext(
        viewerContext = ViewerContext(Viewer.User(7)),
        client = "privacy-client",
    )
    private val validationContext = ValidationRuleContext(client = "validation-client")

    @Test
    fun `privacy evaluation filters finalized items and retains original positions`() {
        val invocations = mutableListOf<List<Int>>()
        val rules = listOf(
            batchPrivacyRule<String, Int> { _, batch ->
                invocations += batch
                batch.decideEach {
                    when (it) {
                        1 -> PrivacyDecision.Allow
                        3 -> PrivacyDecision.Deny("three")
                        else -> PrivacyDecision.Continue
                    }
                }
            },
            batchPrivacyRule<String, Int> { _, batch ->
                invocations += batch
                batch.decideEach { PrivacyDecision.Deny("remaining $it") }
            },
        )

        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "Widget LOAD privacy",
            items = listOf(1, 2, 3, 4),
            rules = rules,
            context = viewerContext,
            freshItem = { it },
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
    fun `privacy evaluation reuses shared context and creates fresh items for every reached rule`() {
        var nextSnapshot = 0
        val seen = mutableListOf<List<Int>>()
        val seenContexts = mutableListOf<PrivacyRuleContext<String>>()
        val rules = listOf(
            batchPrivacyRule<String, Int> { context, batch ->
                seenContexts += context
                seen += batch
                batch.decideEach { PrivacyDecision.Continue }
            },
            batchPrivacyRule<String, Int> { context, batch ->
                seenContexts += context
                seen += batch
                batch.decideEach { PrivacyDecision.Allow }
            },
        )

        evaluateBatchPrivacyRulesForInternalUse(
            "Widget LOAD privacy",
            listOf(Unit),
            rules,
            viewerContext,
        ) { ++nextSnapshot }

        assertEquals(listOf(listOf(1), listOf(2)), seen)
        assertSame(viewerContext, seenContexts[0])
        assertSame(viewerContext, seenContexts[1])
    }

    @Test
    fun `privacy evaluation correlates duplicate values by position and skips later rules once resolved`() {
        var laterCalls = 0
        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "Widget LOAD privacy",
            items = listOf(7, 7),
            rules = listOf(
                batchPrivacyRule<String, Int> { _, batch ->
                    batch.decideEachIndexed { index, _ ->
                        if (index == 0) PrivacyDecision.Allow else PrivacyDecision.Deny("second")
                    }
                },
                batchPrivacyRule<String, Int> { _, batch ->
                    laterCalls++
                    batch.decideEach { PrivacyDecision.Allow }
                },
            ),
            context = viewerContext,
            freshItem = { it },
        )

        assertEquals(listOf(PrivacyDecision.Allow, PrivacyDecision.Deny("second")), decisions)
        assertEquals(0, laterCalls)
    }

    @Test
    fun `privacy decideEachIndexed uses the current active batch index`() {
        val laterBatches = mutableListOf<List<String>>()
        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "Widget LOAD privacy",
            items = listOf("same", "resolved", "same"),
            rules = listOf(
                batchPrivacyRule<String, String> { _, batch ->
                    batch.decideEach {
                        if (it == "resolved") PrivacyDecision.Allow else PrivacyDecision.Continue
                    }
                },
                batchPrivacyRule<String, String> { _, batch ->
                    laterBatches += batch
                    batch.decideEachIndexed { index, _ -> PrivacyDecision.Deny("active-$index") }
                },
            ),
            context = viewerContext,
            freshItem = { it },
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
            batchValidationRule<String, String> { _, batch ->
                invocations += batch
                batch.decideEach { ValidationDecision.Invalid("first $it") }
            },
            batchValidationRule<String, String> { _, batch ->
                invocations += batch
                batch.decideEach {
                    if (it == "a") ValidationDecision.Valid else ValidationDecision.Invalid("second $it")
                }
            },
        )

        val violations = evaluateBatchValidationRulesForInternalUse(
            lifecycle = "Widget CREATE validation",
            items = listOf("a", "b"),
            rules = rules,
            context = validationContext,
            freshItem = { it },
        )

        assertEquals(listOf(listOf("a", "b"), listOf("a", "b")), invocations)
        assertEquals(listOf("first a"), violations[0].map { it.message })
        assertEquals(listOf("first b", "second b"), violations[1].map { it.message })
    }

    @Test
    fun `validation evaluation reuses shared context and creates fresh items for each rule`() {
        var nextSnapshot = 0
        val seen = mutableListOf<List<Int>>()
        val seenContexts = mutableListOf<ValidationRuleContext<String>>()
        val rules = listOf(
            batchValidationRule<String, Int> { context, batch ->
                seenContexts += context
                seen += batch
                batch.decideEach { ValidationDecision.Valid }
            },
            batchValidationRule<String, Int> { context, batch ->
                seenContexts += context
                seen += batch
                batch.decideEach { ValidationDecision.Valid }
            },
        )

        evaluateBatchValidationRulesForInternalUse(
            "Widget CREATE validation",
            listOf(Unit, Unit),
            rules,
            validationContext,
        ) { ++nextSnapshot }

        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), seen)
        assertSame(validationContext, seenContexts[0])
        assertSame(validationContext, seenContexts[1])
    }

    @Test
    fun `no rules preserve one unresolved result slot per item`() {
        assertEquals(
            listOf(PrivacyDecision.Continue, PrivacyDecision.Continue),
            evaluateBatchPrivacyRulesForInternalUse<Int, String, Int>(
                "Widget LOAD privacy",
                listOf(1, 2),
                emptyList(),
                viewerContext,
            ) { it },
        )
        assertEquals(
            listOf(emptyList(), emptyList()),
            evaluateBatchValidationRulesForInternalUse<Int, String, Int>(
                "Widget CREATE validation",
                listOf(1, 2),
                emptyList(),
                validationContext,
            ) { it },
        )
    }

    @Test
    fun `callbacks are skipped for empty inputs`() {
        var privacyCalls = 0
        var validationCalls = 0
        var hookCalls = 0

        val privacy = batchPrivacyRule<String, Int> { _, batch ->
            privacyCalls++
            batch.decideEach { PrivacyDecision.Allow }
        }
        val validation = batchValidationRule<String, Int> { _, batch ->
            validationCalls++
            batch.decideEach { ValidationDecision.Valid }
        }
        val hook = batchActionHook<Int> { hookCalls++ }

        assertEquals(
            emptyList(),
            evaluateBatchPrivacyRulesForInternalUse(
                "privacy",
                emptyList<Int>(),
                listOf(privacy),
                viewerContext,
            ) { it },
        )
        assertEquals(
            emptyList(),
            evaluateBatchValidationRulesForInternalUse(
                "validation",
                emptyList<Int>(),
                listOf(validation),
                validationContext,
            ) { it },
        )
        HookRunner(listOf(hook)).run(emptyList())

        assertEquals(0, privacyCalls)
        assertEquals(0, validationCalls)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `privacy and validation evaluators reject decisions from another batch`() {
        var priorPrivacyDecisions: entkt.runtime.rule.RuleDecisions<PrivacyDecision>? = null
        val privacyDelegate = batchPrivacyRule<String, Int> { _, batch ->
            priorPrivacyDecisions ?: batch.decideEach { PrivacyDecision.Allow }.also {
                priorPrivacyDecisions = it
            }
        }
        val privacy = batchPrivacyRule<String, Int> { context, batch ->
            privacyDelegate.runBatch(context, batch).mapDecisions { it }
        }
        evaluateBatchPrivacyRulesForInternalUse(
            "Widget LOAD privacy",
            listOf(1),
            listOf(privacy),
            viewerContext,
        ) { it }
        val privacyException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchPrivacyRulesForInternalUse(
                "Widget LOAD privacy",
                listOf(2),
                listOf(privacy),
                viewerContext,
            ) { it }
        }
        assertEquals(true, privacyException.foreignBatchResult)

        var priorValidationDecisions: entkt.runtime.rule.RuleDecisions<ValidationDecision>? = null
        val validation = batchValidationRule<String, Int> { _, batch ->
            priorValidationDecisions ?: batch.decideEach { ValidationDecision.Valid }.also {
                priorValidationDecisions = it
            }
        }
        evaluateBatchValidationRulesForInternalUse(
            "Widget CREATE validation",
            listOf(1),
            listOf(validation),
            validationContext,
        ) { it }
        val validationException = assertFailsWith<EntBatchRuleContractException> {
            evaluateBatchValidationRulesForInternalUse(
                "Widget CREATE validation",
                listOf(2),
                listOf(validation),
                validationContext,
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
                BatchLifecycleJavaCompatibility.VIEWER_CONTEXT,
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
                BatchLifecycleJavaCompatibility.VALIDATION_CONTEXT,
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
                BatchLifecycleJavaCompatibility.VIEWER_CONTEXT,
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
                BatchLifecycleJavaCompatibility.VALIDATION_CONTEXT,
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
                    batchPrivacyRule<String, Int> { _, _ -> throw failure },
                    batchPrivacyRule<String, Int> { _, batch ->
                        laterCalls++
                        batch.decideEach { PrivacyDecision.Allow }
                    },
                ),
                context = viewerContext,
                freshItem = { it },
            )
        }

        assertSame(failure, thrown)
        assertEquals(0, laterCalls)
    }

    @Test
    fun `callback batches are immutable even when cast`() {
        val privacy = batchPrivacyRule<String, Int> { _, batch ->
            assertFailsWith<ClassCastException> {
                @Suppress("UNCHECKED_CAST")
                (batch as Any as MutableList<Int>).clear()
            }
            batch.decideEach { PrivacyDecision.Allow }
        }
        val validation = batchValidationRule<String, Int> { _, batch ->
            assertFailsWith<ClassCastException> {
                @Suppress("UNCHECKED_CAST")
                (batch as Any as MutableList<Int>).clear()
            }
            batch.decideEach { ValidationDecision.Valid }
        }
        val hook = batchActionHook<Int> { elements ->
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (elements as MutableList<Int>).clear()
            }
        }

        evaluateBatchPrivacyRulesForInternalUse(
            "privacy",
            listOf(1),
            listOf(privacy),
            viewerContext,
        ) { it }
        evaluateBatchValidationRulesForInternalUse(
            "validation",
            listOf(1),
            listOf(validation),
            validationContext,
        ) { it }
        HookRunner(listOf(hook)).run(listOf(1))
    }

    @Test
    fun `hooks run in registration order and scalar hooks retain element order`() {
        val events = mutableListOf<String>()
        val hooks = listOf(
            ActionHook<Int> { events += "scalar $it" },
            batchActionHook<Int> { elements -> events += "batch ${elements.joinToString()}" },
            ActionHook<Int> { events += "last $it" },
        )

        HookRunner(hooks).run(listOf(2, 1))

        assertEquals(
            listOf("scalar 2", "scalar 1", "batch 2, 1", "last 2", "last 1"),
            events,
        )
    }
}
