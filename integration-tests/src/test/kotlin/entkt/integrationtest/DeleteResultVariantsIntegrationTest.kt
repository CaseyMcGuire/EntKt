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
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import kotlin.Unit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the canonical delete family:
 *
 *   delete(entity): MutationResult<Unit>   — the entity is an id
 *       handle; Success(Unit) whether this call deleted the freshly
 *       reloaded row or found it already absent (absence runs no
 *       callbacks and is never EntTargetAbsentException).
 *   deleteById(id): MutationResult<Boolean> — Success(true) only when
 *       this call's final delete removed the row; Success(false) when
 *       absent before or during the operation.
 *   deleteMany(predicates): MutationResult<Int> — one transaction
 *       across candidate selection and every row's callbacks/deletes;
 *       one failing row leaves no committed subset.
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
        config: entkt.integrationtest.ent.EntClientConfig.() -> Unit = {},
    ): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
            config()
        }
    }

    private fun seedArticle(client: EntClient, title: String = "Hello"): Article {
        return client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.query().firstOrNull().getOrThrow()
                ?: sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
            sys.articles.create {
                this.title = title
                published = true
                authorId = author.id
            }.saveAndLoad().getOrThrow()
        }
    }

    // ---- delete(entity) ----

    @Test
    fun `delete removes the row and returns Success(Unit)`() {
        val client = freshClient()
        val article = seedArticle(client)

        val result = client.articles.delete(article)

        assertEquals(MutationResult.Success(Unit), result)
        assertEquals(0L, client.articles.query().rawCount().getOrThrow())
    }

    @Test
    fun `delete of an already-absent row is Success(Unit) and runs no callbacks`() {
        var beforeDeletes = 0
        var afterDeletes = 0
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { articles(AllowAllArticles); users(OpenUser) }
            hooks {
                articles {
                    beforeDelete { beforeDeletes++ }
                    afterDelete { afterDeletes++ }
                }
            }
        }
        val article = seedArticle(client)

        assertEquals(MutationResult.Success(Unit), client.articles.delete(article))
        assertEquals(1, beforeDeletes)
        assertEquals(1, afterDeletes)

        // Second delete: the row is absent at reload — Success(Unit),
        // and neither before- nor after-delete callbacks run.
        assertEquals(MutationResult.Success(Unit), client.articles.delete(article))
        assertEquals(1, beforeDeletes)
        assertEquals(1, afterDeletes)
    }

    @Test
    fun `delete returns Failed(EntMutationPrivacyDeniedException) when DELETE denies`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val result = client.articles.delete(article)

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.DELETE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("delete denied", ex.reason)
        assertEquals("id", ex.entityKey?.field)
        assertEquals(article.id, ex.entityKey?.value)

        // Row still present.
        assertEquals(1L, client.articles.query().rawCount().getOrThrow())
    }

    @Test
    fun `getOrThrow throws the exact stored exception on delete denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val result = client.articles.delete(article)
        val failed = assertIs<MutationResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntMutationPrivacyDeniedException) {
            assertSame(failed.exception, e)
        }
    }

    // ---- deleteById ----

    @Test
    fun `deleteById returns Success(true) when a row was deleted`() {
        val client = freshClient()
        val article = seedArticle(client)

        val result = client.articles.deleteById(article.id)
        val success = assertIs<MutationResult.Success<Boolean>>(result)
        assertTrue(success.value)
        assertEquals(0L, client.articles.query().rawCount().getOrThrow())
    }

    @Test
    fun `deleteById returns Success(false) when no row existed`() {
        val client = freshClient()

        val result = client.articles.deleteById(999_999L)
        val success = assertIs<MutationResult.Success<Boolean>>(result)
        assertFalse(success.value)
    }

    @Test
    fun `deleteById returns Failed when DELETE denies and leaves the row`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val result = client.articles.deleteById(article.id)
        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.DELETE, ex.operation)
        // Row still there.
        assertEquals(1L, client.articles.query().rawCount().getOrThrow())
    }

    // ---- deleteMany atomicity ----

    @Test
    fun `deleteMany removes every matching row atomically`() {
        val client = freshClient()
        seedArticle(client, "One")
        seedArticle(client, "Two")
        seedArticle(client, "Keep")

        val result = client.articles.deleteMany(Article.published.eq(true))
        // All three match published = true.
        assertEquals(MutationResult.Success(3), result)
        assertEquals(0L, client.articles.query().rawCount().getOrThrow())
    }

    @Test
    fun `deleteMany whose first candidate fails keeps the typed exception and deletes nothing`() {
        // Every candidate is denied, so the FIRST candidate fails before
        // any delete staged — the typed failure passes through unchanged
        // and NotPersisted is the honest state after confirmed rollback.
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        seedArticle(client, "One")
        seedArticle(client, "Two")
        seedArticle(client, "Three")

        val result = client.articles.deleteMany(Article.published.eq(true))

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.DELETE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("delete denied", ex.reason)

        // No committed subset, no silent skipping.
        assertEquals(3L, client.articles.query().rawCount().getOrThrow())
    }

    @Test
    fun `deleteMany failing after a staged delete reports the typed failure as cause and deletes nothing`() {
        // A stateful rule lets the FIRST candidate (whatever the driver's
        // candidate order) delete, then denies the second — guaranteeing
        // a staged write precedes the failure without depending on row
        // order. The staged-then-failed batch is re-reported at the
        // conversion boundary as NotPersisted (rollback confirmed) with
        // the typed row failure as the direct cause.
        var deleteCalls = 0
        val denySecond = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { PrivacyDecision.Allow })
                    delete(ArticleDeletePrivacyRule {
                        if (deleteCalls++ == 0) PrivacyDecision.Allow
                        else PrivacyDecision.Deny("second candidate blocked")
                    })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denySecond)
        seedArticle(client, "One")
        seedArticle(client, "Two")
        seedArticle(client, "Three")

        val result = client.articles.deleteMany(Article.published.eq(true))

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<entkt.runtime.result.EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        val cause = assertIs<EntMutationPrivacyDeniedException>(ex.cause)
        assertEquals(EntOperation.DELETE, cause.operation)
        assertEquals("second candidate blocked", cause.reason)

        // Rollback confirmed: the first candidate's staged delete is
        // undone — no committed subset.
        assertEquals(3L, client.articles.query().rawCount().getOrThrow())
    }
}
