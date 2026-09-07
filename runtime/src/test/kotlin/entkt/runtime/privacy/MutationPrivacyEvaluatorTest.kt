@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityMapping
import entkt.runtime.query.EdgeMapping
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.rule.RuleBatch
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MutationPrivacyEvaluatorTest {
    private data class Record(
        override val id: Long,
        val name: String = "record",
        val payload: ByteArray = byteArrayOf(1),
    ) : EntEntity.LongId

    private object RecordMapping : EntityMapping<Record> {
        override val entityName = "StoredRecord"
        override val clientName = "records"
        override val entityClass = Record::class
        override val table = "records"

        override fun decode(row: Map<String, Any?>): Record = error("No storage in evaluator tests")
        override fun edgeByStorageName(storageName: String): EdgeMapping<Record, *>? = null
    }

    private val viewerContext = ViewerContext(Viewer.User(7L))
    private val ruleClient = Any()
    private val ruleContext = PrivacyRuleContext(viewerContext, ruleClient)
    private val mutationOperations = listOf(
        PrivacyOperation.CREATE,
        PrivacyOperation.UPDATE,
        PrivacyOperation.DELETE,
    )

    private fun evaluator(
        operation: PrivacyOperation = PrivacyOperation.UPDATE,
        rules: List<BatchPrivacyRule<Any, Record>> = emptyList(),
        freshItem: ((Record) -> Record)? = null,
        fallback: PrivacyDecisionEvaluator<Any, Record, *>? = null,
    ): MutationPrivacyEvaluator<Any, Record> {
        if (freshItem == null) {
            return MutationPrivacyEvaluator(RecordMapping, operation, rules, fallback)
        }
        return MutationPrivacyEvaluator(
            entity = RecordMapping,
            operation = operation,
            primary = PrivacyDecisionEvaluator(rules, freshItem),
            fallback = fallback,
        )
    }

    @Test
    fun `direct input constructor preserves subjects and context without a caller converter`() {
        val seen = mutableListOf<Record>()
        val rules = mutableListOf<BatchPrivacyRule<Any, Record>>(
            PrivacyRule { context, item ->
                assertSame(ruleContext, context)
                seen += item
                PrivacyDecision.Continue
            },
            batchPrivacyRule { context, batch ->
                assertSame(ruleContext, context)
                batch.decideEachIndexed { index, item ->
                    seen += item
                    if (index == 0) PrivacyDecision.Deny("first") else PrivacyDecision.Allow
                }
            },
        )
        val evaluator = MutationPrivacyEvaluator(
            entity = RecordMapping,
            operation = PrivacyOperation.CREATE,
            rules = rules,
        )
        rules.clear()
        val record = Record(1)

        val evaluation = evaluator.evaluate(ruleContext, listOf(record, record))

        assertEquals(4, seen.size)
        seen.forEach { assertSame(record, it) }
        assertSame(record, evaluation.allowedSubjects().single())
        assertSame(record, evaluation.deniedOutcomes().single().subject)
        assertEquals("first", evaluation.deniedOutcomes().single().reason)
    }

    @Test
    fun `bound primary and fallback rules share one context and only see unresolved states`() {
        val contexts = mutableListOf<PrivacyRuleContext<Any>>()
        val primaryBatches = mutableListOf<List<Long>>()
        val fallbackBatches = mutableListOf<List<String>>()
        val primaryRules = mutableListOf<BatchPrivacyRule<Any, Record>>(
            batchPrivacyRule { context, batch ->
                contexts += context
                primaryBatches += batch.map { it.id }
                batch.decideEach {
                    when (it.id) {
                        1L -> PrivacyDecision.Allow
                        2L -> PrivacyDecision.Deny("primary denial")
                        else -> PrivacyDecision.Continue
                    }
                }
            },
            batchPrivacyRule { context, batch ->
                contexts += context
                primaryBatches += batch.map { it.id }
                batch.decideEach { PrivacyDecision.Continue }
            },
        )
        val fallbackRules = mutableListOf<BatchPrivacyRule<Any, String>>(
            batchPrivacyRule { context, batch ->
                contexts += context
                fallbackBatches += batch.toList()
                batch.decideEach {
                    when (it) {
                        "allow" -> PrivacyDecision.Allow
                        "deny" -> PrivacyDecision.Deny("fallback denial")
                        else -> PrivacyDecision.Continue
                    }
                }
            },
        )
        val evaluator = evaluator(
            rules = primaryRules,
            fallback = PrivacyDecisionEvaluator(fallbackRules) { record: Record -> record.name },
        )
        primaryRules.clear()
        fallbackRules.clear()
        val states = listOf(Record(1), Record(2), Record(3, "allow"), Record(4, "deny"), Record(5))

        val evaluation = evaluator.evaluate(ruleContext, states)

        assertEquals(listOf(listOf(1L, 2L, 3L, 4L, 5L), listOf(3L, 4L, 5L)), primaryBatches)
        assertEquals(listOf(listOf("allow", "deny", "record")), fallbackBatches)
        assertEquals(listOf(states[0], states[2]), evaluation.allowedSubjects())
        assertEquals(listOf(2L, 4L, 5L), evaluation.deniedOutcomes().map { it.subject.id })
        assertEquals(
            listOf("primary denial", "fallback denial", "no update rule allowed access"),
            evaluation.deniedOutcomes().map { it.reason },
        )
        evaluation.forEachIndexed { index, outcome -> assertSame(states[index], outcome.subject) }
        assertEquals(3, contexts.size)
        contexts.forEach {
            assertSame(ruleContext, it)
            assertSame(viewerContext, it.viewerContext)
            assertSame(ruleClient, it.client)
        }
    }

    @Test
    fun `no rules or unresolved fallback denies with the operation-specific default reason`() {
        val record = Record(1)
        for (operation in mutationOperations) {
            val fallback = PrivacyDecisionEvaluator<Any, Record, Record>(
                rules = listOf(PrivacyRule { _, _ -> PrivacyDecision.Continue }),
                freshItem = { it },
            )
            for (configuredFallback in listOf(null, fallback)) {
                val evaluation = evaluator(operation = operation, fallback = configuredFallback)
                    .evaluate(ruleContext, listOf(record))

                assertSame(record, evaluation.deniedOutcomes().single().subject)
                assertEquals(
                    "no ${operation.name.lowercase()} rule allowed access",
                    evaluation.deniedOutcomes().single().reason,
                )
            }
        }
    }

    @Test
    fun `empty and bypassed evaluations skip rules and converters`() {
        val evaluator = evaluator(
            rules = listOf(PrivacyRule { _, _ -> error("Primary must not run") }),
            freshItem = { error("Primary input must not be converted") },
            fallback = PrivacyDecisionEvaluator<Any, Record, String>(
                rules = listOf(PrivacyRule { _, _ -> error("Fallback must not run") }),
                freshItem = { error("Fallback input must not be converted") },
            ),
        )
        val record = Record(1)

        assertTrue(evaluator.evaluate(ruleContext, emptyList()).isEmpty())
        val allowed = evaluator.evaluate(
            PrivacyRuleContext(ViewerContext.privacyBypass_DANGEROUS("mutation evaluator test"), ruleClient),
            listOf(record, record),
        ).allowedSubjects()
        assertEquals(2, allowed.size)
        allowed.forEach { assertSame(record, it) }
    }

    @Test
    fun `primary and fallback rules can share the original input without copies`() {
        val observed = mutableListOf<Record>()
        val observe = PrivacyRule<Any, Record> { _, item ->
            assertContentEquals(byteArrayOf(1), item.payload)
            observed += item
            PrivacyDecision.Continue
        }
        val evaluator = evaluator(
            rules = listOf(observe, observe),
            fallback = PrivacyDecisionEvaluator(
                rules = listOf(observe, PrivacyRule<Any, Record> { _, _ -> PrivacyDecision.Allow }),
                freshItem = { record: Record -> record },
            ),
        )
        val record = Record(1)

        assertSame(record, evaluator.evaluate(ruleContext, listOf(record)).allowedSubjects().single())
        assertContentEquals(byteArrayOf(1), record.payload)
        assertEquals(3, observed.size)
        observed.forEach {
            assertSame(record, it)
            assertSame(record.payload, it.payload)
        }
    }

    @Test
    fun `duplicate states retain independent decisions through fallback`() {
        val record = Record(1)
        val evaluator = evaluator(
            rules = listOf(batchPrivacyRule { _, batch ->
                batch.decideEachIndexed { index, _ ->
                    if (index == 0) PrivacyDecision.Deny("first occurrence") else PrivacyDecision.Continue
                }
            }),
            fallback = PrivacyDecisionEvaluator<Any, Record, Record>(
                rules = listOf(batchPrivacyRule { _, batch ->
                    assertEquals(1, batch.size)
                    batch.decideEach { PrivacyDecision.Allow }
                }),
                freshItem = { it },
            ),
        )

        val evaluation = evaluator.evaluate(ruleContext, listOf(record, record))

        assertEquals(2, evaluation.size)
        assertSame(record, evaluation.allowedSubjects().single())
        assertSame(record, evaluation.deniedOutcomes().single().subject)
        assertEquals("first occurrence", evaluation.deniedOutcomes().single().reason)
    }

    @Test
    fun `resolved primary decisions never invoke fallback rules or converters`() {
        val evaluator = evaluator(
            rules = listOf(PrivacyRule { _, _ -> PrivacyDecision.Allow }),
            fallback = PrivacyDecisionEvaluator<Any, Record, Record>(
                rules = listOf(PrivacyRule { _, _ -> error("Fallback must not run") }),
                freshItem = { error("Fallback input must not be converted") },
            ),
        )

        assertEquals(1, evaluator.evaluate(ruleContext, listOf(Record(1))).allowedSubjects().size)
    }

    @Test
    fun `primary and fallback exceptions including cancellation escape unchanged`() {
        for (failure in listOf(IllegalStateException("rule failed"), CancellationException("cancelled"))) {
            for (failInFallback in listOf(false, true)) {
                val evaluator = evaluator(
                    rules = listOf(PrivacyRule { _, _ ->
                        if (!failInFallback) throw failure
                        PrivacyDecision.Continue
                    }),
                    fallback = PrivacyDecisionEvaluator<Any, Record, Record>(
                        rules = listOf(PrivacyRule { _, _ -> throw failure }),
                        freshItem = { it },
                    ),
                )

                assertSame(failure, assertFails { evaluator.evaluate(ruleContext, listOf(Record(1))) })
            }
        }
    }

    @Test
    fun `primary and fallback contract failures use the entity and mutation operation`() {
        val record = Record(1)
        val foreignDecisions = RuleBatch.from(listOf(record)).decideEach { PrivacyDecision.Allow }
        for (operation in mutationOperations) {
            for (failInFallback in listOf(false, true)) {
                val evaluator = evaluator(
                    operation = operation,
                    rules = listOf(batchPrivacyRule { _, batch ->
                        if (failInFallback) batch.decideEach { PrivacyDecision.Continue } else foreignDecisions
                    }),
                    fallback = PrivacyDecisionEvaluator<Any, Record, Record>(
                        rules = listOf(batchPrivacyRule { _, _ -> foreignDecisions }),
                        freshItem = { it },
                    ),
                )

                val failure = assertFailsWith<EntBatchRuleContractException> {
                    evaluator.evaluate(ruleContext, listOf(record))
                }

                assertEquals("StoredRecord ${operation.name} privacy", failure.lifecycle)
                assertTrue(failure.foreignBatchResult)
            }
        }
    }

    @Test
    fun `each evaluation uses its supplied context without retaining a client or viewer`() {
        val clients = listOf(Any(), Any())
        val viewers = listOf(viewerContext, ViewerContext(Viewer.User(8L)))
        val contexts = mutableListOf<PrivacyRuleContext<Any>>()
        val suppliedContexts = viewers.mapIndexed { index, viewer -> PrivacyRuleContext(viewer, clients[index]) }
        val evaluator = evaluator(
            rules = listOf(PrivacyRule { context, _ ->
                contexts += context
                PrivacyDecision.Allow
            }),
        )

        suppliedContexts.forEach { evaluator.evaluate(it, listOf(Record(1))) }

        assertEquals(2, contexts.size)
        contexts.forEachIndexed { index, context ->
            assertSame(suppliedContexts[index], context)
            assertSame(clients[index], context.client)
            assertSame(viewers[index], context.viewerContext)
        }
        assertNotSame(contexts[0], contexts[1])
    }

    @Test
    fun `LOAD is rejected at construction`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            evaluator(operation = PrivacyOperation.LOAD)
        }
        assertEquals("Use LoadPrivacyEvaluator for LOAD privacy", failure.message)
    }
}
