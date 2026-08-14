package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.visibleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Projections are pure: `getOrThrow()` and `visibleOrNull()` operate
 * only on the already-produced result — zero driver calls, no privacy
 * re-evaluation, no scanning. Proven with a delegating counting
 * [RecordingDriver] over the real Postgres driver (the sanctioned
 * narrow-fake pattern).
 */
class ReadProjectionPurityIntegrationTest : PostgresTestBase() {

    private object AllowAll : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private val denyArticles = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Deny("hidden") }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private fun recordingClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAll,
    ): Pair<EntClient, RecordingDriver> {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
        }
        return client to recording
    }

    private fun seedArticle(client: EntClient): Article =
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad().getOrThrow()
            sys.articles.create { title = "T"; published = true; authorId = author.id }
                .saveAndLoad().getOrThrow()
        }

    @Test
    fun `getOrThrow and visibleOrNull on a successful read perform zero driver calls`() {
        val (client, recording) = recordingClient()
        val article = seedArticle(client)

        val result = client.articles.findById(article.id)
        assertIs<ReadResult.Success<Article?>>(result)

        recording.reset()
        assertEquals(article.id, result.getOrThrow()?.id)
        assertEquals(article.id, result.visibleOrNull().getOrThrow()?.id)
        assertEquals(0, recording.callCount(), "projections must not touch the driver; saw ${recording.calls}")
    }

    @Test
    fun `visibleOrNull on a Root denial performs zero driver calls and no privacy re-evaluation`() {
        var ruleEvaluations = 0
        val countingDeny = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule {
                        ruleEvaluations++
                        PrivacyDecision.Deny("hidden")
                    })
                }
            }
        }
        val (client, recording) = recordingClient(viewer = Viewer.User(1L), articlePolicy = countingDeny)
        val article = seedArticle(client)

        val result = client.articles.findById(article.id)
        assertIs<ReadResult.Failed>(result)
        val evaluationsAtProduce = ruleEvaluations

        recording.reset()
        val projected = result.visibleOrNull()
        assertNull(assertIs<ReadResult.Success<Article?>>(projected).value)
        assertEquals(0, recording.callCount(), "visibleOrNull must not touch the driver; saw ${recording.calls}")
        assertEquals(evaluationsAtProduce, ruleEvaluations, "visibleOrNull must not re-evaluate privacy")
    }

    @Test
    fun `getOrThrow on a failed read performs zero driver calls and throws the stored instance`() {
        val (client, recording) = recordingClient(viewer = Viewer.User(1L), articlePolicy = denyArticles)
        val article = seedArticle(client)

        val result = client.articles.findById(article.id)
        val failed = assertIs<ReadResult.Failed>(result)

        recording.reset()
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntPrivacyDeniedException) {
            assertSame(failed.exception, e)
        }
        assertEquals(0, recording.callCount(), "getOrThrow must not touch the driver; saw ${recording.calls}")
    }

    @Test
    fun `mutation getOrThrow performs zero driver calls`() {
        val (client, recording) = recordingClient()

        // Validation failure: produced without reaching the driver's
        // write path; the projection must add nothing.
        val result = client.users.create { name = "NoEmail" }.save()
        assertIs<MutationResult.Failed>(result)

        recording.reset()
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: Exception) {
            // expected
        }
        assertEquals(0, recording.callCount(), "mutation getOrThrow must not touch the driver; saw ${recording.calls}")
    }

    @Test
    fun `transaction getOrThrow performs zero driver calls`() {
        val (client, recording) = recordingClient()

        val result = client.withTransaction { tx ->
            tx.users.create { name = "NoEmail" }.save().orRollback()
        }
        assertIs<TransactionResult.Failed>(result)

        recording.reset()
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: Exception) {
            // expected
        }
        assertEquals(0, recording.callCount(), "transaction getOrThrow must not touch the driver; saw ${recording.calls}")
    }
}
