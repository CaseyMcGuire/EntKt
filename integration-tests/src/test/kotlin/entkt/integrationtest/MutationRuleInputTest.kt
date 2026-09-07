@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreateDraft
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleCreateValidationRule
import entkt.integrationtest.ent.ArticleDeletePrivacyRule
import entkt.integrationtest.ent.ArticleDeleteRuleInput
import entkt.integrationtest.ent.ArticleDeleteValidationRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleUpdatePrivacyRule
import entkt.integrationtest.ent.ArticleUpdateRuleInput
import entkt.integrationtest.ent.ArticleUpdateValidationRule
import entkt.integrationtest.ent.ArticleWriteCandidate
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.schema.ArticleMeta
import entkt.integrationtest.schema.HighlightRect
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.hook.batchActionHook
import entkt.runtime.hook.batchTransformingHook
import entkt.runtime.mutation.FieldPatch
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.allowAll
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.batchValidationRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercise generated mutation rule and hook wiring without a database or per-rule copying. */
class MutationRuleInputTest {
    private val viewerContext = ViewerContext(Viewer.User(7L))

    @Test
    fun `scalar and bulk CREATE share prepared candidates across scalar and batch rules`() {
        for (count in 1..2) {
            val driver = RecordingDriver()
            val probe = RuleProbe()
            val client = EntClient(driver) { policies { articles(probe) } }
            val payload = byteArrayOf(1, 2)
            val metadata = ArticleMeta("test", mutableListOf("original"))
            val drafts = listOf<ArticleCreateDraft.() -> Unit>(
                {
                    title = "shared"
                    authorId = 7L
                    this.payload = payload
                    this.metadata = metadata
                    rects = mutableListOf(HighlightRect(1, 0.0, 0.0, 10.0, 20.0))
                },
                { title = "null fields"; authorId = 7L },
            )

            if (count == 1) {
                client.articles.create(drafts.first()).saveAndLoad(viewerContext).getOrThrow()
            } else {
                client.articles.createMany(viewerContext, *drafts.toTypedArray()).getOrThrow()
            }

            assertEquals(count * 2, probe.createPrivacy.size)
            assertEquals(count * 2, probe.createValidation.size)
            for (index in 0 until count) {
                val candidate = probe.createPrivacy[index]
                assertSame(candidate, probe.createPrivacy[index + count])
                assertSame(candidate, probe.createValidation[index])
                assertSame(candidate, probe.createValidation[index + count])
                val row = driver.rows.values.elementAt(index)
                assertSame(row["payload"], candidate.payload)
                assertSame(row["metadata"], candidate.metadata)
                assertSame(row["rects"], candidate.rects)
            }
            assertNotSame(payload, probe.createPrivacy.first().payload)
            assertNotSame(metadata, probe.createPrivacy.first().metadata)
            assertNotSame(metadata.tags, probe.createPrivacy.first().metadata!!.tags)
            assertEquals(List(count) { listOf("metadata", "rects") }.flatten(), driver.jsonCopies)
        }
    }

    @Test
    fun `UPDATE and derived CREATE rules share before patches candidate and edge changes`() {
        for (hasChanges in listOf(false, true)) {
            val driver = RecordingDriver()
            val id = driver.seed()
            val probe = RuleProbe()
            val client = EntClient(driver) { policies { articles(probe) } }

            client.articles.update(id) {
                if (hasChanges) {
                    payload = byteArrayOf(3, 4)
                    metadata = ArticleMeta("updated", mutableListOf("updated"))
                    rects = mutableListOf(HighlightRect(2, 1.0, 1.0, 20.0, 30.0))
                }
            }.saveAndLoad(viewerContext).getOrThrow()

            assertEquals(2, probe.updatePrivacy.size)
            assertEquals(2, probe.updateValidation.size)
            val first = probe.updatePrivacy.first()
            (probe.updatePrivacy + probe.updateValidation).forEach { input ->
                assertSame(first.before, input.before)
                assertSame(first.requestedPatch, input.requestedPatch)
                assertSame(first.effectivePatch, input.effectivePatch)
                assertSame(first.candidate, input.candidate)
                assertSame(first.edgeChanges, input.edgeChanges)
            }
            assertEquals(2, probe.createPrivacy.size)
            assertEquals(2, probe.createValidation.size)
            (probe.createPrivacy + probe.createValidation).forEach { assertSame(first.candidate, it) }
            assertSame(driver.rows.getValue(id)["payload"], first.candidate.payload)
            assertSame(driver.rows.getValue(id)["metadata"], first.candidate.metadata)
            assertSame(driver.rows.getValue(id)["rects"], first.candidate.rects)
            assertEquals(if (hasChanges) listOf("metadata", "rects") else emptyList(), driver.jsonCopies)
        }
    }

