package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.EntError
import entkt.runtime.EntNotFoundException
import entkt.runtime.EntOperation
import entkt.runtime.EntPrivacyDeniedException
import entkt.runtime.EntResult
import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.PrivacyDeniedException
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the repo-level byId family (Phase 5a of the
 * Result Variants RFC). Pins the contract for each variant:
 *
 *  - `byIdOrNull(id): T?`         absence → null; denial throws raw
 *                                 PrivacyDeniedException
 *  - `byIdOrThrow(id): T`         absence → EntNotFoundException;
 *                                 denial → EntPrivacyDeniedException
 *  - `visibleByIdOrNull(id): T?`  absence OR denial → null
 *  - `byIdOrError(id): EntResult` absence → Err(NotFound); denial →
 *                                 Err(PrivacyDenied)
 */
class ByIdResultVariantsIntegrationTest : PostgresTestBase() {

    private val denyAllArticles = ArticleLoadPrivacyRule { PrivacyDecision.Deny("article hidden") }

    private object AllowAll : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private val denyArticles = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(denyAllArticles) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.System,
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAll,
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
        return client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            sys.articles.create {
                title = "Hello"
                published = true
                authorId = author.id
            }.saveOrThrow()
        }
    }

    // ---- byIdOrNull ----

    @Test
    fun `byIdOrNull returns the entity when visible`() {
        val client = freshClient()
        val article = seedArticle(client)

        val loaded = client.articles.byIdOrNull(article.id)
        assertNotNull(loaded)
        assertEquals(article.id, loaded.id)
    }

    @Test
    fun `byIdOrNull returns null for missing rows`() {
        val client = freshClient()

        assertNull(client.articles.byIdOrNull(999_999L))
    }

    @Test
    fun `byIdOrNull throws raw PrivacyDeniedException when LOAD denies`() {
        val client = freshClient(
            viewer = Viewer.User(1L),
            articlePolicy = denyArticles,
        )
        val article = seedArticle(client)

        assertFailsWith<PrivacyDeniedException> {
            client.articles.byIdOrNull(article.id)
        }
    }

    // ---- byIdOrThrow ----

    @Test
    fun `byIdOrThrow returns the entity when visible`() {
        val client = freshClient()
        val article = seedArticle(client)

        val loaded = client.articles.byIdOrThrow(article.id)
        assertEquals(article.id, loaded.id)
    }

    @Test
    fun `byIdOrThrow throws EntNotFoundException carrying EntError NotFound for missing rows`() {
        val client = freshClient()

        val ex = assertFailsWith<EntNotFoundException> {
            client.articles.byIdOrThrow(999_999L)
        }
        assertEquals("Article", ex.notFound.entity)
        assertEquals(EntOperation.LOAD, ex.notFound.operation)
        assertEquals(999_999L, ex.notFound.id)
    }

    @Test
    fun `byIdOrThrow throws structured EntPrivacyDeniedException on denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyArticles)
        val article = seedArticle(client)

        val ex = assertFailsWith<EntPrivacyDeniedException> {
            client.articles.byIdOrThrow(article.id)
        }
        assertEquals("Article", ex.privacyDenied.entity)
        assertEquals(EntOperation.LOAD, ex.privacyDenied.operation)
        assertEquals("article hidden", ex.privacyDenied.reason)
    }

    // ---- visibleByIdOrNull ----

    @Test
    fun `visibleByIdOrNull returns the entity when visible`() {
        val client = freshClient()
        val article = seedArticle(client)

        val loaded = client.articles.visibleByIdOrNull(article.id)
        assertEquals(article.id, loaded?.id)
    }

    @Test
    fun `visibleByIdOrNull returns null for missing rows`() {
        val client = freshClient()

        assertNull(client.articles.visibleByIdOrNull(999_999L))
    }

    @Test
    fun `visibleByIdOrNull returns null when LOAD denies`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyArticles)
        val article = seedArticle(client)

        // The contract: invisibility AND absence both collapse to null;
        // the caller can't distinguish them by this API alone (use
        // byIdOrError for that).
        assertNull(client.articles.visibleByIdOrNull(article.id))
    }

    // ---- byIdOrError ----

    @Test
    fun `byIdOrError returns Ok when visible`() {
        val client = freshClient()
        val article = seedArticle(client)

        val result = client.articles.byIdOrError(article.id)
        assertTrue(result is EntResult.Ok)
        assertEquals(article.id, result.value.id)
    }

    @Test
    fun `byIdOrError returns Err(NotFound) for missing rows`() {
        val client = freshClient()

        val result = client.articles.byIdOrError(999_999L)
        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.NotFound)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.LOAD, error.operation)
        assertEquals(999_999L, error.id)
    }

    @Test
    fun `byIdOrError returns Err(PrivacyDenied) when LOAD denies`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyArticles)
        val article = seedArticle(client)

        val result = client.articles.byIdOrError(article.id)
        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.PrivacyDenied)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.LOAD, error.operation)
        assertEquals("article hidden", error.reason)
    }
}
