package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleDeletePrivacyRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.result.EntError
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntResult
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the delete family.
 *
 *   deleteOrThrow(entity): Unit         throws on real failures;
 *                                       silent on missing-row
 *   deleteOrError(entity): EntResult<Unit>
 *                                       Ok(Unit) on success/no-op;
 *                                       Err for real failures
 *   deleteByIdOrError(id): EntResult<Boolean>
 *                                       Ok(true) = deleted,
 *                                       Ok(false) = no-op,
 *                                       Err for real failures
 *
 * The legacy `delete(entity): Boolean` and `deleteById(id): Boolean`
 * are removed (not deprecated) so callers reach for the explicit
 * structured variants.
 */
class DeleteResultVariantsIntegrationTest : PostgresTestBase() {

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private val denyDelete = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy {
                load(ArticleLoadPrivacyRule { PrivacyDecision.Allow })
                delete(ArticleDeletePrivacyRule { PrivacyDecision.Deny("delete denied") })
            }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAllArticles,
    ): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
        }
    }

    private fun seedArticle(client: EntClient): Article {
        return client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            sys.articles.create {
                title = "Hello"
                published = true
                authorId = author.id
            }.saveOrThrow()
        }
    }

    // ---- deleteOrThrow ----

    @Test
    fun `deleteOrThrow removes the row when DELETE allows`() {
        val client = freshClient()
        val article = seedArticle(client)

        client.articles.deleteOrThrow(article)

        assertEquals(0L, client.articles.query().rawCount())
    }

    @Test
    fun `deleteOrThrow throws structured EntPrivacyDeniedException when DELETE denies`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val ex = assertFailsWith<EntPrivacyDeniedException> {
            client.articles.deleteOrThrow(article)
        }
        assertEquals("Article", ex.privacyDenied.entity)
        assertEquals(EntOperation.DELETE, ex.privacyDenied.operation)
        assertEquals("delete denied", ex.privacyDenied.reason)

        // Row still present.
        assertEquals(1L, client.articles.query().rawCount())
    }

    // ---- deleteOrError ----

    @Test
    fun `deleteOrError returns Ok(Unit) on success`() {
        val client = freshClient()
        val article = seedArticle(client)

        val result = client.articles.deleteOrError(article)
        assertTrue(result is EntResult.Ok)
        assertEquals(Unit, result.value)
    }

    @Test
    fun `deleteOrError returns Err(PrivacyDenied) when DELETE denies`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val result = client.articles.deleteOrError(article)
        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.PrivacyDenied)
        assertEquals(EntOperation.DELETE, error.operation)
        assertEquals("delete denied", error.reason)
    }

    @Test
    fun `deleteOrError treats missing row as Ok(Unit) silently`() {
        val client = freshClient()
        val article = seedArticle(client)

        // First delete succeeds; second sees no row but doesn't surface that as Err.
        client.articles.deleteOrThrow(article)
        val result = client.articles.deleteOrError(article)
        assertTrue(result is EntResult.Ok)
    }

    // ---- deleteByIdOrError ----

    @Test
    fun `deleteByIdOrError returns Ok(true) when a row was deleted`() {
        val client = freshClient()
        val article = seedArticle(client)

        val result = client.articles.deleteByIdOrError(article.id)
        assertTrue(result is EntResult.Ok)
        assertTrue(result.value)
        assertEquals(0L, client.articles.query().rawCount())
    }

    @Test
    fun `deleteByIdOrError returns Ok(false) when no row existed (idempotent no-op)`() {
        val client = freshClient()

        val result = client.articles.deleteByIdOrError(999_999L)
        assertTrue(result is EntResult.Ok)
        assertFalse(result.value)
    }

    @Test
    fun `deleteByIdOrError returns Err(PrivacyDenied) when DELETE denies`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val result = client.articles.deleteByIdOrError(article.id)
        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.PrivacyDenied)
        assertEquals(EntOperation.DELETE, error.operation)
        // Row still there.
        assertEquals(1L, client.articles.query().rawCount())
    }
}
