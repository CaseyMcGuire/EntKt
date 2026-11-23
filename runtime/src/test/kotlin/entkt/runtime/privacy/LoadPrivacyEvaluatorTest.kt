@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.query.EdgeMapping
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.TestRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private val viewerContext = ViewerContext.AsViewer(TestViewer.User(7L))
    private val ruleClient = TestRuleClient()

    @Test
    fun `evaluator preserves context correlation and fail-closed outcomes`() {
        val contexts = mutableListOf<PrivacyRuleContext<TestViewer, TestRuleClient>>()
        val seenItems = mutableListOf<Record>()
        val first = PrivacyRule<TestViewer, TestRuleClient, Record> { context, item ->
            contexts += context
            seenItems += item
            when (item.id) {
                1L -> PrivacyDecision.Allow
                2L -> PrivacyDecision.Deny("owner only")
                else -> PrivacyDecision.Continue
            }
        }
        val second = PrivacyRule<TestViewer, TestRuleClient, Record> { context, item ->
            contexts += context
            seenItems += item
            PrivacyDecision.Continue
        }
        val evaluator = loadEvaluator<TestRuleClient, Record>(
            entityName = RecordDescriptor.entityName,
            rules = listOf(first, second),
        )

        val entities = listOf(Record(1L, "one"), Record(2L, "two"), Record(3L, "three"))
        val evaluation = evaluator.evaluate(viewerContext, ruleClient, entities)

        assertEquals(listOf(1L), evaluation.allowedSubjects().map(Record::id))
        assertSame(entities[0], evaluation.allowedSubjects().single())
        val denied = evaluation.deniedOutcomes()
        assertEquals(listOf(2L, 3L), denied.map { it.subject.id })
        assertEquals(listOf("owner only", "no load rule allowed access"), denied.map { it.reason })
        assertEquals(4, contexts.size)
        contexts.forEach { context ->
            assertSame(contexts.first(), context)
            assertSame(viewerContext, context.viewerContext)
            assertSame(ruleClient, context.client)
        }
        val continuedItems = seenItems.filter { it.id == 3L }
        assertEquals(2, continuedItems.size)
        assertSame(entities[2], continuedItems[0])
        assertSame(entities[2], continuedItems[1])
    }

    @Test
    fun `duplicate entity instances keep independent correlated decisions`() {
        val entity = Record(1L, "shared")
        val evaluator = loadEvaluator<TestRuleClient, Record>(
            entityName = RecordDescriptor.entityName,
            rules = listOf(batchPrivacyRule { _, batch ->
                batch.decideEachIndexed { index, item ->
                    assertSame(entity, item)
                    if (index == 0) PrivacyDecision.Allow
                    else PrivacyDecision.Deny("second occurrence")
                }
            }),
        )

        val evaluation = evaluator.evaluate(viewerContext, ruleClient, listOf(entity, entity))

        assertEquals(2, evaluation.size)
        assertSame(entity, evaluation.allowedSubjects().single())
        val denied = evaluation.deniedOutcomes().single()
        assertSame(entity, denied.subject)
        assertEquals("second occurrence", denied.reason)
    }

    @Test
    fun `empty and bypass batches never invoke rules`() {
        var ruleCalls = 0
        val evaluator = loadEvaluator<TestRuleClient, Record>(
            entityName = RecordDescriptor.entityName,
            rules = listOf(
                PrivacyRule<TestViewer, TestRuleClient, Record> { _, _ ->
                    ruleCalls++
                    PrivacyDecision.Deny("must not run")
                },
            ),
        )

        assertEquals(0, evaluator.evaluate(viewerContext, ruleClient, emptyList()).size)
        assertEquals(
            listOf(Record(1L, "one"), Record(1L, "duplicate")),
            evaluator.evaluate(
                ViewerContext.privacyBypass_DANGEROUS("load phase test"),
                ruleClient,
                listOf(Record(1L, "one"), Record(1L, "duplicate")),
            ).allowedSubjects(),
        )
        assertEquals(0, ruleCalls)
    }

    @Test
    fun `rule exceptions escape unchanged`() {
        val failure = IllegalStateException("rule failed")
        val evaluator = loadEvaluator<TestRuleClient, Record>(
            entityName = RecordDescriptor.entityName,
            rules = listOf(
                PrivacyRule<TestViewer, TestRuleClient, Record> { _, _ -> throw failure },
            ),
        )

        val thrown = assertFailsWith<IllegalStateException> {
            evaluator.evaluate(viewerContext, ruleClient, listOf(Record(1L, "one")))
        }

        assertSame(failure, thrown)
    }

    @Test
    fun `no rules denies every entity with the built-in LOAD reason`() {
        val evaluator = loadEvaluator<TestRuleClient, Record>(
            entityName = RecordDescriptor.entityName,
            rules = emptyList(),
        )
        val entities = listOf(Record(1L, "one"), Record(2L, "two"))

        val evaluation = evaluator.evaluate(viewerContext, ruleClient, entities)

        assertEquals(emptyList(), evaluation.allowedSubjects())
        assertEquals(entities, evaluation.deniedOutcomes().map { it.subject })
        assertEquals(
            listOf("no load rule allowed access", "no load rule allowed access"),
            evaluation.deniedOutcomes().map { it.reason },
        )
    }

    @Test
    fun `rule contract failures identify the descriptor and LOAD operation`() {
        val foreignDecisions = RuleBatch.from(listOf(Record(1L, "one")))
            .decideEach { PrivacyDecision.Allow }
        val evaluator = loadEvaluator<TestRuleClient, Record>(
            entityName = RecordDescriptor.entityName,
            rules = listOf(batchPrivacyRule { _, _ -> foreignDecisions }),
        )

        val failure = assertFailsWith<EntBatchRuleContractException> {
            evaluator.evaluate(viewerContext, ruleClient, listOf(Record(1L, "one")))
        }

        assertEquals("StoredRecord LOAD privacy", failure.lifecycle)
        assertEquals(true, failure.foreignBatchResult)
        assertEquals(
            "Batch rule contract violation for StoredRecord LOAD privacy: decisions belong to a different rule batch",
            failure.message,
        )
    }

    @Test
    fun `a nested evaluation cannot replace the outer context subjects or decisions`() {
        val nestedViewerContext = ViewerContext.AsViewer(TestViewer.User(8L))
        val nestedClient = TestRuleClient()
        val nestedRecord = Record(2L, "nested")
        val outerRecords = listOf(Record(1L, "allowed"), Record(3L, "denied"))
        var outerContext: PrivacyRuleContext<TestViewer, TestRuleClient>? = null
        lateinit var evaluator: LoadPrivacyEvaluator<TestViewer, TestRuleClient, Record>
        evaluator = LoadPrivacyEvaluator(
            entityName = RecordDescriptor.entityName,
            rules = listOf(
                batchPrivacyRule { context, batch ->
                    if (context.viewerContext === viewerContext) {
                        outerContext = context
                        val nested = evaluator.evaluate(
                            nestedViewerContext,
                            nestedClient,
                            listOf(nestedRecord, nestedRecord),
                        )
                        assertEquals(listOf(nestedRecord, nestedRecord), nested.allowedSubjects())
                        assertEquals(outerRecords, batch)
                        batch.decideEach { PrivacyDecision.Continue }
                    } else {
                        assertSame(nestedViewerContext, context.viewerContext)
                        assertSame(nestedClient, context.client)
                        batch.decideEach { PrivacyDecision.Allow }
                    }
                },
                batchPrivacyRule { context, batch ->
                    assertSame(outerContext, context)
                    assertSame(viewerContext, context.viewerContext)
                    assertSame(ruleClient, context.client)
                    assertEquals(outerRecords, batch)
                    batch.decideEachIndexed { index, _ ->
                        if (index == 0) {
                            PrivacyDecision.Allow
                        } else {
                            PrivacyDecision.Deny("outer denial")
                        }
                    }
                },
            ),
        )

        val outer = evaluator.evaluate(viewerContext, ruleClient, outerRecords)

        assertSame(outerRecords[0], outer.allowedSubjects().single())
        assertSame(outerRecords[1], outer.deniedOutcomes().single().subject)
        assertEquals("outer denial", outer.deniedOutcomes().single().reason)
    }

    @Test
    fun `each evaluation uses the supplied context without retaining a client or viewer`() {
        val contexts = mutableListOf<PrivacyRuleContext<TestViewer, TestRuleClient>>()
        val evaluator = loadEvaluator<TestRuleClient, Record>(
            entityName = RecordDescriptor.entityName,
            rules = listOf(
                PrivacyRule<TestViewer, TestRuleClient, Record> { context, _ ->
                    contexts += context
                    PrivacyDecision.Allow
                },
            ),
        )
        val firstViewerContext = ViewerContext.AsViewer(TestViewer.User(1L))
        val firstClient = TestRuleClient()
        val secondViewerContext = ViewerContext.AsViewer(TestViewer.User(2L))
        val secondClient = TestRuleClient()
        val entities = listOf(Record(1L, "one"))

        assertEquals(entities, evaluator.evaluate(firstViewerContext, firstClient, entities).allowedSubjects())
        assertEquals(entities, evaluator.evaluate(secondViewerContext, secondClient, entities).allowedSubjects())

        assertEquals(2, contexts.size)
        assertSame(firstViewerContext, contexts[0].viewerContext)
        assertSame(firstClient, contexts[0].client)
        assertSame(secondViewerContext, contexts[1].viewerContext)
        assertSame(secondClient, contexts[1].client)
    }
}
