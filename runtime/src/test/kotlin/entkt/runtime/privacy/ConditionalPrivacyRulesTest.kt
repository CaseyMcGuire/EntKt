@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.TestRuleClient
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

class ConditionalPrivacyRulesTest {
    private val context = PrivacyRuleContext(ViewerContext(Viewer.User(7)), TestRuleClient())

    @Test
    fun `allowIf allows matches and continues otherwise`() {
        val rule: PrivacyRule<TestRuleClient, Int> = allowIf { context, item ->
            context.viewerContext.userIdOrNull() == item
        }

        assertEquals(PrivacyDecision.Allow, rule.run(context, 7))
        assertEquals(PrivacyDecision.Continue, rule.run(context, 8))
    }

    @Test
    fun `denyIf preserves the reason for matches and continues otherwise`() {
        val rule: PrivacyRule<TestRuleClient, Int> = denyIf("owner cannot perform this operation") { context, item ->
            context.viewerContext.userIdOrNull() == item
        }

        assertEquals(PrivacyDecision.Deny("owner cannot perform this operation"), rule.run(context, 7))
        assertEquals(PrivacyDecision.Continue, rule.run(context, 8))
    }

    @Test
    fun `predicates run only on execution and receive the exact context and item`() {
        val item = Any()
        var calls = 0
        val predicate: (PrivacyRuleContext<TestRuleClient>, Any) -> Boolean = { receivedContext, receivedItem ->
            calls++
            assertSame(context, receivedContext)
            assertSame(item, receivedItem)
            true
        }
        val rules = listOf(allowIf(predicate), denyIf("denied", predicate))

        assertEquals(0, calls)
        rules.forEachIndexed { index, rule ->
            rule.run(context, item)
            assertEquals(index + 1, calls)
        }
    }

    @Test
    fun `batch execution visits duplicates in order and does not visit an empty batch`() {
        val seen = mutableListOf<Int>()
        val predicate: (PrivacyRuleContext<TestRuleClient>, Int) -> Boolean = { _, item ->
            seen += item
            item == 7
        }
        val rules = listOf(allowIf(predicate), denyIf("denied", predicate))
        val matchedDecisions = listOf(PrivacyDecision.Allow, PrivacyDecision.Deny("denied"))
        val items = listOf(7, 8, 7)

        rules.zip(matchedDecisions).forEach { (rule, matched) ->
            seen.clear()
            assertEquals(
                listOf(matched, PrivacyDecision.Continue, matched),
                rule.runBatch(context, RuleBatch.from(items)),
            )
            assertEquals(items, seen)

            seen.clear()
            assertEquals(emptyList(), rule.runBatch(context, RuleBatch.from(emptyList())))
            assertEquals(emptyList(), seen)
        }
    }

    @Test
    fun `only continued items reach later rules and unresolved items remain fail closed`() {
        val reachedAllow = mutableListOf<Int>()
        val rules = listOf<BatchPrivacyRule<TestRuleClient, Int>>(
            denyIf("negative") { _, item -> item < 0 },
            allowIf { _, item ->
                reachedAllow += item
                item % 2 == 0
            },
        )
        val items = listOf(-2, 0, 1)
        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "test privacy",
            items = items,
            rules = rules,
            context = context,
            freshItem = { it },
        )

        assertEquals(
            listOf(PrivacyDecision.Deny("negative"), PrivacyDecision.Allow, PrivacyDecision.Continue),
            decisions,
        )
        assertEquals(listOf(0, 1), reachedAllow)

        val evaluation = correlatePrivacyEvaluationForInternalUse(
            lifecycle = "test privacy",
            subjects = items,
            decisions = decisions,
            unresolvedReason = "no rule allowed access",
        )
        assertEquals(listOf(0), evaluation.allowedSubjects())
        assertEquals(listOf("negative", "no rule allowed access"), evaluation.deniedOutcomes().map { it.reason })
    }

    @Test
    fun `an earlier allow skips a later denial`() {
        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "test privacy",
            items = listOf(7),
            rules = listOf(
                allowIf { _, _ -> true },
                denyIf("unreachable") { _, _ -> error("must not run after Allow") },
            ),
            context = context,
            freshItem = { it },
        )

        assertEquals(listOf(PrivacyDecision.Allow), decisions)
    }

    @Test
    fun `predicate exceptions cancellation and errors propagate unchanged`() {
        val failures = listOf(
            IllegalStateException("predicate failed"),
            CancellationException("cancelled"),
            AssertionError("fatal"),
        )
        for (failure in failures) {
            val rules = listOf<PrivacyRule<TestRuleClient, Int>>(
                allowIf { _, _ -> throw failure },
                denyIf("not a denial") { _, _ -> throw failure },
            )
            for (rule in rules) {
                assertSame(failure, assertFails { rule.run(context, 7) })
                assertSame(failure, assertFails { rule.runBatch(context, RuleBatch.from(listOf(7))) })
            }
        }
    }
}
