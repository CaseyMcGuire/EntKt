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
import entkt.runtime.result.EntError
import entkt.runtime.result.EntNotFoundException
import entkt.runtime.result.EntResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the boundary-input validation added to query bounds + the
 * client-config overfetch cap. Prior to validation:
 *  - `limit(-1)` / `offset(-1)` propagated to the driver and surfaced
 *    as a SQL syntax error on Postgres — one layer removed from the
 *    caller.
 *  - `visibleOverfetchLimit = 0` made the cap-exhaustion check
 *    `rows.size >= cap` always true, so `visibleAllOrError()` would
 *    return `Err(OverfetchCapExceeded)` for every query — including
 *    queries that returned zero rows.
 *
 * Now: each is `require`-rejected at the setter boundary so the
 * caller sees the bad input immediately.
 */
class QueryBoundsValidationIntegrationTest : PostgresTestBase() {

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
        }
    }

    @Test
    fun `limit rejects negative values at the boundary`() {
        val client = freshClient()
        val ex = assertFailsWith<IllegalArgumentException> {
            client.articles.query { limit(-1) }
        }
        assertTrue(
            ex.message!!.contains("limit must be non-negative"),
            "message should explain the constraint: ${ex.message}",
        )
    }

    @Test
    fun `limit allows zero (caller-explicit empty result)`() {
        val client = freshClient()
        // zero is meaningful — "I don't want any rows, just exercise the path."
        client.articles.query { limit(0) }
    }

    @Test
    fun `offset rejects negative values at the boundary`() {
        val client = freshClient()
        val ex = assertFailsWith<IllegalArgumentException> {
            client.articles.query { offset(-1) }
        }
        assertTrue(
            ex.message!!.contains("offset must be non-negative"),
            "message should explain the constraint: ${ex.message}",
        )
    }

    @Test
    fun `visibleOverfetchLimit rejects zero`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            val driver = resetAndDriver()
            EntClient(driver) {
                visibleOverfetchLimit = 0
            }
        }
        assertTrue(
            ex.message!!.contains("visibleOverfetchLimit must be positive"),
            "message should explain the constraint: ${ex.message}",
        )
    }

    @Test
    fun `visibleOverfetchLimit rejects negative values`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            val driver = resetAndDriver()
            EntClient(driver) {
                visibleOverfetchLimit = -5
            }
        }
        assertTrue(ex.message!!.contains("visibleOverfetchLimit must be positive"))
    }

    // ---- limit(0) is honored by every terminal family ----
    //
    // The first-row terminals used to send a hardwired `limit = 1` and
    // discard the caller's bound, so `query { limit(0) }.firstOrNull()`
    // returned a row while `query { limit(0) }.rawExists()` — same
    // query, same rows — returned false. Interceptor limit mutators are
    // silent no-ops at FIRST (InterceptorEngine.limitOpsApply), so the
    // discarded value was always the caller's own.

    private fun seededClient(): EntClient = freshClient().also { client ->
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            sys.articles.create { title = "First"; published = true; authorId = author.id }.saveOrThrow()
            sys.articles.create { title = "Second"; published = true; authorId = author.id }.saveOrThrow()
        }
    }

    @Test
    fun `limit(0) returns no row from firstOrNull`() {
        val client = seededClient()

        assertNull(client.articles.query { limit(0) }.firstOrNull())
        // Sanity: the rows are really there without the bound.
        assertNotNull(client.articles.query().firstOrNull())
    }

    @Test
    fun `limit(0) returns no row from firstVisibleOrNull`() {
        val client = seededClient()

        assertNull(client.articles.query { limit(0) }.firstVisibleOrNull())
        assertNotNull(client.articles.query().firstVisibleOrNull())
    }

    @Test
    fun `limit(0) makes firstOrError report NotFound`() {
        val client = seededClient()

        // "No rows within the caller's bound" is the same outcome as
        // "no rows matched" — NotFound, not a row.
        val result = client.articles.query { limit(0) }.firstOrError()
        assertTrue(result is EntResult.Err, "expected Err(NotFound); was $result")
        assertTrue(result.error is EntError.NotFound, "expected NotFound; was ${result.error}")
    }

    @Test
    fun `limit(0) makes firstOrThrow report NotFound`() {
        val client = seededClient()

        assertFailsWith<EntNotFoundException> {
            client.articles.query { limit(0) }.firstOrThrow()
        }
    }

    @Test
    fun `limit(0) agrees across every terminal that reads rows`() {
        val client = seededClient()

        // The consistency the fix is really about: one bound, one
        // answer, from every terminal that actually reads rows.
        assertNull(client.articles.query { limit(0) }.firstOrNull())
        assertEquals(false, client.articles.query { limit(0) }.rawExists())
        assertEquals(false, client.articles.query { limit(0) }.visibleExists())
        assertEquals(emptyList(), client.articles.query { limit(0) }.allOrThrow())
        assertEquals(emptyList(), client.articles.query { limit(0) }.visibleAll())
        // visibleCount materializes rows to evaluate privacy, so it's
        // bounded like any other row read rather than being an exception.
        assertEquals(0L, client.articles.query { limit(0) }.visibleCount())
    }

    @Test
    fun `limit does not apply to terminals that never materialize rows`() {
        val client = seededClient()

        // rawCount and the raw aggregates lower to COUNT(*) / an
        // aggregate function and never receive a limit, which is the
        // documented contract — pin the boundary so "limit(0) means no
        // rows" isn't mistaken for a universal rule.
        assertEquals(2L, client.articles.query { limit(0) }.rawCount())
    }

    @Test
    fun `explainFirstOrNull reports the limit the terminal will actually send`() {
        val client = seededClient()

        // explain* is only useful if it mirrors the runtime; a plan
        // pinned at 1 would hide the caller's bound.
        val plan = client.articles.query { limit(0) }.explainFirstOrNull()
        assertNotNull(plan.root)
        val desc = plan.root.toString()
        assertTrue(
            desc.contains("LIMIT 0") || desc.contains("limit=0") || desc.contains("limit: 0"),
            "explainFirstOrNull with limit(0) should show limit 0; was: $desc",
        )
    }

    @Test
    fun `a positive limit still returns a row from the first-row terminals`() {
        val client = seededClient()

        // The clamp is min(limit, 1) — anything above zero is already
        // satisfied by the single-row fetch.
        assertNotNull(client.articles.query { limit(1) }.firstOrNull())
        assertNotNull(client.articles.query { limit(5) }.firstOrNull())
        assertNotNull(client.articles.query { limit(5) }.firstVisibleOrNull())
    }
}