    @Test
    fun `scalar and bulk DELETE share entities and candidates including CREATE fallback`() {
        for (bulk in listOf(false, true)) {
            val driver = RecordingDriver()
            val id = driver.seed()
            val probe = RuleProbe()
            val client = EntClient(driver) { policies { articles(probe) } }

            if (bulk) {
                assertEquals(1, client.articles.deleteMany(viewerContext).getOrThrow())
            } else {
                assertTrue(client.articles.deleteById(viewerContext, id).getOrThrow())
            }

            assertEquals(2, probe.deletePrivacy.size)
            assertEquals(2, probe.deleteValidation.size)
            val first = probe.deletePrivacy.first()
            (probe.deletePrivacy + probe.deleteValidation).forEach { input ->
                assertSame(first.entity, input.entity)
                assertSame(first.candidate, input.candidate)
            }
            assertEquals(2, probe.createPrivacy.size)
            probe.createPrivacy.forEach { assertSame(first.candidate, it) }
            assertSame(first.entity.payload, first.candidate.payload)
            assertSame(first.entity.metadata, first.candidate.metadata)
            assertSame(first.entity.rects, first.candidate.rects)
            assertTrue(probe.createValidation.isEmpty())
            assertTrue(driver.jsonCopies.isEmpty())
            assertTrue(driver.rows.isEmpty())
        }
    }

    @Test
    fun `generated mutation wiring runs resolved hook lists in lifecycle order`() {
        for (bulk in listOf(false, true)) {
            val driver = RecordingDriver()
            val events = mutableListOf<String>()
            val client = EntClient(driver) {
                policies { articles(RuleProbe()) }
                hooks {
                    articles {
                        beforeSave { state ->
                            events += "beforeSave"
                            state.setTitle("saved")
                        }
                        beforeCreate(batchTransformingHook { states ->
                            events += "beforeCreate:${states.size}"
                            states.mapStates { state ->
                                assertEquals(FieldPatch.Set("saved"), state.title)
                                state.setTitle("created")
                            }
                        })
                        afterCreate(batchActionHook { entities ->
                            events += "afterCreate:${entities.size}"
                            entities.forEach { assertEquals("created", driver.rows.getValue(it.id)["title"]) }
                        })
                        beforeUpdate { state ->
                            events += "beforeUpdate"
                            assertEquals("created", state.before.title)
                            assertEquals(FieldPatch.Set("saved"), state.title)
                            state.setTitle("updated")
                        }
                        afterUpdate { entity ->
                            events += "afterUpdate"
                            assertEquals("updated", driver.rows.getValue(entity.id)["title"])
                        }
                        beforeDelete(batchActionHook { entities ->
                            events += "beforeDelete:${entities.size}"
                            entities.forEach { assertTrue(it.id in driver.rows) }
                        })
                        afterDelete(batchActionHook { entities ->
                            events += "afterDelete:${entities.size}"
                            entities.forEach { assertTrue(it.id !in driver.rows) }
                        })
                    }
                }
            }
            val draft: ArticleCreateDraft.() -> Unit = { title = "draft"; authorId = 7L }
            val created = if (bulk) {
                client.articles.createMany(viewerContext, draft, draft).getOrThrow()
            } else {
                listOf(client.articles.create(draft).saveAndLoad(viewerContext).getOrThrow())
            }
            val updated = client.articles.update(created.first().id) { title = "draft update" }
                .saveAndLoad(viewerContext).getOrThrow()
            assertEquals("updated", updated.title)

            if (bulk) {
                assertEquals(created.size, client.articles.deleteMany(viewerContext).getOrThrow())
            } else {
                assertTrue(client.articles.deleteById(viewerContext, updated.id).getOrThrow())
            }
            assertEquals(
                List(created.size) { "beforeSave" } + listOf(
                    "beforeCreate:${created.size}", "afterCreate:${created.size}",
                    "beforeSave", "beforeUpdate", "afterUpdate",
                    "beforeDelete:${created.size}", "afterDelete:${created.size}",
                ),
                events,
            )
        }
    }

