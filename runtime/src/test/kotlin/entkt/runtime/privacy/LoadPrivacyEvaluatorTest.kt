@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.query.EdgeMapping
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.rule.ruleBatchForInternalUse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LoadPrivacyEvaluatorTest {
    private data class Record(
        override val id: Long,
        val label: String,
    ) : EntEntity.LongId

    private object RecordDescriptor : EntityDescriptor<Record, Long> {
        override val entityName = "StoredRecord"
        override val clientName = "records"
        override val entityClass = Record::class
        override val schema = EntitySchema(
            table = "records",
            idColumn = "id",
            idStrategy = IdStrategy.EXPLICIT,
            columns = emptyList(),
            edges = emptyMap(),
        )
        override val edgesByStorageName: Map<String, EdgeMapping<Record, *>> = emptyMap()

        override fun decode(row: Map<String, Any?>): Record =
            Record(row.getValue("id") as Long, row.getValue("label") as String)
    }

    private data class RuleItem(val entity: Record)

    private val viewerContext = ViewerContext(Viewer.User(7L))
    private val ruleContext = PrivacyRuleContext(viewerContext, Any())

    @Test
    fun `evaluator preserves context correlation and fail-closed outcomes`() {
        val ruleClient = Any()
        val ruleContext = PrivacyRuleContext(viewerContext, ruleClient)
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
        val evaluator = LoadPrivacyEvaluator<Any, Record, RuleItem>(
            entity = RecordDescriptor,
            rules = configuredRules,
            freshItem = { entity -> RuleItem(entity.copy()) },
        )
        configuredRules.clear()

        val evaluation = evaluator.evaluate(
            ruleContext,
            listOf(Record(1L, "one"), Record(2L, "two"), Record(3L, "three")),
        )

        assertEquals(listOf(1L), evaluation.allowedSubjects().map(Record::id))
        val denied = evaluation.deniedOutcomes()
        assertEquals(listOf(2L, 3L), denied.map { it.subject.id })
        assertEquals(listOf("owner only", "no load rule allowed access"), denied.map { it.reason })
        assertEquals(4, contexts.size)
        contexts.forEach { context ->
            assertSame(ruleContext, context)
            assertSame(viewerContext, context.viewerContext)
            assertSame(ruleClient, context.client)
        }
        val continuedItems = seenItems.filter { it.entity.id == 3L }
        assertEquals(2, continuedItems.size)
        assertNotSame(continuedItems[0], continuedItems[1])
        assertNotSame(continuedItems[0].entity, continuedItems[1].entity)
    }

    @Test
    fun `empty and bypass batches never invoke rules or convert items`() {
        var ruleCalls = 0
        var itemConversions = 0
        val evaluator = LoadPrivacyEvaluator<Any, Record, RuleItem>(
            entity = RecordDescriptor,
            rules = listOf(
                PrivacyRule<Any, RuleItem> { _, _ ->
                    ruleCalls++
                    PrivacyDecision.Deny("must not run")
                },
            ),
            freshItem = {
                itemConversions++
                RuleItem(it)
            },
        )

        assertEquals(0, evaluator.evaluate(ruleContext, emptyList()).size)
        assertEquals(
            listOf(Record(1L, "one"), Record(1L, "duplicate")),
            evaluator.evaluate(
                PrivacyRuleContext(ViewerContext.privacyBypass_DANGEROUS("load phase test"), Any()),
                listOf(Record(1L, "one"), Record(1L, "duplicate")),
            ).allowedSubjects(),
        )
        assertEquals(0, itemConversions)
        assertEquals(0, ruleCalls)
    }

    @Test
    fun `rule exceptions escape unchanged`() {
        val failure = IllegalStateException("rule failed")
        val evaluator = LoadPrivacyEvaluator<Any, Record, RuleItem>(
            entity = RecordDescriptor,
            rules = listOf(
                PrivacyRule<Any, RuleItem> { _, _ -> throw failure },
            ),
            freshItem = ::RuleItem,
        )

        val thrown = assertFailsWith<IllegalStateException> {
            evaluator.evaluate(ruleContext, listOf(Record(1L, "one")))
        }

        assertSame(failure, thrown)
    }

    @Test
    fun `no rules denies every entity with the built-in LOAD reason`() {
        val evaluator = LoadPrivacyEvaluator<Any, Record, RuleItem>(
            entity = RecordDescriptor,
            rules = emptyList(),
            freshItem = ::RuleItem,
        )
        val entities = listOf(Record(1L, "one"), Record(2L, "two"))

        val evaluation = evaluator.evaluate(ruleContext, entities)

        assertEquals(emptyList(), evaluation.allowedSubjects())
        assertEquals(entities, evaluation.deniedOutcomes().map { it.subject })
        assertEquals(
            listOf("no load rule allowed access", "no load rule allowed access"),
            evaluation.deniedOutcomes().map { it.reason },
        )
    }

    @Test
    fun `rule contract failures identify the descriptor and LOAD operation`() {
        val foreignDecisions = ruleBatchForInternalUse(listOf(RuleItem(Record(1L, "one"))))
            .decideEach { PrivacyDecision.Allow }
        val evaluator = LoadPrivacyEvaluator<Any, Record, RuleItem>(
            entity = RecordDescriptor,
            rules = listOf(batchPrivacyRule { _, _ -> foreignDecisions }),
            freshItem = ::RuleItem,
        )

        val failure = assertFailsWith<EntBatchRuleContractException> {
            evaluator.evaluate(ruleContext, listOf(Record(1L, "one")))
        }

        assertEquals("StoredRecord LOAD privacy", failure.lifecycle)
        assertEquals(true, failure.foreignBatchResult)
        assertEquals(
            "Batch rule contract violation for StoredRecord LOAD privacy: decisions belong to a different rule batch",
            failure.message,
        )
    }

    @Test
    fun `each evaluation uses the supplied context without retaining a client or viewer`() {
        val contexts = mutableListOf<PrivacyRuleContext<Any>>()
        val evaluator = LoadPrivacyEvaluator<Any, Record, RuleItem>(
            entity = RecordDescriptor,
            rules = listOf(
                PrivacyRule<Any, RuleItem> { context, _ ->
                    contexts += context
                    PrivacyDecision.Allow
                },
            ),
            freshItem = ::RuleItem,
        )
        val firstContext = PrivacyRuleContext(ViewerContext(Viewer.User(1L)), Any())
        val secondContext = PrivacyRuleContext(ViewerContext(Viewer.User(2L)), Any())
        val entities = listOf(Record(1L, "one"))

        assertEquals(entities, evaluator.evaluate(firstContext, entities).allowedSubjects())
        assertEquals(entities, evaluator.evaluate(secondContext, entities).allowedSubjects())

        assertEquals(2, contexts.size)
        assertSame(firstContext, contexts[0])
        assertSame(secondContext, contexts[1])
    }
}
