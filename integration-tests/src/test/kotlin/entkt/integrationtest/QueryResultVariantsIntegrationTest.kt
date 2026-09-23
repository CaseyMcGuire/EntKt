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
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import entkt.runtime.result.ReadCollectionResult
import entkt.runtime.result.deniedAsNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the canonical query terminals:
 *
 *   all(): ReadCollectionResult<E> — evaluates the full selected window;
 *       each root retains its success or keyed denial without denied entity data.
 *       getOrThrow aggregates root denials; deniedAsNull preserves their slots.
 *   firstOrNull(): ReadResult<E?> — one-row SQL window; a denied
 *       selected first row is Failed(Root, one denial) and no second
 *       row is ever consulted.
 *
 * The former visible*-scanning family (visibleAll, firstVisibleOrNull,
 * visibleAllOrError, the overfetch cap) is removed by design — root
 * visibility filtering is no longer a read-terminal concern.
 */
class QueryResultVariantsIntegrationTest : PostgresTestBase() {
    private var viewerContext = testViewerContext

    /** Allow exactly one specific article — denies all others. */
    private fun pinPolicy(allowedTitle: String) = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy {
                load(ArticleLoadPrivacyRule { _, item ->
                    if (item.title == allowedTitle) PrivacyDecision.Allow
                    else PrivacyDecision.Deny("not '$allowedTitle'")
                })
            }
        }
    }

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(allowAll) }
        }
    }

    private val denyAllArticles = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("hidden") }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(allowAll) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAllArticles,
    ): EntClient {
        viewerContext = ViewerContext(viewer)
        val driver = resetAndDriver()
        return EntClient(driver) {

            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
        }
    }

    /** Seed three articles under a bypass viewer, in insertion order. */
    private fun seedThree(client: EntClient): Triple<Article, Article, Article> {
        return run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val author = sys.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad(viewerContext).getOrThrow()
            val first = sys.articles.create { title = "First"; published = true; authorId = author.id }
                .saveAndLoad(viewerContext).getOrThrow()
            val second = sys.articles.create { title = "Second"; published = true; authorId = author.id }
                .saveAndLoad(viewerContext).getOrThrow()
            val third = sys.articles.create { title = "Third"; published = true; authorId = author.id }
                .saveAndLoad(viewerContext).getOrThrow()
            Triple(first, second, third)
        }
    }

    // ---- all(): Completed ----

    @Test
    fun `all returns every matching row when LOAD allows`() {
        val client = freshClient()
        seedThree(client)

        val result = client.articles.query().all(viewerContext)
        val completed = assertIs<ReadCollectionResult.Completed<Article>>(result)
        assertEquals(3, completed.getOrThrow().size)
    }

    @Test
    fun `all returns Completed(emptyList()) for no rows`() {
        val client = freshClient()

        assertEquals(emptyList(), client.articles.query().all(viewerContext).getOrThrow())
    }

    @Test
    fun `all with limit(0) returns Completed(emptyList())`() {
        val client = freshClient()
        seedThree(client)

        assertEquals(emptyList(), client.articles.query { limit(0) }.all(viewerContext).getOrThrow())
    }

    // ---- all(): strict full-window denial aggregation ----

    @Test
    fun `all lists every denied root row in encountered order with keys but no hydrated data`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("Second"))
        val (first, second, third) = seedThree(client)

        val result = client.articles.query { orderBy(Article.id.asc()) }.all(viewerContext)
        val completed = assertIs<ReadCollectionResult.Completed<Article>>(result)
        val entries = completed.entities()
        assertEquals(3, entries.size)
        assertIs<ReadResult.Failed>(entries[0])
        assertEquals(second.id, assertIs<ReadResult.Success<Article>>(entries[1]).value.id)
        assertIs<ReadResult.Failed>(entries[2])
        assertEquals(listOf(null, second.id, null), result.deniedAsNull().getOrThrow().map { it?.id })
        val ex = assertFailsWith<EntPrivacyDeniedException> { result.getOrThrow() }
        assertIs<LoadDenialOrigin.Root>(ex.origin)
        // Both denied rows are listed, in the query's encountered order.
        assertEquals(2, ex.denials.size)
        assertEquals(listOf(first.id, third.id), ex.denials.map { it.entityKey.value })
        for (denial in ex.denials) {
            assertEquals("Article", denial.entityType)
            assertEquals("id", denial.entityKey.field)
            assertTrue(denial.reason.startsWith("not "), "rule-supplied reason expected; got ${denial.reason}")
        }
    }

    @Test
    fun `strict unwrapping never returns a partial list after denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("Second"))
        seedThree(client)

        assertFailsWith<EntPrivacyDeniedException> { client.articles.query().all(viewerContext).getOrThrow() }
    }

    @Test
    fun `all evaluates only the selected window`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("First"))
        seedThree(client)

        // Window = first row only (by id); the denied Second/Third rows
        // are outside the window, so the read succeeds.
        val result = client.articles.query { orderBy(Article.id.asc()); limit(1) }.all(viewerContext)
        val completed = assertIs<ReadCollectionResult.Completed<Article>>(result)
        assertEquals(listOf("First"), completed.getOrThrow().map { it.title })
    }

    @Test
    fun `an ordinary rule exception beats an incomplete denial aggregate`() {
        val boom = IllegalStateException("rule blew up")
        val explodingPolicy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { _, item ->
                        when (item.title) {
                            "Second" -> throw boom
                            else -> PrivacyDecision.Deny("hidden")
                        }
                    })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = explodingPolicy)
        seedThree(client)

        // First is denied, Second's rule throws: the ordinary exception is
        // the stored failure — never a partial EntPrivacyDeniedException.
        val failed = assertIs<ReadCollectionResult.Failed>(
            client.articles.query { orderBy(Article.id.asc()) }.all(viewerContext),
        )
        assertSame(boom, failed.exception)
        assertSame(failed, failed.deniedAsNull())
        assertSame(boom, assertFailsWith<IllegalStateException> { failed.deniedAsNull().getOrThrow() })
    }

    @Test
    fun `all denied roots project to null slots while empty results remain empty`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        assertEquals(emptyList(), client.articles.query().all(viewerContext).deniedAsNull().getOrThrow())
        seedThree(client)

        val result = client.articles.query { orderBy(Article.id.asc()) }.all(viewerContext)

        assertEquals(listOf(null, null, null), result.deniedAsNull().getOrThrow())
        assertEquals(3, assertFailsWith<EntPrivacyDeniedException> { result.getOrThrow() }.denials.size)
    }

    @Test
    fun `null projection preserves the selected offset window without refilling`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("Second"))
        val (_, second, _) = seedThree(client)

        val result = client.articles.query { orderBy(Article.id.asc()); offset(1); limit(2) }.all(viewerContext)

        assertEquals(listOf(second.id, null), result.deniedAsNull().getOrThrow().map { it?.id })
    }

    // ---- firstOrNull ----

    @Test
    fun `firstOrNull returns Success(null) only for empty matches`() {
        val client = freshClient()
        seedThree(client)

        assertNull(
            client.articles.query { where(Article.title eq "Missing") }.firstOrNull(viewerContext).getOrThrow(),
        )
        assertNotNull(client.articles.query().firstOrNull(viewerContext).getOrThrow())
    }

    @Test
    fun `firstOrNull returns the first row of the ordered window`() {
        val client = freshClient()
        val (first, _, _) = seedThree(client)

        // Explicit orderBy so the test pins "first by id" rather than
        // relying on Postgres's unspecified default row order.
        val loaded = client.articles.query { orderBy(Article.id.asc()) }.firstOrNull(viewerContext).getOrThrow()
        assertEquals(first.id, loaded?.id)
    }

    @Test
    fun `firstOrNull with limit(0) returns Success(null)`() {
        val client = freshClient()
        seedThree(client)

        val result = client.articles.query { limit(0) }.firstOrNull(viewerContext)
        val success = assertIs<ReadResult.Success<Article?>>(result)
        assertNull(success.value)
    }

    @Test
    fun `firstOrNull denied first row is Failed with one keyed denial and never scans row two`() {
        val evaluated = mutableListOf<Long>()
        val recordingDenyPolicy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { _, item ->
                        evaluated.add(item.id)
                        PrivacyDecision.Deny("hidden")
                    })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = recordingDenyPolicy)
        val (first, _, _) = seedThree(client)

        val result = client.articles.query { orderBy(Article.id.asc()) }.firstOrNull(viewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(ex.origin)
        assertEquals(1, ex.denials.size)
        assertEquals(first.id, ex.denials.single().entityKey.value)
        // Strict posture: the SQL window is one row; privacy ran exactly
        // once, on that row. No replacement scanning happened.
        assertEquals(listOf(first.id), evaluated)
    }

    @Test
    fun `firstOrNull denial getOrThrow throws the stored exception instance`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        seedThree(client)

        val result = client.articles.query { orderBy(Article.id.asc()) }.firstOrNull(viewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntPrivacyDeniedException) {
            assertSame(failed.exception, e)
        }
    }
}
