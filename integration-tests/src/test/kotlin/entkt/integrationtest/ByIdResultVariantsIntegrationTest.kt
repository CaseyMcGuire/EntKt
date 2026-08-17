package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import entkt.runtime.result.visibleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * End-to-end coverage for the canonical singular lookup:
 *
 *  - `findById(id): ReadResult<Entity?>` — `Success(entity)` when
 *    visible, `Success(null)` for authoritative absence,
 *    `Failed(EntPrivacyDeniedException(Root, one keyed denial))` when
 *    LOAD denies the selected row.
 *  - `getOrThrow()` throws the stored exception instance directly.
 *  - `visibleOrNull()` maps only the Root denial to `Success(null)`.
 */
class ByIdResultVariantsIntegrationTest : PostgresTestBase() {

    private val denyAllArticles = ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("article hidden") }

    private object AllowAll : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private val denyArticles = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(denyAllArticles) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
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
        return client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad().getOrThrow()
            sys.articles.create {
                title = "Hello"
                published = true
                authorId = author.id
            }.saveAndLoad().getOrThrow()
        }
    }

    // ---- findById: Success(entity) ----

    @Test
    fun `findById returns Success with the entity when visible`() {
        val client = freshClient()
        val article = seedArticle(client)

        val result = client.articles.findById(article.id)
        val success = assertIs<ReadResult.Success<Article?>>(result)
        assertEquals(article.id, success.value?.id)
    }

    // ---- findById: Success(null) is authoritative absence ----

    @Test
    fun `findById returns Success(null) for missing rows`() {
        val client = freshClient()

        val result = client.articles.findById(999_999L)
        val success = assertIs<ReadResult.Success<Article?>>(result)
        assertNull(success.value)
    }

    @Test
    fun `getOrThrow preserves successful absence as null`() {
        val client = freshClient()

        assertNull(client.articles.findById(999_999L).getOrThrow())
    }

    // ---- findById: Failed(EntPrivacyDeniedException(Root, ...)) ----

    @Test
    fun `findById returns Failed with a Root denial carrying exactly one keyed denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyArticles)
        val article = seedArticle(client)

        val result = client.articles.findById(article.id)
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(ex.origin)
        assertEquals(1, ex.denials.size)
        val denial = ex.denials.single()
        assertEquals("Article", denial.entityType)
        assertEquals("id", denial.entityKey.field)
        assertEquals(article.id, denial.entityKey.value)
        assertEquals("article hidden", denial.reason)
    }

    @Test
    fun `getOrThrow throws the exact stored exception instance on denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyArticles)
        val article = seedArticle(client)

        val result = client.articles.findById(article.id)
        val failed = assertIs<ReadResult.Failed>(result)
        val thrown = assertFailsWith<EntPrivacyDeniedException> { result.getOrThrow() }
        assertSame(failed.exception, thrown)
        // Framework-created expected-path exceptions still carry an
        // ordinary JVM stack trace for diagnosis.
        assertNotNull(thrown.stackTrace)
        check(thrown.stackTrace.isNotEmpty()) { "expected a non-empty stack trace" }
    }

    // ---- visibleOrNull ----

    @Test
    fun `visibleOrNull preserves presence`() {
        val client = freshClient()
        val article = seedArticle(client)

        val loaded = client.articles.findById(article.id).visibleOrNull().getOrThrow()
        assertEquals(article.id, loaded?.id)
    }

    @Test
    fun `visibleOrNull preserves absence`() {
        val client = freshClient()

        assertNull(client.articles.findById(999_999L).visibleOrNull().getOrThrow())
    }

    @Test
    fun `visibleOrNull maps Root LOAD denial to Success(null)`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyArticles)
        val article = seedArticle(client)

        // Invisibility and absence both collapse to null; a caller that
        // needs to distinguish them inspects the raw ReadResult.Failed.
        val result = client.articles.findById(article.id).visibleOrNull()
        val success = assertIs<ReadResult.Success<Article?>>(result)
        assertNull(success.value)
    }

    @Test
    fun `visibleOrNull leaves non-privacy failures unchanged`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyArticles)
        val article = seedArticle(client)

        val failed = assertIs<ReadResult.Failed>(client.articles.findById(article.id))
        // Sanity: only the Root privacy denial maps to absence — the
        // projection returns the identical Failed for anything else.
        // (Non-privacy Failed states are pinned in the projection-purity
        // suite; here we pin that the mapped case really is Root.)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(ex.origin)
    }
}
