package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.query.GlobalQueryInterceptor
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Shape-preserving edge traversal: `queryX()` follows the source
 * query as written. Source `where` / `orderBy` / `limit` / `offset`
 * select which source rows are traversed (lowered as a source-id
 * subquery); the target block still owns the target shape; target
 * rows stay duplicate-free; and interceptor limit operations apply
 * to `EDGE_TRAVERSAL` instead of silently no-oping. Data terminals
 * return `ReadResult`: interceptor rejection is
 * `Failed(EntQueryRejectedException)`, LOAD denial is
 * `Failed(EntPrivacyDeniedException)`.
 *
 * Privacy split pinned here too: traversal does NOT apply source
 * LOAD privacy (source rows are not returned), while target rows
 * keep normal target read semantics.
 */
class EdgeTraversalSourceShapeIntegrationTest : PostgresTestBase() {

    private fun bypassClient(driver: PostgresDriver): EntClient = EntClient(driver)

    /** Three users, two articles each; returns users oldest-first. */
    private fun seedUsersWithArticles(client: EntClient): List<User> {
        val users = listOf("alice", "bob", "carol").map { name ->
            client.users.create { this.name = name; email = "$name@x" }.saveAndLoad(testViewerContext).getOrThrow()
        }
        for (user in users) {
            for (n in 1..2) {
                client.articles.create {
                    title = "${user.name}-$n"
                    authorId = user.id
                }.save(testViewerContext).getOrThrow()
            }
        }
        return users
    }

    // ---- Source shape constrains traversal ----

    @Test
    fun `source where orderBy limit constrain direct traversal`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        // Every clause is binding: the where drops carol BEFORE the
        // order/limit apply (bob is selected, not carol), the desc
        // order picks bob over alice, and the limit keeps exactly
        // one of the two remaining users. Dropping any clause — or
        // applying limit before where — changes the result.
        val titles = client.users.query {
            where(User.name neq "carol")
            orderBy(User.id.desc())
            limit(1)
        }.queryArticles().all(testViewerContext).getOrThrow().map { it.title }.sorted()

