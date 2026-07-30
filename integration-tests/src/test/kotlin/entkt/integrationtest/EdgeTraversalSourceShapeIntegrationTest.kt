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
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.query.GlobalQueryInterceptor
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.EntError
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Shape-preserving edge traversal: `queryX()` follows the source
 * query as written. Source `where` / `orderBy` / `limit` / `offset`
 * select which source rows are traversed (lowered as a source-id
 * subquery); the target block still owns the target shape; target
 * rows stay duplicate-free; and interceptor limit operations apply
 * to `EDGE_TRAVERSAL` instead of silently no-oping.
 *
 * Privacy split pinned here too: traversal does NOT apply source
 * LOAD privacy (source rows are not returned), while target rows
 * keep normal target read semantics.
 */
class EdgeTraversalSourceShapeIntegrationTest : PostgresTestBase() {

    private fun bypassClient(driver: PostgresDriver): EntClient = EntClient(driver) {
        privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
    }

    /** Three users, two articles each; returns users oldest-first. */
    private fun seedUsersWithArticles(client: EntClient): List<User> {
        val users = listOf("alice", "bob", "carol").map { name ->
            client.users.create { this.name = name; email = "$name@x" }.saveOrThrow()
        }
        for (user in users) {
            for (n in 1..2) {
                client.articles.create {
                    title = "${user.name}-$n"
                    authorId = user.id
                }.saveOrThrow()
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
        }.queryArticles().allOrThrow().map { it.title }.sorted()

        assertEquals(listOf("bob-1", "bob-2"), titles)
    }

    @Test
    fun `source limit alone constrains direct traversal`() {
        val client = bypassClient(resetAndDriver())
        seedUsersWithArticles(client)

        val titles = client.users.query {
            orderBy(User.id.desc())
            limit(1)
        }.queryArticles().allOrThrow().map { it.title }.sorted()

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
        }.queryArticles().allOrThrow().map { it.title }.sorted()

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
        }.queryArticles().allOrThrow().map { it.title }.sorted()

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
        }.queryAuthor().allOrThrow()

        assertEquals(listOf("carol"), authors.map { it.name })
    }

    @Test
    fun `source shape constrains many-to-many traversal`() {
        val client = bypassClient(resetAndDriver())
        val postA = client.posts.create { title = "a" }.saveOrThrow()
        val postB = client.posts.create { title = "b" }.saveOrThrow()
        val tagA = client.tags.create { name = "only-a" }.saveOrThrow()
        val tagB = client.tags.create { name = "only-b" }.saveOrThrow()
        client.postTags.create { postId = postA.id; tagId = tagA.id }.saveOrThrow()
        client.postTags.create { postId = postB.id; tagId = tagB.id }.saveOrThrow()

        // Only the newest post (b) feeds the junction walk.
        val tags = client.posts.query {
            orderBy(Post.id.desc())
            limit(1)
        }.queryTags().allOrThrow()

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
        }.allOrThrow().map { it.title }

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
        }.allOrThrow()

        assertEquals(3, articles.size, "target limit(3) must cap total rows, not rows per source user")
    }

    @Test
    fun `many-to-many fan-out does not duplicate target rows`() {
        val client = bypassClient(resetAndDriver())
        val postA = client.posts.create { title = "a" }.saveOrThrow()
        val postB = client.posts.create { title = "b" }.saveOrThrow()
        val shared = client.tags.create { name = "shared" }.saveOrThrow()
        client.postTags.create { postId = postA.id; tagId = shared.id }.saveOrThrow()
        client.postTags.create { postId = postB.id; tagId = shared.id }.saveOrThrow()

        // Both posts are selected and both reach the same tag — the
        // tag must come back once.
        val tags = client.posts.query { orderBy(Post.id.asc()) }.queryTags().allOrThrow()

        assertEquals(listOf("shared"), tags.map { it.name })
    }

    // ---- Interceptor semantics ----

    @Test
    fun `source interceptor predicates narrow the traversal source`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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

        val titles = client.users.query().queryArticles().allOrThrow().map { it.title }.sorted()

        assertEquals(listOf("alice-1", "alice-2"), titles)
    }

    @Test
    fun `source interceptor default limit applies to traversal`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        }.allOrThrow().map { it.title }.sorted()

        assertEquals(listOf("alice-1", "alice-2"), titles)
    }

    @Test
    fun `source interceptor clamp lowers a caller-set source limit`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        }.queryArticles { limit(50) }.allOrThrow().map { it.title }.sorted()

        assertEquals(listOf("alice-1", "alice-2"), titles, "requireLimitAtMost(1) must clamp the traversal source to one user")
    }

    @Test
    fun `allOrError maps source rejection to Err QueryRejected`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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

        val result = client.users.query().queryArticles().allOrError()

        val err = assertIs<EntResult.Err>(result)
        val rejected = assertIs<EntError.QueryRejected>(err.error)
        assertEquals("no_trav", rejected.code)
        assertEquals("traversal-rejector", rejected.interceptor)
    }

    // ---- Privacy semantics ----

    @Test
    fun `source LOAD privacy is not applied implicitly`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(DenyAllUserLoads)
                articles(AllowAllArticleLoads)
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            seedUsersWithArticles(sys)
        }

        // Sanity: the viewer cannot LOAD users directly...
        assertFailsWith<EntPrivacyDeniedException> {
            client.users.query { limit(1) }.allOrThrow()
        }

        // ...but traversal only uses users to define the article
        // query — no User entity is returned, so no User LOAD
        // privacy runs.
        val titles = client.users.query {
            orderBy(User.id.desc())
            limit(1)
        }.queryArticles().allOrThrow().map { it.title }.sorted()

        assertEquals(listOf("carol-1", "carol-2"), titles)
    }

    @Test
    fun `target LOAD privacy still applies to traversal results`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AllowAllUserLoads)
                articles(PublishedOnlyArticleLoads)
            }
        }
        val published = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@x" }.saveOrThrow()
            val pub = sys.articles.create { title = "pub"; published = true; authorId = author.id }.saveOrThrow()
            sys.articles.create { title = "draft"; published = false; authorId = author.id }.saveOrThrow()
            pub
        }

        // Strict terminal throws on the denied draft…
        assertFailsWith<EntPrivacyDeniedException> {
            client.users.query().queryArticles().allOrThrow()
        }
        // …visible terminal filters it.
        val visible = client.users.query().queryArticles().visibleAll()
        assertEquals(listOf(published.id), visible.map { it.id })
    }

    // ---- Chains and explain ----

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
        }.queryAuthor().allOrThrow()

        assertEquals(listOf("bob"), authors.map { it.name })
    }

    @Test
    fun `explain shows the shaped traversal source`() {
        val client = bypassClient(resetAndDriver())

        val plan = client.users.query {
            where(User.name eq "alice")
            orderBy(User.id.desc())
            limit(10)
            offset(5)
        }.queryArticles().explainAllOrThrow()

        val sql = assertNotNull(plan.root).describe()
        assertTrue("IN (SELECT" in sql, "traversal should lower to a source-id subquery; was: $sql")
        assertTrue("ORDER BY" in sql, "source orderBy should appear in the subquery; was: $sql")
        assertTrue("LIMIT 10" in sql, "source limit should appear in the subquery; was: $sql")
        assertTrue("OFFSET 5" in sql, "source offset should appear in the subquery; was: $sql")
    }
}

// ---- Privacy fixtures ----

private val DenyAllUserLoads = object : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(UserLoadPrivacyRule { PrivacyDecision.Deny("no user loads") })
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
            load(ArticleLoadPrivacyRule { ctx ->
                if (ctx.entity.published) PrivacyDecision.Allow else PrivacyDecision.Continue
            })
        }
    }
}
