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
import entkt.runtime.privacy.allowAll
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the canonical query terminals:
 *
 *   all(): ReadResult<List<E>>  — strict: evaluates the full selected
 *       window; ANY denied root row fails the terminal with an
 *       ordered, keyed denial list (no hydrated data, no partial list).
 *   firstOrNull(): ReadResult<E?> — one-row SQL window; a denied
 *       selected first row is Failed(Root, one denial) and no second
 *       row is ever consulted.
 *
 * The former visible*-scanning family (visibleAll, firstVisibleOrNull,
 * visibleAllOrError, the overfetch cap) is removed by design — root
 * visibility filtering is no longer a read-terminal concern.
 */
class QueryResultVariantsIntegrationTest : PostgresTestBase() {

    /** Allow exactly one specific article — denies all others. */
    private fun pinPolicy(allowedTitle: String) = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy {
                load(ArticleLoadPrivacyRule { _, item ->
                    if (item.entity.title == allowedTitle) PrivacyDecision.Allow
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
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
        }
    }

    /** Seed three articles under a bypass viewer, in insertion order. */
    private fun seedThree(client: EntClient): Triple<Article, Article, Article> {
        return client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad().getOrThrow()
            val first = sys.articles.create { title = "First"; published = true; authorId = author.id }
                .saveAndLoad().getOrThrow()
            val second = sys.articles.create { title = "Second"; published = true; authorId = author.id }
                .saveAndLoad().getOrThrow()
            val third = sys.articles.create { title = "Third"; published = true; authorId = author.id }
                .saveAndLoad().getOrThrow()
            Triple(first, second, third)
        }
    }

    // ---- all(): Success ----

    @Test
    fun `all returns every matching row when LOAD allows`() {
        val client = freshClient()
        seedThree(client)

        val result = client.articles.query().all()
        val success = assertIs<ReadResult.Success<List<Article>>>(result)
        assertEquals(3, success.value.size)
    }

    @Test
    fun `all returns Success(emptyList()) for no rows`() {
        val client = freshClient()

        assertEquals(emptyList(), client.articles.query().all().getOrThrow())
    }

    @Test
    fun `all with limit(0) returns Success(emptyList())`() {
        val client = freshClient()
        seedThree(client)

        assertEquals(emptyList(), client.articles.query { limit(0) }.all().getOrThrow())
    }

    // ---- all(): strict full-window denial aggregation ----

    @Test
    fun `all lists every denied root row in encountered order with keys but no hydrated data`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("Second"))
        val (first, _, third) = seedThree(client)

        val result = client.articles.query { orderBy(Article.id.asc()) }.all()
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
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
    fun `all never returns a partial list after denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("Second"))
        seedThree(client)

        // Strictness: the one visible row is NOT returned — the terminal
        // is Failed, not a filtered Success.
        assertIs<ReadResult.Failed>(client.articles.query().all())
    }

    @Test
    fun `all evaluates only the selected window`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("First"))
        seedThree(client)

        // Window = first row only (by id); the denied Second/Third rows
        // are outside the window, so the read succeeds.
        val result = client.articles.query { orderBy(Article.id.asc()); limit(1) }.all()
        val success = assertIs<ReadResult.Success<List<Article>>>(result)
        assertEquals(listOf("First"), success.value.map { it.title })
    }

    @Test
    fun `an ordinary rule exception beats an incomplete denial aggregate`() {
        val boom = IllegalStateException("rule blew up")
        val explodingPolicy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { _, item ->
                        when (item.entity.title) {
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
        val failed = assertIs<ReadResult.Failed>(
            client.articles.query { orderBy(Article.id.asc()) }.all(),
        )
        assertSame(boom, failed.exception)
    }

    // ---- firstOrNull ----

    @Test
    fun `firstOrNull returns Success(null) only for empty matches`() {
        val client = freshClient()
        seedThree(client)

        assertNull(
            client.articles.query { where(Article.title eq "Missing") }.firstOrNull().getOrThrow(),
        )
        assertNotNull(client.articles.query().firstOrNull().getOrThrow())
    }

    @Test
    fun `firstOrNull returns the first row of the ordered window`() {
        val client = freshClient()
        val (first, _, _) = seedThree(client)

        // Explicit orderBy so the test pins "first by id" rather than
        // relying on Postgres's unspecified default row order.
        val loaded = client.articles.query { orderBy(Article.id.asc()) }.firstOrNull().getOrThrow()
        assertEquals(first.id, loaded?.id)
    }

    @Test
    fun `firstOrNull with limit(0) returns Success(null)`() {
        val client = freshClient()
        seedThree(client)

        val result = client.articles.query { limit(0) }.firstOrNull()
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
                        evaluated.add(item.entity.id)
                        PrivacyDecision.Deny("hidden")
                    })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = recordingDenyPolicy)
        val (first, _, _) = seedThree(client)

        val result = client.articles.query { orderBy(Article.id.asc()) }.firstOrNull()
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

        val result = client.articles.query { orderBy(Article.id.asc()) }.firstOrNull()
        val failed = assertIs<ReadResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntPrivacyDeniedException) {
            assertSame(failed.exception, e)
        }
    }
}
