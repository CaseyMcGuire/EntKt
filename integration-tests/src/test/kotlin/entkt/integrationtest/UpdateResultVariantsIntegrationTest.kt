package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleUpdatePrivacyRule
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntTargetAbsentException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import kotlin.Unit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the update-side canonical terminals:
 * `save(): MutationResult<Unit>` / `saveAndLoad(): MutationResult<Entity>`.
 *
 * Pins the driver-classification wiring (recognized constraints →
 * `EntConstraintViolationException` hardcoding `NotPersisted`), the
 * target-absence contract (`EntTargetAbsentException`), and the
 * assignment-free no-op semantics: target-existence is still checked,
 * pre-write phases still run, persist and post-persist are skipped,
 * and nothing is written — while explicitly assigned equal values DO
 * write.
 */
class UpdateResultVariantsIntegrationTest : PostgresTestBase() {

    private object AllowAll : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {

            policies {
                articles(AllowAll)
                users(OpenUser)
            }
        }
    }

    // ---- Constraint classification ----

    @Test
    fun `save returns Failed(EntConstraintViolationException) for unique violation on update`() {
        val client = freshClient()
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()
        val bob = client.users.create { name = "B"; email = "b@example.com" }
            .saveAndLoad(testViewerContext).getOrThrow()

        // Retargeting bob's email to "a@example.com" trips the
        // unique-email constraint.
        val result = client.users.update(bob.id) {
            email = "a@example.com"
        }.save(testViewerContext)

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals(EntOperation.UPDATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("23505", ex.driverCode)
        assertNotNull(ex.constraint)
        assertTrue(ex.constraint!!.contains("email"), "constraint should mention email: ${ex.constraint}")
    }

    @Test
    fun `getOrThrow throws the exact stored EntConstraintViolationException on update`() {
        val client = freshClient()
        client.users.create { name = "C"; email = "c@example.com" }.save(testViewerContext).getOrThrow()
        val dan = client.users.create { name = "D"; email = "d@example.com" }
            .saveAndLoad(testViewerContext).getOrThrow()

        val result = client.users.update(dan.id) { email = "c@example.com" }.saveAndLoad(testViewerContext)
        val failed = assertIs<MutationResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntConstraintViolationException) {
            assertSame(failed.exception, e)
            assertEquals(EntOperation.UPDATE, e.operation)
            assertEquals("23505", e.driverCode)
        }
    }

    @Test
    fun `save returns Failed(EntConstraintViolationException) for FK violation on update`() {
        val client = freshClient()
        val author = client.users.create { name = "E"; email = "e@example.com" }
            .saveAndLoad(testViewerContext).getOrThrow()
        val article = client.articles.create {
            title = "Hello"
            published = true
            authorId = author.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        // Repoint authorId to a non-existent user.
        val result = client.articles.update(article.id) {
            authorId = 999_999L
        }.save(testViewerContext)

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.UPDATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("23503", ex.driverCode)
        assertNotNull(ex.constraint)
    }

    @Test
    fun `a unique violation leaves the owner row unchanged`() {
        val client = freshClient()
        client.users.create { name = "F"; email = "f@example.com" }.save(testViewerContext).getOrThrow()
        val guy = client.users.create { name = "G"; email = "g@example.com" }
            .saveAndLoad(testViewerContext).getOrThrow()

        assertIs<MutationResult.Failed>(
            client.users.update(guy.id) {
                name = "Guy"
                email = "f@example.com"
            }.save(testViewerContext),
        )

        // The conflicting update did not partially apply.
        val reread = client.users.findById(testViewerContext, guy.id).getOrThrow()!!
        assertEquals("g@example.com", reread.email)
        assertEquals("G", reread.name)
    }

    // ---- Target absence ----

    @Test
    fun `update of a missing target is Failed(EntTargetAbsentException)`() {
        val client = freshClient()

        val result = client.users.update(999_999L) { name = "ghost" }.saveAndLoad(testViewerContext)

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntTargetAbsentException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals("id", ex.key.field)
        assertEquals(999_999L, ex.key.value)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
    }

    @Test
    fun `an assignment-free update of a missing target is still Failed(EntTargetAbsentException)`() {
        val client = freshClient()

        // The no-op path establishes target existence before succeeding.
        val result = client.users.update(999_999L) { }.save(testViewerContext)

        val failed = assertIs<MutationResult.Failed>(result)
        assertIs<EntTargetAbsentException>(failed.exception)
    }

    // ---- Assignment-free (no-op) updates ----

    @Test
    fun `an assignment-free update succeeds without writing and skips post-persist hooks`() {
        val recording = RecordingDriver(resetAndDriver())
        var beforeUpdates = 0
        var afterUpdates = 0
        val client = EntClient(recording) {

            policies { users(OpenUser); articles(AllowAll) }
            hooks {
                users {
                    beforeUpdate { beforeUpdates++ }
                    afterUpdate { afterUpdates++ }
                }
            }
        }
        val user = client.users.create { name = "N"; email = "n@example.com" }
            .saveAndLoad(testViewerContext).getOrThrow()

        recording.reset()
        val result = client.users.update(user.id) { }.save(testViewerContext)

        assertEquals(MutationResult.Success(Unit), result)
        // Pre-write phases ran; persist and post-persist were skipped.
        assertEquals(1, beforeUpdates)
        assertEquals(0, afterUpdates)
        // Target-existence check reads; nothing writes — no update
        // synthesized from defaults either.
        assertEquals(0, recording.callCount("update:"))
        assertTrue(recording.callCount("byId:") >= 1, "target-existence check must read the row")

        val reread = client.users.findById(testViewerContext, user.id).getOrThrow()!!
        assertEquals("N", reread.name)
    }

    @Test
    fun `a no-op saveAndLoad returns the current row`() {
        val client = freshClient()
        val user = client.users.create { name = "O"; email = "o@example.com" }
            .saveAndLoad(testViewerContext).getOrThrow()

        val loaded = client.users.update(user.id) { }.saveAndLoad(testViewerContext).getOrThrow()
        assertEquals(user.id, loaded.id)
        assertEquals("O", loaded.name)
    }

    @Test
    fun `explicitly assigned equal values still write`() {
        val recording = RecordingDriver(resetAndDriver())
        var afterUpdates = 0
        val client = EntClient(recording) {

            policies { users(OpenUser); articles(AllowAll) }
            hooks { users { afterUpdate { afterUpdates++ } } }
        }
        val user = client.users.create { name = "P"; email = "p@example.com" }
            .saveAndLoad(testViewerContext).getOrThrow()

        recording.reset()
        // Assigning the current value is still an assignment: the write
        // happens and post-persist hooks run.
        val result = client.users.update(user.id) { name = "P" }.save(testViewerContext)

        assertEquals(MutationResult.Success(Unit), result)
        assertEquals(1, recording.callCount("update:"))
        assertEquals(1, afterUpdates)
    }

    // ---- Pre-write privacy: UPDATE operation, keyed ----

    @Test
    fun `a pre-write UPDATE privacy rejection carries operation UPDATE and NotPersisted`() {
        val viewerContext = ViewerContext(Viewer.User(1L))
        val denyUpdate = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow })
                    update(ArticleUpdatePrivacyRule { _, _ -> PrivacyDecision.Deny("update denied") })
                }
            }
        }
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies { articles(denyUpdate); users(OpenUser) }
        }
        val article = run {
            val sys = client
            val testViewerContext = testBypassContext("test")
            val author = sys.users.create { name = "Q"; email = "q@example.com" }
                .saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "T"; published = true; authorId = author.id }
                .saveAndLoad(testViewerContext).getOrThrow()
        }

        val result = client.articles.update(article.id) { title = "T2" }.save(viewerContext)

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        // The denied *mutation* operation — distinguishable from a
        // post-write LOAD disclosure denial (operation = LOAD), which is
        // pinned in WriteSucceededLoadDeniedIntegrationTest.
        assertEquals(EntOperation.UPDATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("Article", ex.entityType)
        assertEquals("id", ex.entityKey?.field)
        assertEquals(article.id, ex.entityKey?.value)
        assertEquals("update denied", ex.reason)

        // Nothing was written.
        val reread = run {
            val sys = client
            val testViewerContext = testBypassContext("test")
            sys.articles.findById(testViewerContext, article.id).getOrThrow()!!
        }
        assertEquals("T", reread.title)
    }
}