    private class RuleProbe : EntityPolicy<Article, ArticlePolicyScope> {
        val createPrivacy = mutableListOf<ArticleWriteCandidate>()
        val createValidation = mutableListOf<ArticleWriteCandidate>()
        val updatePrivacy = mutableListOf<ArticleUpdateRuleInput>()
        val updateValidation = mutableListOf<ArticleUpdateRuleInput>()
        val deletePrivacy = mutableListOf<ArticleDeleteRuleInput>()
        val deleteValidation = mutableListOf<ArticleDeleteRuleInput>()

        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy {
                load(allowAll)
                create(ArticleCreatePrivacyRule { _, item -> createPrivacy += item; PrivacyDecision.Continue })
                create(batchPrivacyRule { _, batch ->
                    batch.decideEach { createPrivacy += it; PrivacyDecision.Allow }
                })
                val update = ArticleUpdatePrivacyRule { _, item -> updatePrivacy += item; PrivacyDecision.Continue }
                update(update, update)
                updateDerivesFromCreate()
                val delete = ArticleDeletePrivacyRule { _, item -> deletePrivacy += item; PrivacyDecision.Continue }
                delete(delete, delete)
                deleteDerivesFromCreate()
            }
            validation {
                create(ArticleCreateValidationRule { _, item -> createValidation += item; ValidationDecision.Valid })
                create(batchValidationRule { _, batch ->
                    batch.decideEach { createValidation += it; ValidationDecision.Valid }
                })
                val update = ArticleUpdateValidationRule { _, item -> updateValidation += item; ValidationDecision.Valid }
                update(update, update)
                updateDerivesFromCreate()
                val delete = ArticleDeleteValidationRule { _, item -> deleteValidation += item; ValidationDecision.Valid }
                delete(delete, delete)
            }
        }
    }

    /** Only models the unfiltered Article operations exercised here; all other I/O fails. */
    private class RecordingDriver : DatabaseDriver by NoopDriver {
        override val inTransaction = true
        val rows = linkedMapOf<Any, Map<String, Any?>>()
        val jsonCopies = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> copyJsonValue(table: String, column: String, value: T): T {
            jsonCopies += column
            return when (value) {
                is ArticleMeta -> value.copy(tags = value.tags.toMutableList())
                is List<*> -> value.toMutableList()
                else -> value
            } as T
        }

        override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> {
            val id = rows.size.toLong() + 1
            return (values + ("id" to id)).also { rows[id] = it }
        }

        override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> =
            values.map { insert(table, it) }

        override fun byId(table: String, id: Any): Map<String, Any?>? = rows[id]

        override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?> =
            (rows.getValue(id) + values).also { rows[id] = it }

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            check(predicates.isEmpty() && orderBy.isEmpty() && limit == null && offset == null)
            return rows.values.toList()
        }

        override fun delete(table: String, id: Any): Boolean = rows.remove(id) != null

        override fun deleteManyByIds(
            table: String,
            idColumn: String,
            ids: List<Any>,
            predicates: List<Predicate<*>>,
        ): List<Any> {
            check(idColumn == "id" && predicates.isEmpty())
            return ids.filter { delete(table, it) }
        }

        fun seed(): Long = insert(
            Article.TABLE,
            mapOf(
                "title" to "existing", "published" to false, "author_id" to 7L,
                "payload" to byteArrayOf(1, 2),
                "metadata" to ArticleMeta("original", mutableListOf("original")),
                "rects" to mutableListOf(HighlightRect(1, 0.0, 0.0, 10.0, 20.0)),
            ),
        ).getValue("id") as Long
    }
}
