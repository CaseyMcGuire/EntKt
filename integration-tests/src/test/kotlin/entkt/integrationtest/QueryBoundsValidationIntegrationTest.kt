package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.Driver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.EntError
import entkt.runtime.result.EntNotFoundException
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    // ---- eager-load bounds apply per parent, whatever the cardinality ----

    @Test
    fun `limit(0) on a to-one eager load yields no target`() {
        val client = seededClient()

        // `withAuthor { limit(0) }` used to load the author anyway: the
        // to-one eager path passed null limit/offset on the reasoning
        // that "limit is meaningless for a to-one". Positive limits are
        // indeed already satisfied by one target per parent — but zero
        // is a bound, not a no-op.
        val articles = client.articles.query { withAuthor { limit(0) } }.allOrThrow()

        assertTrue(articles.isNotEmpty(), "the root rows still load")
        assertTrue(
            articles.all { it.edges.author == null },
            "limit(0) should leave the eager target unloaded",
        )
    }

    @Test
    fun `offset on a to-one eager load steps past the only candidate`() {
        val client = seededClient()

        val articles = client.articles.query { withAuthor { offset(1) } }.allOrThrow()

        assertTrue(
            articles.all { it.edges.author == null },
            "offset(1) skips the single target the parent has",
        )
    }

    @Test
    fun `an unbounded to-one eager load still loads the target`() {
        val client = seededClient()

        // Guard against over-correcting: with no bounds set, the window
        // is wide open and behavior is unchanged.
        val articles = client.articles.query { withAuthor() }.allOrThrow()

        assertTrue(articles.isNotEmpty())
        assertTrue(
            articles.all { it.edges.author != null },
            "an unbounded eager load must be unaffected",
        )
        // A positive limit is already satisfied by the single target.
        assertTrue(
            client.articles.query { withAuthor { limit(1) } }.allOrThrow()
                .all { it.edges.author != null },
        )
    }

    @Test
    fun `to-one and to-many eager loads agree on limit(0)`() {
        val client = seededClient()

        // The inconsistency this fixes: to-many already sliced per
        // parent, so the two cardinalities answered `limit(0)`
        // differently for no reason the caller could see.
        val users = client.users.query { withArticles { limit(0) } }.allOrThrow()
        assertTrue(users.isNotEmpty())
        // `== true` rather than `orEmpty()`: this must distinguish
        // "eagerly loaded and empty" from "never loaded" (null).
        assertTrue(users.all { it.edges.articles?.isEmpty() == true }, "to-many honors limit(0)")

        val articles = client.articles.query { withAuthor { limit(0) } }.allOrThrow()
        assertTrue(articles.all { it.edges.author == null }, "to-one now honors it too")
    }

    // ---- an empty eager window must not touch the target at all ----

    private object DeniedUsers : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Deny("user is hidden") }) }
        }
    }

    /** Articles readable, their authors denied to the current viewer. */
    private fun clientWithDeniedAuthors(): EntClient {
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User("reader")) }
            policies {
                articles(AllowAllArticles)
                users(DeniedUsers)
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            sys.articles.create { title = "First"; published = true; authorId = author.id }.saveOrThrow()
        }
        return client
    }

    @Test
    fun `limit(0) on a to-one eager load does not evaluate the excluded target`() {
        val client = clientWithDeniedAuthors()

        // Eager-target denial is strict — it throws rather than filtering.
        // But a target excluded by the caller's own window was never
        // requested, so denying it reports a decision about a row nobody
        // asked to load. Gating only the final assignment left the fetch,
        // the privacy check, and nested eager loading all still running.
        val articles = client.articles.query { withAuthor { limit(0) } }.allOrThrow()

        assertTrue(articles.isNotEmpty())
        assertTrue(articles.all { it.edges.author == null })
    }

    @Test
    fun `offset past a to-one eager target does not evaluate it`() {
        val client = clientWithDeniedAuthors()

        val articles = client.articles.query { withAuthor { offset(1) } }.allOrThrow()

        assertTrue(articles.all { it.edges.author == null })
    }

    @Test
    fun `an in-window denied eager target still throws`() {
        val client = clientWithDeniedAuthors()

        // The other half of the contract: skipping privacy for an empty
        // window must not weaken the strict denial when the caller does
        // ask for the target.
        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query { withAuthor() }.allOrThrow()
        }
        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query { withAuthor { limit(1) } }.allOrThrow()
        }
    }

    @Test
    fun `an empty eager window still fires the target's EAGER_LOAD interceptors`() {
        val driver = resetAndDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
            interceptors {
                users(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "user-observer")
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            sys.articles.create { title = "First"; published = true; authorId = author.id }.saveOrThrow()
        }
        ops.clear()

        client.articles.query { withAuthor { limit(0) } }.allOrThrow()

        // The bound decides which rows survive, not whether the eager
        // subquery exists. Interceptors fire on every eager subquery —
        // `explain()` does it unconditionally, and the to-many paths fire
        // before slicing — so skipping them here would make an
        // interceptor's view of the query depend on the caller's limit.
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "withAuthor { limit(0) } should still fire User interceptors with EAGER_LOAD; observed: $ops",
        )
    }

    /** Records which tables were queried, delegating everything else. */
    private class QueryCountingDriver(private val real: Driver) : Driver by real {
        val queriedTables = mutableListOf<String>()

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            queriedTables += table
            return real.query(table, predicates, orderBy, limit, offset)
        }
    }

    private fun countingClient(): Pair<EntClient, QueryCountingDriver> {
        val counting = QueryCountingDriver(resetAndDriver())
        val client = EntClient(counting) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            sys.articles.create { title = "First"; published = true; authorId = author.id }.saveOrThrow()
        }
        counting.queriedTables.clear()
        return client to counting
    }

    @Test
    fun `an empty to-one eager window issues no query for the target`() {
        val (client, counting) = countingClient()

        client.articles.query { withAuthor { limit(0) } }.allOrThrow()

        assertTrue(counting.queriedTables.contains("articles"), "the root query still runs")
        assertFalse(
            counting.queriedTables.contains("users"),
            "no row could survive the window, so the target fetch is pure waste; queried: ${counting.queriedTables}",
        )
    }

    @Test
    fun `an empty to-many eager window issues no query for the target`() {
        val (client, counting) = countingClient()

        client.users.query { withArticles { limit(0) } }.allOrThrow()

        assertTrue(counting.queriedTables.contains("users"), "the root query still runs")
        assertFalse(
            counting.queriedTables.contains("articles"),
            "same waste on the to-many path; queried: ${counting.queriedTables}",
        )
    }

    @Test
    fun `an empty many-to-many eager window issues no query for the target`() {
        val counting = QueryCountingDriver(resetAndDriver())
        val client = EntClient(counting) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        // A real link matters: with no junction rows the eager block
        // short-circuits before the target fetch, so the test would pass
        // without exercising the window at all.
        val post = client.posts.create { title = "p" }.saveOrThrow()
        val tag = client.tags.create { name = "t" }.saveOrThrow()
        client.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tag.id) }.saveOrThrow() }
        counting.queriedTables.clear()

        val posts = client.posts.query { withTags { limit(0) } }.allOrThrow()

        assertTrue(posts.all { it.edges.tags?.isEmpty() == true })
        assertFalse(
            counting.queriedTables.contains("tags"),
            "no tag could survive the window; queried: ${counting.queriedTables}",
        )
        // The junction query is deliberately still issued: its rows
        // produce the target ids the EAGER_LOAD interceptor pass
        // predicates on, and interceptors fire on every eager subquery.
        assertTrue(
            counting.queriedTables.contains("post_tags"),
            "the junction query feeds the interceptor pass; queried: ${counting.queriedTables}",
        )
    }

    @Test
    fun `a non-empty many-to-many window still issues the target query`() {
        val counting = QueryCountingDriver(resetAndDriver())
        val client = EntClient(counting) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        // A real link matters: with no junction rows the eager block
        // short-circuits before the target fetch, so the test would pass
        // without exercising the window at all.
        val post = client.posts.create { title = "p" }.saveOrThrow()
        val tag = client.tags.create { name = "t" }.saveOrThrow()
        client.withTransaction { tx -> tx.posts.update(post.id) { tags.add(tag.id) }.saveOrThrow() }
        counting.queriedTables.clear()

        client.posts.query { withTags { limit(5) } }.allOrThrow()

        assertTrue(counting.queriedTables.contains("tags"), "queried: ${counting.queriedTables}")
    }

    @Test
    fun `a non-empty eager window still issues the target query`() {
        val (client, counting) = countingClient()

        // Guard against over-correcting the skip into always-skip.
        val articles = client.articles.query { withAuthor { limit(1) } }.allOrThrow()

        assertTrue(articles.all { it.edges.author != null })
        assertTrue(counting.queriedTables.contains("users"), "queried: ${counting.queriedTables}")
    }

    @Test
    fun `eager interceptors fire even when no relationship data exists`() {
        val driver = resetAndDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
            interceptors {
                users(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "user-observer")
                tags(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "tag-observer")
            }
        }
        // An article with no author and a post with no tags: both eager
        // subqueries have an empty structural IN. Whether an interceptor
        // runs — and whether it can reject — must not depend on which
        // rows happen to carry relationships.
        // Reminder.assignee is the nullable-belongsTo fixture; Article's
        // author is required, so it can't model "every FK is null".
        client.reminders.create { body = "unassigned" }.saveOrThrow()
        client.posts.create { title = "untagged" }.saveOrThrow()
        ops.clear()

        client.reminders.query { withAssignee() }.allOrThrow()
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "belongs-to with every FK null should still fire target interceptors; observed: $ops",
        )

        ops.clear()
        client.posts.query { withTags() }.allOrThrow()
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "many-to-many with an empty junction should still fire target interceptors; observed: $ops",
        )
    }

    @Test
    fun `no relationship data still means no target query`() {
        val counting = QueryCountingDriver(resetAndDriver())
        val client = EntClient(counting) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
        }
        client.reminders.create { body = "unassigned" }.saveOrThrow()
        counting.queriedTables.clear()

        val reminders = client.reminders.query { withAssignee() }.allOrThrow()

        // Firing the interceptor is not a reason to issue a query whose
        // IN list is empty.
        assertTrue(reminders.all { it.edges.assignee == null })
        assertFalse(
            counting.queriedTables.contains("users"),
            "an empty IN can't match anything; queried: ${counting.queriedTables}",
        )
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
