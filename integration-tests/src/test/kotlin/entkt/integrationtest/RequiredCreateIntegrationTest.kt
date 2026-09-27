package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleCreateValidationRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.CalendarEvent
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.mutation.FieldPatch
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.validation.ValidationDecision
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequiredCreateIntegrationTest : PostgresTestBase() {
    @Test
    fun `arguments seed fresh drafts before the optional block without persisting early`() {
        val client = EntClient(resetAndDriver())
        val first = client.users.create(name = "First", email = "first@example.com") {
            assertEquals("First", name)
            assertEquals("first@example.com", email)
            assertTrue(isSet(User.name))
            assertTrue(isSet(User.email))
            assertFalse(isSet(User.apiToken))
            name = "Configured"
            apiToken = null
            assertTrue(isSet(User.apiToken))
        }
        val second = client.users.create(name = "Second", email = "second@example.com")

        assertTrue(client.users.query().all(testViewerContext).getOrThrow().isEmpty())

        val savedSecond = second.saveAndLoad(testViewerContext).getOrThrow()
        val savedFirst = first.saveAndLoad(testViewerContext).getOrThrow()
        assertEquals("Second", savedSecond.name)
        assertEquals("Configured", savedFirst.name)
        assertEquals("first@example.com", savedFirst.email)
        assertNull(savedFirst.apiToken)
    }

    @Test
    fun `required overload runs the existing hooks privacy validation and disclosure lifecycle`() {
        val events = mutableListOf<String>()
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    create(ArticleCreatePrivacyRule { _, candidate ->
                        events += "privacy"
                        assertEquals("beforeCreate", candidate.title)
                        assertFalse(candidate.published)
                        PrivacyDecision.Allow
                    })
                    load(ArticleLoadPrivacyRule { _, entity ->
                        events += "load"
                        assertEquals("beforeCreate", entity.title)
                        PrivacyDecision.Allow
                    })
                }
                validation {
                    create(ArticleCreateValidationRule { _, candidate ->
                        events += "validation"
                        assertEquals("beforeCreate", candidate.title)
                        ValidationDecision.Valid
                    })
                }
            }
        }
        val client = EntClient(resetAndDriver()) {
            policies { articles(policy) }
            hooks {
                articles {
                    beforeSave { state ->
                        events += "beforeSave"
                        assertEquals(FieldPatch.Set("configured"), state.title)
                        assertEquals(FieldPatch.Unset, state.published)
                        state.setTitle("beforeSave")
                    }
                    beforeCreate { state ->
                        events += "beforeCreate"
                        assertEquals(FieldPatch.Set("beforeSave"), state.title)
                        state.setTitle("beforeCreate")
                    }
                    afterCreate { entity ->
                        events += "afterCreate"
                        assertEquals("beforeCreate", entity.title)
                    }
                }
            }
        }
        val author = client.users.create(name = "Author", email = "author@example.com")
            .saveAndLoad(testViewerContext).getOrThrow()
        val pending = client.articles.create(title = "argument", authorId = author.id) {
            title = "configured"
        }
        assertTrue(events.isEmpty())

        val article = pending.saveAndLoad(ViewerContext(Viewer.User(author.id))).getOrThrow()

        assertEquals("beforeCreate", article.title)
        assertEquals(author.id, article.authorId)
        assertEquals(
            listOf("beforeSave", "beforeCreate", "privacy", "validation", "afterCreate", "load"),
            events,
        )
    }

    @Test
    fun `clearing a required argument still fails runtime validation`() {
        val client = EntClient(resetAndDriver())
        val pending = client.users.create(name = "Initially present", email = "user@example.com")
        pending.configure { name = null }

        val failure = assertIs<MutationResult.Failed>(pending.save(testViewerContext))
        val exception = assertIs<EntValidationException>(failure.exception)

        assertEquals(MutationWriteState.NotPersisted, exception.writeState)
        assertEquals("name", exception.violations.single().field)
        assertTrue(client.users.query().all(testViewerContext).getOrThrow().isEmpty())
    }

    @Test
    fun `omitted defaults and explicit null keep their existing semantics`() {
        val client = EntClient(resetAndDriver())
        val start = LocalDate.of(2026, 9, 26)
        val defaulted = client.calendarEvents.create(name = "Defaults", startsOn = start) {
            assertFalse(isSet(CalendarEvent.dueOn))
            assertFalse(isSet(CalendarEvent.reviewOn))
            assertNull(dueOn)
            assertNull(reviewOn)
        }.saveAndLoad(testViewerContext).getOrThrow()
        val overridden = client.calendarEvents.create(name = "Overrides", startsOn = start.plusDays(1)) {
            dueOn = start.plusDays(2)
            reviewOn = null
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals(LocalDate.of(2026, 1, 1), defaulted.dueOn)
        assertEquals(LocalDate.of(2026, 2, 1), defaulted.reviewOn)
        assertNull(defaulted.endsOn)
        assertEquals(start.plusDays(2), overridden.dueOn)
        assertNull(overridden.reviewOn)
    }

    @Test
    fun `field-backed and nullable relationships use their declared input names`() {
        val client = EntClient(resetAndDriver())
        val author = client.users.create(name = "Author", email = "author@example.com")
            .saveAndLoad(testViewerContext).getOrThrow()

        val note = client.notes.create(body = "Field-backed", writer = author.id)
            .saveAndLoad(testViewerContext).getOrThrow()
        val reminder = client.reminders.create(body = "No assignee")
            .saveAndLoad(testViewerContext).getOrThrow()

        assertEquals(author.id, note.writer)
        assertNull(reminder.assigneeId)
    }

    @Test
    fun `transaction-scoped overloads commit through the existing pending mutation terminals`() {
        val client = EntClient(resetAndDriver())
        val saved = client.withTransaction { tx ->
            val author = tx.users.create(name = "Author", email = "author@example.com")
                .saveAndLoad(testViewerContext).orRollback()
            tx.articles.create(title = "Transactional", authorId = author.id)
                .saveAndLoad(testViewerContext).orRollback()
        }.getOrThrow()

        assertEquals(saved, client.articles.findById(testViewerContext, saved.id).getOrThrow())
    }
}
