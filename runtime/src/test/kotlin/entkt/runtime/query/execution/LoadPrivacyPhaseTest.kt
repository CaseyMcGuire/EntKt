@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRule
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.EdgeMapping
import entkt.runtime.result.EntityKey
import entkt.runtime.result.PrivacyDenial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LoadPrivacyPhaseTest {
    private data class Record(
        override val id: Long,
        val label: String,
    ) : EntEntity.LongId

    private data class RuleItem(val entity: Record)

    private object RecordMapping : EntityMapping<Record> {
        override val entityName = "Record"
        override val clientName = "records"
        override val entityClass = Record::class
        override val table = "records"

        override fun decode(row: Map<String, Any?>): Record =
            Record(row.getValue("id") as Long, row.getValue("label") as String)

        override fun edgeByStorageName(storageName: String): EdgeMapping<Record, *>? = null
    }

    private val viewerContext = ViewerContext(Viewer.User(7L))

    @Test
    fun `phase preserves context correlation and fail-closed outcomes`() {
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
        val phase = loadPrivacyPhaseForInternalUse(
            entity = RecordMapping,
            rules = configuredRules,
            ruleClientProvider = {
                providerCalls++
                ruleClient
            },
            freshItem = { entity -> RuleItem(entity.copy()) },
        )
        configuredRules.clear()

        val denials = phase.denials(
            viewerContext,
            listOf(Record(1L, "one"), Record(2L, "two"), Record(3L, "three")),
        )

        assertEquals(
            listOf(
                null,
                PrivacyDenial("Record", EntityKey("id", 2L), "owner only"),
                PrivacyDenial("Record", EntityKey("id", 3L), "no load rule allowed access"),
            ),
            denials,
        )
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
        val phase = loadPrivacyPhaseForInternalUse(
            entity = RecordMapping,
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

        assertEquals(emptyList(), phase.denials(viewerContext, emptyList()))
        assertEquals(
            listOf(null, null),
            phase.denials(
                ViewerContext.privacyBypass_DANGEROUS("load phase test"),
                listOf(Record(1L, "one"), Record(1L, "duplicate")),
            ),
        )
        assertEquals(0, providerCalls)
        assertEquals(0, ruleCalls)
    }

    @Test
    fun `rule exceptions escape unchanged`() {
        val failure = IllegalStateException("rule failed")
        val phase = loadPrivacyPhaseForInternalUse(
            entity = RecordMapping,
            rules = listOf(
                PrivacyRule<Any, RuleItem> { _, _ -> throw failure },
            ),
            ruleClientProvider = { Any() },
            freshItem = ::RuleItem,
        )

        val thrown = assertFailsWith<IllegalStateException> {
            phase.denials(viewerContext, listOf(Record(1L, "one")))
        }

        assertSame(failure, thrown)
    }
}
