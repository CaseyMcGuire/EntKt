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
import entkt.runtime.privacy.ViewerContext
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
 *       across candidate selection, phase-major batch callbacks, and
 *       one logical set-based delete; one failing item leaves no
 *       committed subset.
 */
class DeleteResultVariantsIntegrationTest : PostgresTestBase() {
    private var viewerContext = testViewerContext

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private val denyDelete = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy {
                load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow })
                delete(ArticleDeletePrivacyRule { _, _ -> PrivacyDecision.Deny("delete denied") })
            }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAllArticles,
        config: entkt.integrationtest.ent.EntClientConfig.() -> Unit = {},
    ): EntClient {
        viewerContext = ViewerContext(viewer)
        val driver = resetAndDriver()
        return EntClient(driver) {

            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
            config()
        }
    }

    private fun seedArticle(
        client: EntClient,
        title: String = "Hello",
        payload: ByteArray? = null,
    ): Article {
        return run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val author = sys.users.query().firstOrNull(viewerContext).getOrThrow()
                ?: sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(viewerContext).getOrThrow()
            sys.articles.create {
                this.title = title
                published = true
                authorId = author.id
                this.payload = payload
            }.saveAndLoad(viewerContext).getOrThrow()
        }
    }

    // ---- delete(entity) ----

    @Test
    fun `delete removes the row and returns Success(Unit)`() {
        val client = freshClient()
        val article = seedArticle(client)

        val result = client.articles.delete(viewerContext, article)

        assertEquals(MutationResult.Success(Unit), result)
        assertEquals(0L, client.articles.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `delete of an already-absent row is Success(Unit) and runs no callbacks`() {
        var beforeDeletes = 0
        var afterDeletes = 0
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies { articles(AllowAllArticles); users(OpenUser) }
            hooks {
                articles {
                    beforeDelete { beforeDeletes++ }
                    afterDelete { afterDeletes++ }
                }
            }
        }
        val article = seedArticle(client)

        assertEquals(MutationResult.Success(Unit), client.articles.delete(viewerContext, article))
        assertEquals(1, beforeDeletes)
        assertEquals(1, afterDeletes)

        // Second delete: the row is absent at reload — Success(Unit),
        // and neither before- nor after-delete callbacks run.
        assertEquals(MutationResult.Success(Unit), client.articles.delete(viewerContext, article))
        assertEquals(1, beforeDeletes)
        assertEquals(1, afterDeletes)
    }

    @Test
    fun `delete returns Failed(EntMutationPrivacyDeniedException) when DELETE denies`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val result = client.articles.delete(viewerContext, article)

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.DELETE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("delete denied", ex.reason)
        assertEquals("id", ex.entityKey?.field)
        assertEquals(article.id, ex.entityKey?.value)

        // Row still present.
        assertEquals(1L, client.articles.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `getOrThrow throws the exact stored exception on delete denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val result = client.articles.delete(viewerContext, article)
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

        val result = client.articles.deleteById(viewerContext, article.id)
        val success = assertIs<MutationResult.Success<Boolean>>(result)
        assertTrue(success.value)
        assertEquals(0L, client.articles.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `deleteById returns Success(false) when no row existed`() {
        val client = freshClient()

        val result = client.articles.deleteById(viewerContext, 999_999L)
        val success = assertIs<MutationResult.Success<Boolean>>(result)
        assertFalse(success.value)
    }

    @Test
    fun `deleteById returns Failed when DELETE denies and leaves the row`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyDelete)
        val article = seedArticle(client)

        val result = client.articles.deleteById(viewerContext, article.id)
        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.DELETE, ex.operation)
        // Row still there.
        assertEquals(1L, client.articles.query().all(viewerContext).getOrThrow().size.toLong())
    }

    // ---- deleteMany atomicity ----

    @Test
    fun `deleteMany removes every matching row atomically`() {
        val client = freshClient()
        seedArticle(client, "One")
        seedArticle(client, "Two")
        seedArticle(client, "Keep")

        val result = client.articles.deleteMany(viewerContext, Article.published.eq(true))
        // All three match published = true.
        assertEquals(MutationResult.Success(3), result)
        assertEquals(0L, client.articles.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `deleteMany reasserts a frozen mutable predicate operand`() {
        val operand = byteArrayOf(1)
        var beforeDeletes = 0
        val client = freshClient {
            hooks {
                articles {
                    beforeDelete {
                        beforeDeletes++
                        operand[0] = 2
                    }
                }
            }
        }
        seedArticle(client, title = "Frozen", payload = byteArrayOf(1))

        val result = client.articles.deleteMany(viewerContext, Article.payload eq operand)

        assertEquals(MutationResult.Success(1), result)
        assertEquals(1, beforeDeletes)
        assertEquals(0L, client.articles.query().all(viewerContext).getOrThrow().size.toLong())
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

        val result = client.articles.deleteMany(viewerContext, Article.published.eq(true))

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.DELETE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("delete denied", ex.reason)

        // No committed subset, no silent skipping.
        assertEquals(3L, client.articles.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `deleteMany evaluates every candidate privacy decision before writing`() {
        // The scalar rule adapts over the ordered batch. Its first decision
        // allows and its second denies, but phase-major preflight means the
        // denial is still a direct NotPersisted failure: no candidate has
        // reached the set-based delete.
        var deleteCalls = 0
        val denySecond = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow })
                    delete(ArticleDeletePrivacyRule { _, _ ->
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

        val result = client.articles.deleteMany(viewerContext, Article.published.eq(true))

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals(EntOperation.DELETE, ex.operation)
        assertEquals("second candidate blocked", ex.reason)

        // Every candidate remains because the denial preceded persistence.
        assertEquals(3L, client.articles.query().all(viewerContext).getOrThrow().size.toLong())
    }
}