        assertEquals(listOf("bob-1", "bob-2"), titles)
    }

    @Test
    fun `source limit alone constrains direct traversal`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        val titles = client.users.query {
            orderBy(User.id.desc())
            limit(1)
        }.queryArticles().all(testViewerContext).getOrThrow().map { it.title }.sorted()

        assertEquals(listOf("carol-1", "carol-2"), titles)
    }

    @Test
    fun `source offset is honored`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        // Users in id-asc order are alice, bob, carol; offset 1
        // limit 1 selects bob.
        val titles = client.users.query {
            orderBy(User.id.asc())
            limit(1)
            offset(1)
        }.queryArticles().all(testViewerContext).getOrThrow().map { it.title }.sorted()

        assertEquals(listOf("bob-1", "bob-2"), titles)
    }

    @Test
    fun `source offset without a limit is honored`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        // Offset-only: skip the first user in id-asc order, keep the
        // rest. The ordering must survive into the subquery even with
        // no limit — OFFSET without ORDER BY would skip an arbitrary
        // row.
        val titles = client.users.query {
            orderBy(User.id.asc())
            offset(1)
        }.queryArticles().all(testViewerContext).getOrThrow().map { it.title }.sorted()

        assertEquals(listOf("bob-1", "bob-2", "carol-1", "carol-2"), titles)
    }

    @Test
    fun `source shape constrains child-to-parent traversal`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        // Newest article overall is carol-2; its author is carol.
        val authors = client.articles.query {
            orderBy(Article.id.desc())
            limit(1)
        }.queryAuthor().all(testViewerContext).getOrThrow()

        assertEquals(listOf("carol"), authors.map { it.name })
    }

    @Test
    fun `source shape constrains many-to-many traversal`() {
        val client = bypassClient(resetAndDriver())
        val postA = client.posts.create { title = "a" }.saveAndLoad(testViewerContext).getOrThrow()
        val postB = client.posts.create { title = "b" }.saveAndLoad(testViewerContext).getOrThrow()
        val tagA = client.tags.create { name = "only-a" }.saveAndLoad(testViewerContext).getOrThrow()
        val tagB = client.tags.create { name = "only-b" }.saveAndLoad(testViewerContext).getOrThrow()
        client.postTags.create { postId = postA.id; tagId = tagA.id }.save(testViewerContext).getOrThrow()
        client.postTags.create { postId = postB.id; tagId = tagB.id }.save(testViewerContext).getOrThrow()

        // Only the newest post (b) feeds the junction walk.
        val tags = client.posts.query {
            orderBy(Post.id.desc())
            limit(1)
        }.queryTags().all(testViewerContext).getOrThrow()

        assertEquals(listOf("only-b"), tags.map { it.name })
    }

    // ---- Target shape is still target shape ----

    @Test
    fun `target where orderBy and limit still apply to target rows`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        val titles = client.users.query {
            orderBy(User.id.asc())
            limit(2) // alice + bob
        }.queryArticles {
            where(Article.title neq "alice-1")
            orderBy(Article.title.desc())
            limit(2)
        }.all(testViewerContext).getOrThrow().map { it.title }

        assertEquals(listOf("bob-2", "bob-1"), titles)
    }

    @Test
    fun `target limit is total target rows not per source row`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        val articles = client.users.query {
            orderBy(User.id.asc())
            limit(2) // alice + bob → 4 articles reachable
        }.queryArticles {
            orderBy(Article.id.asc())
            limit(3)
        }.all(testViewerContext).getOrThrow()

        assertEquals(3, articles.size, "target limit(3) must cap total rows, not rows per source user")
    }

    @Test
    fun `many-to-many fan-out does not duplicate target rows`() {
        val client = bypassClient(resetAndDriver())
        val postA = client.posts.create { title = "a" }.saveAndLoad(testViewerContext).getOrThrow()
        val postB = client.posts.create { title = "b" }.saveAndLoad(testViewerContext).getOrThrow()
        val shared = client.tags.create { name = "shared" }.saveAndLoad(testViewerContext).getOrThrow()
        client.postTags.create { postId = postA.id; tagId = shared.id }.save(testViewerContext).getOrThrow()
        client.postTags.create { postId = postB.id; tagId = shared.id }.save(testViewerContext).getOrThrow()

        // Both posts are selected and both reach the same tag — the
        // tag must come back once.
        val tags = client.posts.query { orderBy(Post.id.asc()) }.queryTags().all(testViewerContext).getOrThrow()

        assertEquals(listOf("shared"), tags.map { it.name })
    }

    // ---- Interceptor semantics ----

    @Test
    fun `source interceptor predicates narrow the traversal source`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EDGE_TRAVERSAL) {
                            scope.addPredicate(User.name eq "alice")
                        }
                    },
                    name = "only-alice",
                )
            }
        }
        seedUsersWithArticles(client)

        val titles = client.users.query().queryArticles().all(testViewerContext).getOrThrow().map { it.title }.sorted()

        assertEquals(listOf("alice-1", "alice-2"), titles)
    }

    @Test
    fun `source interceptor default limit applies to traversal`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            interceptors {
                // Was a silent no-op on EDGE_TRAVERSAL before the
                // shaped lowering; now it bounds the source row set.
                global(
                    GlobalQueryInterceptor { scope, _ -> scope.setDefaultLimitIfAbsent(1) },
                    name = "default-limit",
                )
            }
        }
        seedUsersWithArticles(client)

        val titles = client.users.query {
            orderBy(User.id.asc())
        }.queryArticles {
            // The target step sees the same default limit; lift it
            // out of the way so only the source-side effect is
            // observed (1 source user → 2 articles).
            limit(50)
        }.all(testViewerContext).getOrThrow().map { it.title }.sorted()

        assertEquals(listOf("alice-1", "alice-2"), titles)
    }

    @Test
    fun `source interceptor clamp lowers a caller-set source limit`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            interceptors {
                // Gated on the traversal step so the clamp
                // observably narrows the SOURCE row set (an
                // ungated clamp would also cap the target read).
                global(
                    GlobalQueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EDGE_TRAVERSAL) scope.requireLimitAtMost(1)
                    },
                    name = "clamp-traversal-source",
                )
            }
        }
        seedUsersWithArticles(client)

        val titles = client.users.query {
            orderBy(User.id.asc())
            limit(3)
        }.queryArticles { limit(50) }.all(testViewerContext).getOrThrow().map { it.title }.sorted()

        assertEquals(listOf("alice-1", "alice-2"), titles, "requireLimitAtMost(1) must clamp the traversal source to one user")
    }

    @Test
    fun `all maps source rejection to Failed(EntQueryRejectedException)`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EDGE_TRAVERSAL) {
                            scope.reject("no traversal", code = "no_trav")
                        }
                    },
                    name = "traversal-rejector",
                )
            }
        }

        val result = client.users.query().queryArticles().all(testViewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val rejected = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("no traversal", rejected.reason)
        assertEquals("no_trav", rejected.code)
        assertEquals("traversal-rejector", rejected.interceptor)
    }

    // ---- Privacy semantics ----

    @Test
    fun `source LOAD privacy is not applied implicitly`() {
        val viewerContext = ViewerContext(Viewer.Anonymous)
        val client = EntClient(resetAndDriver()) {

            policies {
                users(DenyAllUserLoads)
                articles(AllowAllArticleLoads)
            }
        }
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            seedUsersWithArticles(sys)
        }

        // Sanity: the viewer cannot LOAD users directly...
        val direct = client.users.query { limit(1) }.all(viewerContext)
        val failed = assertIs<ReadResult.Failed>(direct)
        assertIs<EntPrivacyDeniedException>(failed.exception)

        // ...but traversal only uses users to define the article
        // query — no User entity is returned, so no User LOAD
        // privacy runs.
        val titles = client.users.query {
            orderBy(User.id.desc())
            limit(1)
        }.queryArticles().all(viewerContext).getOrThrow().map { it.title }.sorted()

        assertEquals(listOf("carol-1", "carol-2"), titles)
    }

    @Test
    fun `target LOAD privacy still applies to traversal results`() {
        val viewerContext = ViewerContext(Viewer.Anonymous)
        val client = EntClient(resetAndDriver()) {

            policies {
                users(AllowAllUserLoads)
                articles(PublishedOnlyArticleLoads)
            }
        }
        val draft = run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val author = sys.users.create { name = "A"; email = "a@x" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "pub"; published = true; authorId = author.id }.save(testViewerContext).getOrThrow()
            sys.articles.create { title = "draft"; published = false; authorId = author.id }.saveAndLoad(testViewerContext).getOrThrow()
        }

        // The strict terminal evaluates the full selected window and
        // fails on the denied draft, keying exactly that row — the
        // published article alone would have been visible.
        val result = client.users.query().queryArticles().all(viewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val denied = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(denied.origin)
        assertEquals(listOf(draft.id), denied.denials.map { it.entityKey.value })
    }

    // ---- Traversal chains ----

    @Test
    fun `traversal chains preserve shape at each hop`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        // Hop 1: the two newest users (carol + bob). Hop 2: their
        // oldest article — bob-1, since bob's articles precede
        // carol's in id order. Hop 3: back to the author — exactly
        // [bob]. Every hop's shape is observable: dropping hop 1's
        // bounds makes hop 2 pick alice-1 → [alice]; dropping hop
        // 2's bounds leaves both authors → [bob, carol].
        val authors = client.users.query {
            orderBy(User.id.desc())
            limit(2)
        }.queryArticles {
            orderBy(Article.id.asc())
            limit(1)
        }.queryAuthor().all(testViewerContext).getOrThrow()

        assertEquals(listOf("bob"), authors.map { it.name })
    }

}

// ---- Privacy fixtures ----

private val DenyAllUserLoads = object : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("no user loads") })
        }
    }
}

private val AllowAllUserLoads = object : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy { load(allowAll) }
    }
}

private val AllowAllArticleLoads = object : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(allowAll) }
    }
}

private val PublishedOnlyArticleLoads = object : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(ArticleLoadPrivacyRule { _, item ->
                if (item.entity.published) PrivacyDecision.Allow else PrivacyDecision.Continue
            })
        }
    }
}
