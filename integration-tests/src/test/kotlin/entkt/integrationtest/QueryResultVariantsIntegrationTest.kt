package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.runtime.EntError
import entkt.runtime.EntNotFoundException
import entkt.runtime.EntOperation
import entkt.runtime.EntPrivacyDeniedException
import entkt.runtime.EntResult
import entkt.runtime.EntityPolicy
import entkt.runtime.InMemoryDriver
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
 * End-to-end coverage for the query-level read-API trio (Phase 5b/c
 * of the Result Variants RFC):
 *
 *   firstOrNull         — keep (absence → null; denial throws)
 *   firstOrThrow        — absence → EntNotFoundException;
 *                         denial → EntPrivacyDeniedException
 *   firstOrError        — Err(NotFound) / Err(PrivacyDenied) /
 *                         Err(DriverFailure)
 *   firstVisibleOrNull  — absence OR invisibility → null
 *   allOrThrow          — replace legacy `all()`; throws on any
 *                         denial
 *   allOrError          — Err(PrivacyDenied) / Err(DriverFailure)
 *   visibleAll          — drop denied rows from the result list
 *   visibleAllOrError   — same wrapped in EntResult
 */
class QueryResultVariantsIntegrationTest {

    /** Allow exactly one specific article — denies all others. */
    private fun pinPolicy(allowedTitle: String) = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy {
                load(ArticleLoadPrivacyRule { ctx ->
                    if (ctx.entity.title == allowedTitle) PrivacyDecision.Allow
                    else PrivacyDecision.Deny("not '$allowedTitle'")
                })
            }
        }
    }

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private val denyAllArticles = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Deny("hidden") }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.System,
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAllArticles,
    ): EntClient {
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
        }
    }

    /** Seed three articles under System and return them in insertion order. */
    private fun seedThree(client: EntClient): Triple<Article, Article, Article> {
        return client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            val first = sys.articles.create { title = "First"; published = true; authorId = author.id }.saveOrThrow()
            val second = sys.articles.create { title = "Second"; published = true; authorId = author.id }.saveOrThrow()
            val third = sys.articles.create { title = "Third"; published = true; authorId = author.id }.saveOrThrow()
            Triple(first, second, third)
        }
    }

    // ---- allOrThrow ----

    @Test
    fun `allOrThrow returns every matching row when LOAD allows`() {
        val client = freshClient()
        seedThree(client)

        val all = client.articles.query().allOrThrow()
        assertEquals(3, all.size)
    }

    @Test
    fun `allOrThrow throws EntPrivacyDeniedException when any row is denied`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("First"))
        seedThree(client)

        // allOrThrow wraps allOrError().getOrThrow(), so the throw is
        // the structured EntPrivacyDeniedException (not the raw
        // PrivacyDeniedException). This is consistent with the rest
        // of the *OrThrow family (byIdOrThrow / firstOrThrow /
        // saveOrThrow / deleteOrThrow).
        val ex = assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query().allOrThrow()
        }
        assertEquals(EntOperation.LOAD, ex.privacyDenied.operation)
    }

    // ---- allOrError ----

    @Test
    fun `allOrError returns Ok when every row is visible`() {
        val client = freshClient()
        seedThree(client)

        val result = client.articles.query().allOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(3, result.value.size)
    }

    @Test
    fun `allOrError returns Err(PrivacyDenied) on any denied row`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("First"))
        seedThree(client)

        val result = client.articles.query().allOrError()
        assertTrue(result is EntResult.Err)
        assertTrue(result.error is EntError.PrivacyDenied)
        assertEquals(EntOperation.LOAD, result.error.operation)
    }

    // ---- visibleAll ----

    @Test
    fun `visibleAll returns only the rows the viewer can LOAD`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("Second"))
        seedThree(client)

        val visible = client.articles.query().visibleAll()
        assertEquals(1, visible.size)
        assertEquals("Second", visible[0].title)
    }

    @Test
    fun `visibleAll returns empty list when nothing is visible`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        seedThree(client)

        val visible = client.articles.query().visibleAll()
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `visibleAll skips privacy evaluation when the repo has no LOAD rules`() {
        // System viewer's privacy is bypassed but we still want to
        // confirm the fast path through `!hasLoadPrivacy()` returns
        // every row instead of evaluating per-row.
        val client = freshClient()
        seedThree(client)

        val visible = client.articles.query().visibleAll()
        assertEquals(3, visible.size)
    }

    // ---- visibleAllOrError ----

    @Test
    fun `visibleAllOrError returns Ok with visible rows even when others are denied`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("Third"))
        seedThree(client)

        val result = client.articles.query().visibleAllOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(1, result.value.size)
        assertEquals("Third", result.value[0].title)
    }

    @Test
    fun `visibleAllOrError maps eager-edge LOAD denial to Err(PrivacyDenied), not DriverFailure`() {
        // Article LOAD allows all; the *eager-loaded* User edge denies.
        // The visible-filter on Article doesn't drop the row (Article
        // is allowed), but loadEdges then raises PrivacyDeniedException
        // when LOAD-checking the eager User target. Before the catch
        // arm was added, this misclassified as Err(DriverFailure).
        val denyAllUsers = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy { load(UserLoadPrivacyRule { PrivacyDecision.Deny("user hidden") }) }
            }
        }
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                articles(AllowAllArticles)
                users(denyAllUsers)
            }
        }
        // Seed via System (privacy bypass) so we have a User row to
        // eager-load.
        client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            sys.articles.create { title = "X"; published = true; authorId = author.id }.saveOrThrow()
        }

        val result = client.articles.query { withAuthor() }.visibleAllOrError()
        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(
            error is EntError.PrivacyDenied,
            "expected PrivacyDenied for eager-edge denial; got $error",
        )
        assertEquals(EntOperation.LOAD, error.operation)
        assertEquals("user hidden", error.reason)
    }

    @Test
    fun `visibleAll throws raw PrivacyDeniedException on eager-edge denial (visible filtering is root-only)`() {
        // Per the RFC's "Visible-only API contract" section:
        // visibleAll's filter applies only to the root entity. Eager-
        // loaded edge targets via withAuthor() / withTags() / etc.
        // still enforce target LOAD privacy strictly — a denied
        // target throws PrivacyDeniedException rather than silently
        // dropping the root row. This pins the documented carve-out.
        val denyAllUsers = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy { load(UserLoadPrivacyRule { PrivacyDecision.Deny("user hidden") }) }
            }
        }
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                articles(AllowAllArticles)
                users(denyAllUsers)
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            sys.articles.create { title = "X"; published = true; authorId = author.id }.saveOrThrow()
        }

        kotlin.test.assertFailsWith<PrivacyDeniedException> {
            client.articles.query { withAuthor() }.visibleAll()
        }
    }

    // ---- firstOrNull (unchanged contract — null only for empty match) ----

    @Test
    fun `firstOrNull returns null only for empty matches`() {
        val client = freshClient()
        seedThree(client)

        val none = client.articles.query { where(Article.title eq "Missing") }.firstOrNull()
        assertNull(none)

        val first = client.articles.query().firstOrNull()
        assertNotNull(first)
    }

    @Test
    fun `firstOrNull throws on privacy denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        seedThree(client)

        assertFailsWith<PrivacyDeniedException> {
            client.articles.query().firstOrNull()
        }
    }

    // ---- firstOrThrow ----

    @Test
    fun `firstOrThrow returns the first matching row`() {
        val client = freshClient()
        val (first, _, _) = seedThree(client)

        val loaded = client.articles.query().firstOrThrow()
        assertEquals(first.id, loaded.id)
    }

    @Test
    fun `firstOrThrow throws EntNotFoundException on empty match`() {
        val client = freshClient()

        val ex = assertFailsWith<EntNotFoundException> {
            client.articles.query().firstOrThrow()
        }
        assertEquals("Article", ex.notFound.entity)
        assertEquals(EntOperation.QUERY, ex.notFound.operation)
        // first/empty has no specific id to attribute the miss to.
        assertNull(ex.notFound.id)
    }

    @Test
    fun `firstOrThrow throws structured EntPrivacyDeniedException on denial`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        seedThree(client)

        val ex = assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query().firstOrThrow()
        }
        assertEquals("Article", ex.privacyDenied.entity)
    }

    // ---- firstOrError ----

    @Test
    fun `firstOrError returns Ok with the first matching row`() {
        val client = freshClient()
        val (first, _, _) = seedThree(client)

        val result = client.articles.query().firstOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(first.id, result.value.id)
    }

    @Test
    fun `firstOrError returns Err(NotFound) on empty match`() {
        val client = freshClient()

        val result = client.articles.query().firstOrError()
        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.NotFound)
        assertEquals(EntOperation.QUERY, error.operation)
    }

    @Test
    fun `firstOrError returns Err(PrivacyDenied) on denied first row`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        seedThree(client)

        val result = client.articles.query().firstOrError()
        assertTrue(result is EntResult.Err)
        assertTrue(result.error is EntError.PrivacyDenied)
    }

    // ---- firstVisibleOrNull ----

    @Test
    fun `firstVisibleOrNull returns first visible row, skipping denied ones`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = pinPolicy("Second"))
        seedThree(client)

        val loaded = client.articles.query().firstVisibleOrNull()
        assertNotNull(loaded)
        assertEquals("Second", loaded.title)
    }

    @Test
    fun `firstVisibleOrNull returns null when nothing is visible`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        seedThree(client)

        assertNull(client.articles.query().firstVisibleOrNull())
    }

    @Test
    fun `firstVisibleOrNull returns null on empty match`() {
        val client = freshClient()

        assertNull(client.articles.query { where(Article.title eq "X") }.firstVisibleOrNull())
    }

    // ---- visibleOverfetchLimit / cap ----

    /** Build a client whose cap is intentionally small so cap-exhaustion is easy to trigger. */
    private fun freshClientWithCap(
        cap: Int,
        viewer: Viewer,
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope>,
    ): EntClient {
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
            visibleOverfetchLimit = cap
        }
    }

    private fun seedNArticles(client: EntClient, n: Int) {
        client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            repeat(n) { i ->
                sys.articles.create {
                    title = "Article-$i"
                    published = true
                    authorId = author.id
                }.saveOrThrow()
            }
        }
    }

    @Test
    fun `visibleAll caps the storage scan at visibleOverfetchLimit`() {
        val client = freshClientWithCap(
            cap = 3,
            viewer = Viewer.User(1L),
            articlePolicy = AllowAllArticles,
        )
        seedNArticles(client, n = 10)

        // 10 rows exist but the cap pulls only 3.
        val visible = client.articles.query().visibleAll()
        assertEquals(3, visible.size)
    }

    @Test
    fun `visibleAllOrError returns Err(OverfetchCapExceeded) when cap is hit`() {
        val client = freshClientWithCap(
            cap = 3,
            viewer = Viewer.User(1L),
            articlePolicy = AllowAllArticles,
        )
        seedNArticles(client, n = 10)

        val result = client.articles.query().visibleAllOrError()
        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.OverfetchCapExceeded)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.QUERY, error.operation)
        assertEquals(3, error.cap)
    }

    @Test
    fun `visibleAllOrError returns Ok when query naturally fits inside the cap`() {
        val client = freshClientWithCap(
            cap = 100,
            viewer = Viewer.User(1L),
            articlePolicy = AllowAllArticles,
        )
        seedNArticles(client, n = 3)

        val result = client.articles.query().visibleAllOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(3, result.value.size)
    }

    @Test
    fun `visibleAllOrError respects user queryLimit when smaller than cap`() {
        val client = freshClientWithCap(
            cap = 100,
            viewer = Viewer.User(1L),
            articlePolicy = AllowAllArticles,
        )
        seedNArticles(client, n = 10)

        // queryLimit 2 < cap 100 → no cap exhaustion, return at most 2.
        val result = client.articles.query { limit(2) }.visibleAllOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(2, result.value.size)
    }

    @Test
    fun `firstVisibleOrNull cap-exhaustion is silent (returns null)`() {
        // All articles deny, cap = 3 — firstVisibleOrNull scans 3
        // rows, finds none visible, returns null. No Err surface
        // here per the RFC's optimistic-read shape.
        val client = freshClientWithCap(
            cap = 3,
            viewer = Viewer.User(1L),
            articlePolicy = denyAllArticles,
        )
        seedNArticles(client, n = 10)

        assertNull(client.articles.query().firstVisibleOrNull())
    }

    // ---- No-LOAD-privacy fast paths (cap is skipped entirely) ----

    /**
     * Builds a fresh InMemoryDriver-backed client whose Article
     * policy has NO LOAD privacy rules — the "visible" APIs collapse
     * to "all" because there's nothing to filter in-process.
     */
    private fun freshClientNoPrivacy(cap: Int): EntClient {
        val noLoadPolicy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                // No `load(...)` rules — repo.hasLoadPrivacy() == false.
            }
        }
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        return EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                articles(noLoadPolicy)
                users(OpenUser)
            }
            visibleOverfetchLimit = cap
        }
    }

    @Test
    fun `visibleAll returns every row when the repo has no LOAD privacy (cap is skipped)`() {
        val client = freshClientNoPrivacy(cap = 3)
        seedNArticles(client, n = 10)

        // 10 rows exist; the no-privacy fast path skips the cap so
        // all 10 come back. Without the fix, this would return 3.
        val visible = client.articles.query().visibleAll()
        assertEquals(10, visible.size)
    }

    @Test
    fun `visibleAllOrError returns Ok with every row and no OverfetchCapExceeded when repo has no LOAD privacy`() {
        val client = freshClientNoPrivacy(cap = 3)
        seedNArticles(client, n = 10)

        // Without the fix, the cap-exhaustion check would fire and
        // return Err(OverfetchCapExceeded) — but there's nothing to
        // filter, so "exhaustion" has no meaning here.
        val result = client.articles.query().visibleAllOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(10, result.value.size)
    }

    @Test
    fun `firstVisibleOrNull fetches at most one row when repo has no LOAD privacy`() {
        // Indirect verification: with cap=3 and 10 rows, the fix
        // changes the driver query limit from 3 to 1. We can't
        // observe the driver limit directly, but we can confirm the
        // result is correct (first row by storage order) and equals
        // what allOrThrow returns first.
        val client = freshClientNoPrivacy(cap = 3)
        seedNArticles(client, n = 10)

        val firstVisible = client.articles.query().firstVisibleOrNull()
        val firstAll = client.articles.query().allOrThrow().first()
        assertEquals(firstAll.id, firstVisible?.id)
    }
}
