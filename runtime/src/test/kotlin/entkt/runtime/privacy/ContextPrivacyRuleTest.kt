@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.EntRuleClient
import entkt.runtime.rule.TestRuleClient
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

class ContextPrivacyRuleTest {
    private val context = PrivacyRuleContext(ViewerContext(Viewer.User(7)), TestRuleClient())

    @Test
    fun `one rule accepts different item types without capturing a viewer or client`() {
        val seen = mutableListOf<PrivacyRuleContext<EntRuleClient>>()
        val shared = ContextPrivacyRule<EntRuleClient> { received ->
            seen += received
            if (received.viewerContext.viewer is Viewer.Anonymous) {
                PrivacyDecision.Deny("authentication required")
            } else {
                PrivacyDecision.Allow
            }
        }
        val numbers: PrivacyRule<TestRuleClient, Int> = shared.asPrivacyRuleForInternalUse()
        val strings: PrivacyRule<TestRuleClient, String> = shared.asPrivacyRuleForInternalUse()
        val anonymous = PrivacyRuleContext(ViewerContext(Viewer.Anonymous), TestRuleClient())

        assertEquals(0, seen.size, "registration must not evaluate or cache a decision")
        assertEquals(PrivacyDecision.Allow, numbers.run(context, 7))
        assertEquals(PrivacyDecision.Deny("authentication required"), strings.run(anonymous, "ignored"))
        assertSame(context, seen[0])
        assertSame(anonymous, seen[1])
        assertSame(context.client, seen[0].client)
        assertSame(anonymous.client, seen[1].client)
    }

    @Test
    fun `context rules share registration order and per-item short circuiting with scalar and batch rules`() {
        var contextCalls = 0
        val reachedScalar = mutableListOf<Int>()
        val shared = ContextPrivacyRule<TestRuleClient> { received ->
            assertSame(context, received)
            when (contextCalls++) {
                0 -> PrivacyDecision.Continue
                1 -> PrivacyDecision.Allow
                else -> PrivacyDecision.Deny("context denied")
            }
        }
        val rules = listOf<BatchPrivacyRule<TestRuleClient, Int>>(
            batchPrivacyRule { _, batch ->
                batch.decideEach { item ->
                    if (item < 0) PrivacyDecision.Deny("negative") else PrivacyDecision.Continue
                }
            },
            shared.asPrivacyRuleForInternalUse(),
            allowIf { _, item ->
                reachedScalar += item
                true
            },
        )

        val decisions = evaluate(listOf(-1, 0, 1, 2), rules)

        assertEquals(
            listOf(
                PrivacyDecision.Deny("negative"),
                PrivacyDecision.Allow,
                PrivacyDecision.Allow,
                PrivacyDecision.Deny("context denied"),
            ),
            decisions,
        )
        assertEquals(3, contextCalls)
        assertEquals(listOf(0), reachedScalar)
    }

    @Test
    fun `duplicate items each invoke the context rule without caching decisions`() {
        var calls = 0
        val rule = ContextPrivacyRule<TestRuleClient> {
            calls++
            if (calls == 2) PrivacyDecision.Deny("changed permission") else PrivacyDecision.Allow
        }

        val decisions = evaluate(listOf(7, 7, 7), listOf(rule.asPrivacyRuleForInternalUse()))

        assertEquals(3, calls)
        assertEquals(
            listOf(PrivacyDecision.Allow, PrivacyDecision.Deny("changed permission"), PrivacyDecision.Allow),
            decisions,
        )
    }

    @Test
    fun `empty batches and earlier decisive rules do not invoke the context rule`() {
        val unreachable = ContextPrivacyRule<TestRuleClient> { error("must not run") }
        val rules = listOf<BatchPrivacyRule<TestRuleClient, Int>>(
            denyIf("denied") { _, item -> item < 0 },
            allowIf { _, _ -> true },
            unreachable.asPrivacyRuleForInternalUse(),
        )

        assertEquals(emptyList(), evaluate(emptyList(), rules))
        assertEquals(listOf(PrivacyDecision.Deny("denied"), PrivacyDecision.Allow), evaluate(listOf(-1, 7), rules))
    }

    @Test
    fun `a context rule that continues does not grant access`() {
        val rule = ContextPrivacyRule<TestRuleClient> { PrivacyDecision.Continue }
        val items = listOf(7)
        val evaluation = correlatePrivacyEvaluationForInternalUse(
            lifecycle = "test privacy",
            subjects = items,
            decisions = evaluate(items, listOf(rule.asPrivacyRuleForInternalUse())),
            unresolvedReason = "no rule allowed access",
        )

        assertEquals(emptyList(), evaluation.allowedSubjects())
        assertEquals("no rule allowed access", evaluation.deniedOutcomes().single().reason)
    }

    @Test
    fun `exceptions cancellation and fatal errors propagate unchanged`() {
        for (failure in listOf(IllegalStateException("failed"), CancellationException("cancelled"), AssertionError("fatal"))) {
            val rule = ContextPrivacyRule<TestRuleClient> { throw failure }

            assertSame(failure, assertFails { rule.run(context) })
            assertSame(failure, assertFails { evaluate(listOf(7), listOf(rule.asPrivacyRuleForInternalUse())) })
        }
    }

    private fun evaluate(
        items: List<Int>,
        rules: List<BatchPrivacyRule<TestRuleClient, Int>>,
    ): List<PrivacyDecision> = evaluateBatchPrivacyRulesForInternalUse(
        lifecycle = "test privacy",
        items = items,
        rules = rules,
        context = context,
        freshItem = { it },
    )
}
