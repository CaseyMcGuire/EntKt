package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the boundary-input validation added to query bounds. Prior to
 * validation, `limit(-1)` / `offset(-1)` propagated to the driver and
 * surfaced as a SQL syntax error on Postgres — one layer removed from
 * the caller. Now each is `require`-rejected at the builder boundary
 * so the caller sees the bad input immediately (builder argument
 * validation throws; it never becomes a `ReadResult.Failed`).
 *
 * Also pins that `limit(0)` is honored consistently by every terminal
 * that reads rows, and by eager-load windows of every cardinality.
 */
class QueryBoundsValidationIntegrationTest : PostgresTestBase() {

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {

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

    // ---- limit(0) is honored by every terminal family ----
    //
    // The first-row terminals used to send a hardwired `limit = 1` and
    // discard the caller's bound, so `query { limit(0) }.firstOrNull(testViewerContext)`
    // returned a row while `query { limit(0) }.rawExists(testViewerContext)` — same
    // query, same rows — answered false. Interceptor limit mutators are
    // silent no-ops at FIRST (see limitOpsApply), so the
    // discarded value was always the caller's own.

    private fun seededClient(): EntClient = freshClient().also { client ->
        run {
            val sys = client
            val testViewerContext = testBypassContext("test")
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "First"; published = true; authorId = author.id }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "Second"; published = true; authorId = author.id }.saveAndLoad(testViewerContext).getOrThrow()
        }
    }

    @Test
    fun `limit(0) returns no row from firstOrNull`() {
        val client = seededClient()

        // "No rows within the caller's bound" is authoritative
        // absence — Success(null), same as "no rows matched".
        assertNull(client.articles.query { limit(0) }.firstOrNull(testViewerContext).getOrThrow())
        // Sanity: the rows are really there without the bound.
        assertNotNull(client.articles.query().firstOrNull(testViewerContext).getOrThrow())
    }

    @Test
    fun `limit(0) agrees across every terminal that reads rows`() {
        val client = seededClient()

        // The consistency the fix is really about: one bound, one
        // answer, from every terminal that actually reads rows.
        assertNull(client.articles.query { limit(0) }.firstOrNull(testViewerContext).getOrThrow())
        assertEquals(false, client.articles.query { limit(0) }.rawExists(testViewerContext).getOrThrow())
        assertEquals(emptyList(), client.articles.query { limit(0) }.all(testViewerContext).getOrThrow())
    }

    // ---- eager-load bounds apply per parent, whatever the cardinality ----

    @Test
    fun `limit(0) on a to-one eager load yields no target`() {
        val client = seededClient()

        // `loadAuthor { limit(0) }` used to load the author anyway: the
        // to-one eager path passed null limit/offset on the reasoning
        // that "limit is meaningless for a to-one". Positive limits are
        // indeed already satisfied by one target per parent — but zero
        // is a bound, not a no-op.
        val articles = client.articles.query { loadAuthor { limit(0) } }.all(testViewerContext).getOrThrow()

        assertTrue(articles.isNotEmpty(), "the root rows still load")
        assertTrue(
            articles.all { it.edges.author.requireLoaded() == null },
            "limit(0) should yield a loaded edge with no target",
        )
    }

    @Test
    fun `offset on a to-one eager load steps past the only candidate`() {
        val client = seededClient()

        val articles = client.articles.query { loadAuthor { offset(1) } }.all(testViewerContext).getOrThrow()

        assertTrue(
            articles.all { it.edges.author.requireLoaded() == null },
            "offset(1) skips the single target the parent has",
        )
    }

    @Test
    fun `an unbounded to-one eager load still loads the target`() {
        val client = seededClient()

        // Guard against over-correcting: with no bounds set, the window
        // is wide open and behavior is unchanged.
        val articles = client.articles.query { loadAuthor() }.all(testViewerContext).getOrThrow()

        assertTrue(articles.isNotEmpty())
        assertTrue(
            articles.all { it.edges.author.requireLoaded() != null },
            "an unbounded eager load must be unaffected",
        )
        // A positive limit is already satisfied by the single target.
        assertTrue(
            client.articles.query { loadAuthor { limit(1) } }.all(testViewerContext).getOrThrow()
                .all { it.edges.author.requireLoaded() != null },
        )
    }

    @Test
    fun `to-one and to-many eager loads agree on limit(0)`() {
        val client = seededClient()

        // The inconsistency this fixes: to-many already sliced per
        // parent, so the two cardinalities answered `limit(0)`
        // differently for no reason the caller could see.
        val users = client.users.query { loadArticles { limit(0) } }.all(testViewerContext).getOrThrow()
        assertTrue(users.isNotEmpty())
        // `requireLoaded()` throws if the edge were Unloaded, so this
        // pins "eagerly loaded and empty", not "never loaded".
        assertTrue(users.all { it.edges.articles.requireLoaded().isEmpty() }, "to-many honors limit(0)")

        val articles = client.articles.query { loadAuthor { limit(0) } }.all(testViewerContext).getOrThrow()
        assertTrue(articles.all { it.edges.author.requireLoaded() == null }, "to-one now honors it too")
    }

    // ---- an empty eager window must not touch the target at all ----

    private object DeniedUsers : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("user is hidden") }) }
        }
    }

    /** Articles readable, their authors denied to the current viewer. */
    private fun clientWithDeniedAuthors(): EntClient {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                articles(AllowAllArticles)
                users(DeniedUsers)
            }
        }
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "First"; published = true; authorId = author.id }.saveAndLoad(testViewerContext).getOrThrow()
        }
        return client
    }

    @Test
    fun `limit(0) on a to-one eager load does not evaluate the excluded target`() {
        val viewerContext = ViewerContext(Viewer.User("reader"))
        val client = clientWithDeniedAuthors()

        // Eager-target denial is strict — it fails the read rather than
        // filtering. But a target excluded by the caller's own window
        // was never requested, so denying it reports a decision about a
        // row nobody asked to load. Gating only the final assignment
        // left the fetch, the privacy check, and nested eager loading
        // all still running.
        val articles = client.articles.query { loadAuthor { limit(0) } }.all(viewerContext).getOrThrow()

        assertTrue(articles.isNotEmpty())
        assertTrue(articles.all { it.edges.author.requireLoaded() == null })
    }

    @Test
    fun `offset past a to-one eager target does not evaluate it`() {
        val viewerContext = ViewerContext(Viewer.User("reader"))
        val client = clientWithDeniedAuthors()

        val articles = client.articles.query { loadAuthor { offset(1) } }.all(viewerContext).getOrThrow()

        assertTrue(articles.all { it.edges.author.requireLoaded() == null })
    }

    @Test
    fun `an in-window denied eager target still fails the read`() {
        val viewerContext = ViewerContext(Viewer.User("reader"))
        val client = clientWithDeniedAuthors()

        // The other half of the contract: skipping privacy for an empty
        // window must not weaken the strict denial when the caller does
        // ask for the target. The denial is stored in the result;
        // getOrThrow surfaces it.
        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query { loadAuthor() }.all(viewerContext).getOrThrow()
        }
        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query { loadAuthor { limit(1) } }.all(viewerContext).getOrThrow()
        }
    }

    @Test
    fun `an empty eager window still fires the target's EAGER_LOAD interceptors`() {
        val driver = resetAndDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
            interceptors {
                users(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "user-observer")
            }
        }
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "First"; published = true; authorId = author.id }.saveAndLoad(testViewerContext).getOrThrow()
        }
        ops.clear()

        client.articles.query { loadAuthor { limit(0) } }.all(testViewerContext).getOrThrow()

        // The bound decides which rows survive, not whether the eager
        // subquery exists. Interceptors fire on every eager subquery, and
        // the to-many paths fire
        // before slicing — so skipping them here would make an
        // interceptor's view of the query depend on the caller's limit.
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "loadAuthor { limit(0) } should still fire User interceptors with EAGER_LOAD; observed: $ops",
        )
    }

    /** Records which tables were queried, delegating everything else. */
    private class QueryCountingDriver(private val real: DatabaseDriver) : DatabaseDriver by real {
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

            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
        }
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "First"; published = true; authorId = author.id }.saveAndLoad(testViewerContext).getOrThrow()
        }
        counting.queriedTables.clear()
        return client to counting
    }

    @Test
    fun `an empty to-one eager window issues no query for the target`() {
        val (client, counting) = countingClient()

        client.articles.query { loadAuthor { limit(0) } }.all(testViewerContext).getOrThrow()

        assertTrue(counting.queriedTables.contains("articles"), "the root query still runs")
        assertFalse(
            counting.queriedTables.contains("users"),
            "no row could survive the window, so the target fetch is pure waste; queried: ${counting.queriedTables}",
        )
    }

    @Test
    fun `an empty to-many eager window issues no query for the target`() {
        val (client, counting) = countingClient()

        client.users.query { loadArticles { limit(0) } }.all(testViewerContext).getOrThrow()

        assertTrue(counting.queriedTables.contains("users"), "the root query still runs")
        assertFalse(
            counting.queriedTables.contains("articles"),
            "same waste on the to-many path; queried: ${counting.queriedTables}",
        )
    }

    @Test
    fun `an empty many-to-many eager window issues no query for the target`() {
        val counting = QueryCountingDriver(resetAndDriver())
        val client = EntClient(counting)
        // A real link matters: with no junction rows the eager block
        // short-circuits before the target fetch, so the test would pass
        // without exercising the window at all.
        val post = client.posts.create { title = "p" }.saveAndLoad(testViewerContext).getOrThrow()
        val tag = client.tags.create { name = "t" }.saveAndLoad(testViewerContext).getOrThrow()
        client.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tag.id) }.save(testViewerContext).orRollback()
        }.getOrThrow()
        counting.queriedTables.clear()

        val posts = client.posts.query { loadTags { limit(0) } }.all(testViewerContext).getOrThrow()

        assertTrue(posts.all { it.edges.tags.requireLoaded().isEmpty() })
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
        val client = EntClient(counting)
        // A real link matters: with no junction rows the eager block
        // short-circuits before the target fetch, so the test would pass
        // without exercising the window at all.
        val post = client.posts.create { title = "p" }.saveAndLoad(testViewerContext).getOrThrow()
        val tag = client.tags.create { name = "t" }.saveAndLoad(testViewerContext).getOrThrow()
        client.withTransaction { tx ->
            tx.posts.update(post.id) { tags.add(tag.id) }.save(testViewerContext).orRollback()
        }.getOrThrow()
        counting.queriedTables.clear()

        client.posts.query { loadTags { limit(5) } }.all(testViewerContext).getOrThrow()

        assertTrue(counting.queriedTables.contains("tags"), "queried: ${counting.queriedTables}")
    }

    @Test
    fun `a non-empty eager window still issues the target query`() {
        val (client, counting) = countingClient()

        // Guard against over-correcting the skip into always-skip.
        val articles = client.articles.query { loadAuthor { limit(1) } }.all(testViewerContext).getOrThrow()

        assertTrue(articles.all { it.edges.author.requireLoaded() != null })
        assertTrue(counting.queriedTables.contains("users"), "queried: ${counting.queriedTables}")
    }

    @Test
    fun `eager interceptors fire even when no relationship data exists`() {
        val driver = resetAndDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

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
        client.reminders.create { body = "unassigned" }.saveAndLoad(testViewerContext).getOrThrow()
        client.posts.create { title = "untagged" }.saveAndLoad(testViewerContext).getOrThrow()
        ops.clear()

        client.reminders.query { loadAssignee() }.all(testViewerContext).getOrThrow()
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "belongs-to with every FK null should still fire target interceptors; observed: $ops",
        )

        ops.clear()
        client.posts.query { loadTags() }.all(testViewerContext).getOrThrow()
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "many-to-many with an empty junction should still fire target interceptors; observed: $ops",
        )
    }

    @Test
    fun `eager interceptors fire when the root query matches nothing`() {
        val driver = resetAndDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
            interceptors {
                users(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "user-observer")
            }
        }

        val articles = client.articles.query {
            where(Article.title eq "no-such-title")
            loadAuthor()
        }.all(testViewerContext).getOrThrow()

        // The root result decides what gets loaded, not whether the
        // eager subquery exists. An empty root must fire the same
        // EAGER_LOAD pass the relationship-empty and limit(0) cases fire.
        assertTrue(articles.isEmpty())
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "an empty root should still fire the target's EAGER_LOAD interceptors; observed: $ops",
        )
    }

    @Test
    fun `a rejecting eager interceptor rejects even when the root matches nothing`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) scope.reject("no eager users", code = "no_eager")
                    },
                    name = "user-rej",
                )
            }
        }
        val query = { client.articles.query { where(Article.title eq "no-such-title"); loadAuthor() } }

        val result = query().all(testViewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("no_eager", ex.code)
    }

    @Test
    fun `firstOrNull with no match still fires eager interceptors`() {
        val driver = resetAndDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
            interceptors {
                users(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "user-observer")
            }
        }

        assertNull(
            client.articles.query { where(Article.title eq "no-such-title"); loadAuthor() }
                .firstOrNull(testViewerContext).getOrThrow(),
        )
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "firstOrNull with no match should still fire the eager pass; observed: $ops",
        )
    }

    @Test
    fun `an empty root fires many-to-many eager interceptors without querying the junction`() {
        val counting = QueryCountingDriver(resetAndDriver())
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(counting) {

            interceptors {
                tags(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "tag-observer")
            }
        }

        val posts = client.posts.query { where(Post.title eq "no-such"); loadTags() }.all(testViewerContext).getOrThrow()

        assertTrue(posts.isEmpty())
        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "an empty root should still fire M2M EAGER_LOAD interceptors; observed: $ops",
        )
        // With no parents there is nothing the junction IN could
        // match — unlike the limit(0) case, where parents exist and
        // the junction rows feed the interceptor pass's target ids.
        assertFalse(
            counting.queriedTables.contains("post_tags"),
            "no parents — the junction fetch is pure waste; queried: ${counting.queriedTables}",
        )
        assertFalse(counting.queriedTables.contains("tags"))
    }

    @Test
    fun `nested eager interceptors fire when the outer eager load matches nothing`() {
        val driver = resetAndDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
            interceptors {
                users(QueryInterceptor { _, ctx -> ops.add(ctx.operation) }, name = "user-observer")
            }
        }
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        }
        ops.clear()

        // The user has no articles, so the nested loadAuthor pass has
        // zero parent groups. The only User-targeted EAGER_LOAD in
        // this query is that nested pass — the root runs with ALL —
        // so the assertion isolates nested firing.
        client.users.query { loadArticles { loadAuthor() } }.all(testViewerContext).getOrThrow()

        assertTrue(
            ops.contains(ReadOperation.EAGER_LOAD),
            "nested EAGER_LOAD (author) should fire even with zero articles; observed: $ops",
        )
    }

    @Test
    fun `no relationship data still means no target query`() {
        val counting = QueryCountingDriver(resetAndDriver())
        val client = EntClient(counting) {

            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
        }
        client.reminders.create { body = "unassigned" }.saveAndLoad(testViewerContext).getOrThrow()
        counting.queriedTables.clear()

        val reminders = client.reminders.query { loadAssignee() }.all(testViewerContext).getOrThrow()

        // Firing the interceptor is not a reason to issue a query whose
        // IN list is empty.
        assertTrue(reminders.all { it.edges.assignee.requireLoaded() == null })
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
        assertEquals(2L, client.articles.query { limit(0) }.rawCount(testViewerContext).getOrThrow())
    }

    @Test
    fun `a positive limit still returns a row from the first-row terminals`() {
        val client = seededClient()

        // The clamp is min(limit, 1) — anything above zero is already
        // satisfied by the single-row fetch.
        assertNotNull(client.articles.query { limit(1) }.firstOrNull(testViewerContext).getOrThrow())
        assertNotNull(client.articles.query { limit(5) }.firstOrNull(testViewerContext).getOrThrow())
    }
}
