@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.entity.EntEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertIs

class LoadPrivacyEvaluatorTest {
    private data class Record(
        override val id: Long,
        val label: String,
    ) : EntEntity.LongId

    private data class RuleItem(val entity: Record)

    private val viewerContext = ViewerContext(Viewer.User(7L))

    @Test
    fun `evaluator preserves context correlation and fail-closed outcomes`() {
        val ruleClient = Any()
        var providerCalls = 0
        val contexts = mutableListOf<PrivacyRuleContext<Any>>()
        val seenItems = mutableListOf<RuleItem>()
        val first = PrivacyRule<Any, RuleItem> { context, item ->
            contexts += context
            seenItems += item
            when (item.entity.id) {
                1L -> PrivacyDecision.Allow
                2L -> PrivacyDecision.Deny("owner only")
                else -> PrivacyDecision.Continue
            }
        }
        val second = PrivacyRule<Any, RuleItem> { context, item ->
            contexts += context
            seenItems += item
            PrivacyDecision.Continue
        }
        val configuredRules = mutableListOf<BatchPrivacyRule<Any, RuleItem>>(first, second)
        val evaluator = loadPrivacyEvaluatorForInternalUse<Any, Record, RuleItem>(
            lifecycle = "Record LOAD privacy",
            unresolvedReason = "no load rule allowed access",
            rules = configuredRules,
            ruleClientProvider = {
                providerCalls++
                ruleClient
            },
            freshItem = { entity -> RuleItem(entity.copy()) },
        )
        configuredRules.clear()

        val evaluation = evaluator.evaluate(
            viewerContext,
            listOf(Record(1L, "one"), Record(2L, "two"), Record(3L, "three")),
        )

        assertEquals(listOf(1L), evaluation.allowedSubjects().map(Record::id))
        val denied = evaluation.deniedOutcomes()
        assertEquals(listOf(2L, 3L), denied.map { it.subject.id })
        assertEquals(listOf("owner only", "no load rule allowed access"), denied.map { it.reason })
        assertEquals(1, providerCalls)
        assertEquals(4, contexts.size)
        contexts.forEach { context ->
            assertSame(viewerContext, context.viewerContext)
            assertSame(ruleClient, context.client)
        }
        val continuedItems = seenItems.filter { it.entity.id == 3L }
        assertEquals(2, continuedItems.size)
        assertNotSame(continuedItems[0], continuedItems[1])
        assertNotSame(continuedItems[0].entity, continuedItems[1].entity)
    }

    @Test
    fun `empty and bypass batches never resolve the rule client or invoke rules`() {
        var providerCalls = 0
        var ruleCalls = 0
        val evaluator = loadPrivacyEvaluatorForInternalUse<Any, Record, RuleItem>(
            lifecycle = "Record LOAD privacy",
            unresolvedReason = "no load rule allowed access",
            rules = listOf(
                PrivacyRule<Any, RuleItem> { _, _ ->
                    ruleCalls++
                    PrivacyDecision.Deny("must not run")
                },
            ),
            ruleClientProvider = {
                providerCalls++
                Any()
            },
            freshItem = ::RuleItem,
        )

        assertEquals(0, evaluator.evaluate(viewerContext, emptyList()).size)
        assertEquals(
            listOf(Record(1L, "one"), Record(1L, "duplicate")),
            evaluator.evaluate(
                ViewerContext.privacyBypass_DANGEROUS("load phase test"),
                listOf(Record(1L, "one"), Record(1L, "duplicate")),
            ).allowedSubjects(),
        )
        assertEquals(0, providerCalls)
        assertEquals(0, ruleCalls)
    }

    @Test
    fun `rule exceptions escape unchanged`() {
        val failure = IllegalStateException("rule failed")
        val evaluator = loadPrivacyEvaluatorForInternalUse<Any, Record, RuleItem>(
            lifecycle = "Record LOAD privacy",
            unresolvedReason = "no load rule allowed access",
            rules = listOf(
                PrivacyRule<Any, RuleItem> { _, _ -> throw failure },
            ),
            ruleClientProvider = { Any() },
            freshItem = ::RuleItem,
        )

        val thrown = assertFailsWith<IllegalStateException> {
            evaluator.evaluate(viewerContext, listOf(Record(1L, "one")))
        }

        assertSame(failure, thrown)
    }
}
