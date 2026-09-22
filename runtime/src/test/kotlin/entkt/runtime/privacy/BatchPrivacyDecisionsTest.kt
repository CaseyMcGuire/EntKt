@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions
import entkt.runtime.rule.TestRuleClient
import entkt.runtime.rule.decisionsForInternalUse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BatchPrivacyDecisionsTest {
    private val context = PrivacyRuleContext(ViewerContext(Viewer.User(7L)), TestRuleClient())

    @Test
    fun `allowAll returns an allow for every occurrence including null items`() {
        val batch = RuleBatch.from(listOf(7, null, 7))

        val decisions: RuleDecisions<PrivacyDecision> = batch.allowAll()

        assertEquals(batch.decideEach { PrivacyDecision.Allow }, decisions)
        assertEquals(3, decisions.size)
        assertEquals(decisions, batch.decisionsForInternalUse("test privacy", decisions))
    }

    @Test
    fun `denyAll preserves the exact reason for every occurrence including null items`() {
        val batch = RuleBatch.from(listOf(7, null, 7))
        val reason = "Access denied: authentication required"

        val decisions: RuleDecisions<PrivacyDecision> = batch.denyAll(reason)

        assertEquals(batch.decideEach { PrivacyDecision.Deny(reason) }, decisions)
        assertEquals(3, decisions.size)
        assertEquals(decisions, batch.decisionsForInternalUse("test privacy", decisions))
    }

    @Test
    fun `both helpers return valid empty decisions for an empty batch`() {
        val batch = RuleBatch.from(emptyList<String>())

        for (decisions in listOf(batch.allowAll(), batch.denyAll("denied"))) {
            assertTrue(decisions.isEmpty())
            assertTrue(batch.decisionsForInternalUse("test privacy", decisions).isEmpty())
        }
    }

    @Test
    fun `both helpers reject reuse with another equal batch including empty batches`() {
        for (items in listOf(listOf(7, 7), emptyList())) {
            val source = RuleBatch.from(items)
            val other = RuleBatch.from(items)

            for (decisions in listOf(source.allowAll(), source.denyAll("denied"))) {
                val failure = assertFailsWith<EntBatchRuleContractException> {
                    other.decisionsForInternalUse("test privacy", decisions)
                }
                assertTrue(failure.foreignBatchResult)
                assertEquals(items.size, failure.expectedSize)
                assertEquals(items.size, failure.actualSize)
            }
        }
    }

    @Test
    fun `allowAll finalizes only unresolved items without overriding an earlier denial`() {
        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "test privacy",
            items = listOf(0, 7, 7),
            rules = listOf<BatchPrivacyRule<TestRuleClient, Int>>(
                denyIf("already denied") { _, item -> item == 0 },
                batchPrivacyRule { _, batch ->
                    assertEquals(listOf(7, 7), batch)
                    batch.allowAll()
                },
                batchPrivacyRule { _, _ -> error("Finalized items must not reach later rules") },
            ),
            context = context,
            freshItem = { it },
        )

        assertEquals(
            listOf(PrivacyDecision.Deny("already denied"), PrivacyDecision.Allow, PrivacyDecision.Allow),
            decisions,
        )
    }

    @Test
    fun `denyAll finalizes only unresolved items without overriding an earlier allowance`() {
        val decisions = evaluateBatchPrivacyRulesForInternalUse(
            lifecycle = "test privacy",
            items = listOf(0, 7, 7),
            rules = listOf<BatchPrivacyRule<TestRuleClient, Int>>(
                allowIf { _, item -> item == 0 },
                batchPrivacyRule { _, batch ->
                    assertEquals(listOf(7, 7), batch)
                    batch.denyAll("not permitted")
                },
                batchPrivacyRule { _, _ -> error("Finalized items must not reach later rules") },
            ),
            context = context,
            freshItem = { it },
        )

        assertEquals(
            listOf(PrivacyDecision.Allow, PrivacyDecision.Deny("not permitted"), PrivacyDecision.Deny("not permitted")),
            decisions,
        )
    }
}
