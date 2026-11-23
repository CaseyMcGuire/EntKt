@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.TestRuleClient
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MutationPrivacyEvaluatorsTest {
    private data class Record(val id: Long, val name: String = "record")

    private val viewerContext = ViewerContext.AsViewer(Viewer.User(7L))
    private val ruleClient = TestRuleClient()

    /** The evaluator builds the rule context; it must carry the supplied viewer context and client. */
    private fun assertSuppliedInputs(context: PrivacyRuleContext<TestRuleClient>) {
        assertSame(viewerContext, context.viewerContext)
        assertSame(ruleClient, context.client)
    }

    private class Evaluators<Candidate>(
        val load: LoadPrivacyEvaluator<TestRuleClient, Record>,
        val create: CreatePrivacyEvaluator<TestRuleClient, Candidate>,
        val update: UpdatePrivacyEvaluator<TestRuleClient, Candidate, Record>,
        val delete: DeletePrivacyEvaluator<TestRuleClient, Candidate, Record>,
    )

    private fun <Candidate> evaluators(
        load: List<BatchPrivacyRule<TestRuleClient, Record>> = emptyList(),
        create: List<BatchPrivacyRule<TestRuleClient, Candidate>>,
        update: List<BatchPrivacyRule<TestRuleClient, Record>> = emptyList(),
        delete: List<BatchPrivacyRule<TestRuleClient, Record>> = emptyList(),
        deriveUpdate: Boolean = false,
        deriveDelete: Boolean = false,
    ): Evaluators<Candidate> = Evaluators(
        load = LoadPrivacyEvaluator("StoredRecord", load),
        create = CreatePrivacyEvaluator("StoredRecord", create),
        update = UpdatePrivacyEvaluator(
            entityName = "StoredRecord",
            rules = update,
            createRules = create,
            derivesFromCreate = deriveUpdate,
        ),
        delete = DeletePrivacyEvaluator(
            entityName = "StoredRecord",
            rules = delete,
            createRules = create,
            derivesFromCreate = deriveDelete,
        ),
    )

    @Test
    fun `each evaluator requires only its own operation input types`() {
        val calls = mutableListOf<String>()
        val load = LoadPrivacyEvaluator("StoredRecord", listOf(PrivacyRule<TestRuleClient, Record> { _, item ->
            calls += "load:${item.id}"
            PrivacyDecision.Allow
        }))
        val createRules = listOf(PrivacyRule<TestRuleClient, String> { _, item ->
            calls += "create:$item"
            PrivacyDecision.Allow
        })
        val create = CreatePrivacyEvaluator("StoredRecord", createRules)
        val update = UpdatePrivacyEvaluator(
            "StoredRecord",
            listOf(PrivacyRule<TestRuleClient, Long> { _, item ->
                calls += "update:$item"
                PrivacyDecision.Allow
            }),
            createRules,
            derivesFromCreate = true,
        )
        val delete = DeletePrivacyEvaluator(
            "StoredRecord",
            listOf(PrivacyRule<TestRuleClient, Boolean> { _, item ->
                calls += "delete:$item"
                PrivacyDecision.Allow
            }),
            createRules,
            derivesFromCreate = true,
        )
        val record = Record(1)
        assertSame(record, load.evaluate(viewerContext, ruleClient, listOf(record)).allowedSubjects().single())
        assertEquals(listOf("candidate"), create.evaluate(viewerContext, ruleClient, listOf("candidate")).allowedSubjects())
        assertSame(record, update.evaluate(viewerContext, ruleClient, listOf(record), { it.id }, { error("resolved") })
            .allowedSubjects().single())
        assertSame(record, delete.evaluate(viewerContext, ruleClient, listOf(record), { true }, { error("resolved") })
            .allowedSubjects().single())
        assertEquals(listOf("load:1", "create:candidate", "update:1", "delete:true"), calls)
    }

    @Test
    fun `CREATE preserves duplicate subjects and shares them with every rule`() {
        val seen = mutableListOf<Record>()
        val contexts = mutableListOf<PrivacyRuleContext<TestRuleClient>>()
        val rules = listOf<BatchPrivacyRule<TestRuleClient, Record>>(
            PrivacyRule { context, item ->
                contexts += context
                seen += item
                PrivacyDecision.Continue
            },
            batchPrivacyRule { context, batch ->
                contexts += context
                batch.decideEachIndexed { index, item ->
                    seen += item
                    if (index == 0) PrivacyDecision.Deny("first") else PrivacyDecision.Allow
                }
            },
        )
        val evaluators = evaluators(create = rules)
        val record = Record(1)
        val evaluation = evaluators.create.evaluate(viewerContext, ruleClient, listOf(record, record))
        assertEquals(4, seen.size)
        seen.forEach { assertSame(record, it) }
        assertEquals(3, contexts.size)
        contexts.forEach { context ->
            assertSame(contexts.first(), context)
            assertSuppliedInputs(context)
        }
        assertSame(record, evaluation.allowedSubjects().single())
        assertSame(record, evaluation.deniedOutcomes().single().subject)
        assertEquals("first", evaluation.deniedOutcomes().single().reason)
    }

    @Test
    fun `UPDATE and DELETE derive CREATE only for unresolved subjects and convert afresh per rule`() {
        for (operation in listOf(PrivacyOperation.UPDATE, PrivacyOperation.DELETE)) {
            val contexts = mutableListOf<PrivacyRuleContext<TestRuleClient>>()
            val operationBatches = mutableListOf<List<Long>>()
            val createBatches = mutableListOf<List<String>>()
            val converted = mutableListOf<Long>()
            val convertedCandidates = mutableListOf<Long>()
            val rules = listOf<BatchPrivacyRule<TestRuleClient, Record>>(
                batchPrivacyRule { context, batch ->
                    contexts += context
                    operationBatches += batch.map { it.id }
                    batch.decideEach {
                        when (it.id) {
                            1L -> PrivacyDecision.Allow
                            2L -> PrivacyDecision.Deny("operation denial")
                            else -> PrivacyDecision.Continue
                        }
                    }
                },
                batchPrivacyRule { context, batch ->
                    contexts += context
                    operationBatches += batch.map { it.id }
                    batch.decideEach { PrivacyDecision.Continue }
                },
            )
            val createRules = listOf<BatchPrivacyRule<TestRuleClient, String>>(
                batchPrivacyRule { context, batch ->
                    contexts += context
                    createBatches += batch.toList()
                    batch.decideEach {
                        when (it) {
                            "allow" -> PrivacyDecision.Allow
                            "deny" -> PrivacyDecision.Deny("create denial")
                            else -> PrivacyDecision.Continue
                        }
                    }
                },
                batchPrivacyRule { context, batch ->
                    contexts += context
                    createBatches += batch.toList()
                    batch.decideEach { PrivacyDecision.Continue }
                },
            )
            val evaluators = evaluators(create = createRules, update = rules, delete = rules,
                deriveUpdate = true, deriveDelete = true)
            val states = listOf(Record(1), Record(2), Record(3, "allow"), Record(4, "deny"), Record(5))
            val toRuleInput = { record: Record ->
                converted += record.id
                record.copy().also { assertNotSame(record, it) }
            }
            val toCandidate = { record: Record ->
                convertedCandidates += record.id
                record.name
            }
            val evaluation = when (operation) {
                PrivacyOperation.UPDATE -> evaluators.update.evaluate(viewerContext, ruleClient, states, toRuleInput, toCandidate)
                else -> evaluators.delete.evaluate(viewerContext, ruleClient, states, toRuleInput, toCandidate)
            }
            assertEquals(listOf(listOf(1L, 2L, 3L, 4L, 5L), listOf(3L, 4L, 5L)), operationBatches)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 3L, 4L, 5L), converted)
            assertEquals(listOf(listOf("allow", "deny", "record"), listOf("record")), createBatches)
            assertEquals(listOf(3L, 4L, 5L, 5L), convertedCandidates)
            assertEquals(listOf(states[0], states[2]), evaluation.allowedSubjects())
            assertEquals(listOf(2L, 4L, 5L), evaluation.deniedOutcomes().map { it.subject.id })
            assertEquals(listOf("operation denial", "create denial", "no ${operation.name.lowercase()} rule allowed access"),
                evaluation.deniedOutcomes().map { it.reason })
            evaluation.forEachIndexed { index, outcome -> assertSame(states[index], outcome.subject) }
            assertEquals(4, contexts.size)
            contexts.forEach { context ->
                assertSame(contexts.first(), context)
                assertSuppliedInputs(context)
            }
        }
    }

    @Test
    fun `derivation flags independently control whether CREATE can grant access`() {
        val record = Record(1)
        for (deriveUpdate in listOf(false, true)) {
            for (deriveDelete in listOf(false, true)) {
                val evaluators = evaluators(
                    create = listOf(PrivacyRule<TestRuleClient, Record> { _, _ -> PrivacyDecision.Allow }),
                    deriveUpdate = deriveUpdate,
                    deriveDelete = deriveDelete,
                )
                val update = evaluators.update.evaluate(viewerContext, ruleClient, listOf(record), { it }) {
                    assertTrue(deriveUpdate, "Disabled UPDATE derivation must skip conversion")
                    it
                }
                val delete = evaluators.delete.evaluate(viewerContext, ruleClient, listOf(record), { it }) {
                    assertTrue(deriveDelete, "Disabled DELETE derivation must skip conversion")
                    it
                }
                assertEquals(if (deriveUpdate) 1 else 0, update.allowedSubjects().size)
                assertEquals(if (deriveDelete) 1 else 0, delete.allowedSubjects().size)
            }
        }
    }

    @Test
    fun `empty rules and Continue fail closed with operation-specific reasons`() {
        for (rules in listOf(emptyList(), listOf(PrivacyRule<TestRuleClient, Record> { _, _ -> PrivacyDecision.Continue }))) {
            val evaluators = evaluators(create = rules, deriveUpdate = true, deriveDelete = true)
            val record = Record(1)
            val evaluations = listOf(
                PrivacyOperation.LOAD to evaluators.load.evaluate(viewerContext, ruleClient, listOf(record)),
                PrivacyOperation.CREATE to evaluators.create.evaluate(viewerContext, ruleClient, listOf(record)),
                PrivacyOperation.UPDATE to evaluators.update.evaluate(viewerContext, ruleClient, listOf(record), { it }, { it }),
                PrivacyOperation.DELETE to evaluators.delete.evaluate(viewerContext, ruleClient, listOf(record), { it }, { it }),
            )
            for ((operation, evaluation) in evaluations) {
                assertSame(record, evaluation.deniedOutcomes().single().subject)
                assertEquals("no ${operation.name.lowercase()} rule allowed access", evaluation.deniedOutcomes().single().reason)
            }
        }
    }

    @Test
    fun `empty and bypassed evaluations skip every rule and converter`() {
        val unreachable = listOf(PrivacyRule<TestRuleClient, Record> { _, _ -> error("Rule must not run") })
        val evaluators = evaluators(load = unreachable, create = unreachable, update = unreachable, delete = unreachable,
            deriveUpdate = true, deriveDelete = true)
        val unreachableConversion = { _: Record -> error("Input must not be converted") }
        val record = Record(1)
        for (subjects in listOf(emptyList(), listOf(record, record))) {
            val context: ViewerContext = if (subjects.isEmpty()) {
                viewerContext
            } else {
                ViewerContext.privacyBypass_DANGEROUS("evaluator test")
            }
            val evaluations = listOf(
                evaluators.load.evaluate(context, ruleClient, subjects),
                evaluators.create.evaluate(context, ruleClient, subjects),
                evaluators.update.evaluate(context, ruleClient, subjects, unreachableConversion, unreachableConversion),
                evaluators.delete.evaluate(context, ruleClient, subjects, unreachableConversion, unreachableConversion),
            )
            evaluations.forEach { evaluation ->
                assertEquals(subjects.size, evaluation.size)
                assertEquals(subjects, evaluation.allowedSubjects())
                assertTrue(evaluation.deniedOutcomes().isEmpty())
            }
        }
    }

    @Test
    fun `duplicate states retain independent decisions through CREATE derivation`() {
        val record = Record(1)
        val rules = listOf(batchPrivacyRule<TestRuleClient, Record> { _, batch ->
            batch.decideEachIndexed { index, _ ->
                if (index == 0) PrivacyDecision.Deny("first occurrence") else PrivacyDecision.Continue
            }
        })
        val evaluators = evaluators(update = rules, delete = rules, deriveUpdate = true, deriveDelete = true,
            create = listOf(batchPrivacyRule<TestRuleClient, Record> { _, batch ->
                assertEquals(1, batch.size)
                assertSame(record, batch.single())
                batch.allowAll()
            }))
        for (evaluation in listOf(
            evaluators.update.evaluate(viewerContext, ruleClient, listOf(record, record), { it }, { it }),
            evaluators.delete.evaluate(viewerContext, ruleClient, listOf(record, record), { it }, { it }),
        )) {
            assertEquals(2, evaluation.size)
            assertSame(record, evaluation.allowedSubjects().single())
            assertSame(record, evaluation.deniedOutcomes().single().subject)
            assertEquals("first occurrence", evaluation.deniedOutcomes().single().reason)
        }
    }

    @Test
    fun `operation and derived CREATE exceptions escape unchanged including cancellation`() {
        for (failure in listOf(IllegalStateException("rule failed"), CancellationException("cancelled"))) {
            for (failInCreate in listOf(false, true)) {
                val rules = listOf(PrivacyRule<TestRuleClient, Record> { _, _ ->
                    if (!failInCreate) {
                        throw failure
                    }
                    PrivacyDecision.Continue
                })
                val evaluators = evaluators(update = rules, delete = rules, deriveUpdate = true, deriveDelete = true,
                    create = listOf(PrivacyRule<TestRuleClient, Record> { _, _ -> throw failure }))
                assertSame(failure, assertFails { evaluators.create.evaluate(viewerContext, ruleClient, listOf(Record(1))) })
                assertSame(failure, assertFails { evaluators.update.evaluate(viewerContext, ruleClient, listOf(Record(1)), { it }, { it }) })
                assertSame(failure, assertFails { evaluators.delete.evaluate(viewerContext, ruleClient, listOf(Record(1)), { it }, { it }) })
            }
        }
    }

    @Test
    fun `operation and derived CREATE contract failures name the requested operation`() {
        val record = Record(1)
        val foreign = RuleBatch.from(listOf(record)).decideEach { PrivacyDecision.Allow }
        for (failInCreate in listOf(false, true)) {
            val rules = listOf(batchPrivacyRule<TestRuleClient, Record> { _, batch ->
                if (failInCreate) batch.decideEach { PrivacyDecision.Continue } else foreign
            })
            val evaluators = evaluators(update = rules, delete = rules, deriveUpdate = true, deriveDelete = true,
                create = listOf(batchPrivacyRule<TestRuleClient, Record> { _, _ -> foreign }))
            val calls = listOf(
                PrivacyOperation.CREATE to { evaluators.create.evaluate(viewerContext, ruleClient, listOf(record)) },
                PrivacyOperation.UPDATE to { evaluators.update.evaluate(viewerContext, ruleClient, listOf(record), { it }, { it }) },
                PrivacyOperation.DELETE to { evaluators.delete.evaluate(viewerContext, ruleClient, listOf(record), { it }, { it }) },
            )
            for ((operation, call) in calls) {
                val failure = assertFailsWith<EntBatchRuleContractException> { call() }
                assertEquals("StoredRecord ${operation.name} privacy", failure.lifecycle)
                assertTrue(failure.foreignBatchResult)
            }
        }
    }

    @Test
    fun `each call uses its supplied viewer and client including derived rules`() {
        val seen = mutableListOf<PrivacyRuleContext<TestRuleClient>>()
        val evaluators = evaluators(deriveUpdate = true, create = listOf(PrivacyRule<TestRuleClient, Record> { context, _ ->
            seen += context
            PrivacyDecision.Allow
        }))
        val secondViewerContext = ViewerContext.AsViewer(Viewer.User(8L))
        val secondClient = TestRuleClient()
        evaluators.update.evaluate(viewerContext, ruleClient, listOf(Record(1)), { it }, { it })
        evaluators.update.evaluate(secondViewerContext, secondClient, listOf(Record(1)), { it }, { it })
        assertEquals(2, seen.size)
        assertSuppliedInputs(seen[0])
        assertSame(secondViewerContext, seen[1].viewerContext)
        assertSame(secondClient, seen[1].client)
        assertNotSame(seen[0], seen[1])
    }
}
