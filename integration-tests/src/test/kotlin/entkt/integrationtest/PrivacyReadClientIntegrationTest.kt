package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntClientConfig
import entkt.integrationtest.ent.ReadOnlyEntClient
import entkt.integrationtest.ent.Membership
import entkt.integrationtest.ent.MembershipLoadPrivacyRule
import entkt.integrationtest.ent.MembershipPolicyScope
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreatePrivacyRule
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import entkt.runtime.result.visibleOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ---- Privacy rules exercising the read-only privacy client ----

/** Users readable by authenticated viewers only (fail-closed otherwise). */
private val AllowUserLoadsForAuthenticated = UserLoadPrivacyRule { context, _ ->
    if (context.viewerContext.viewer is Viewer.User) PrivacyDecision.Allow else PrivacyDecision.Continue
}

private val AllowAllUserCreates = UserCreatePrivacyRule { _, _ -> PrivacyDecision.Allow }
private val AllowAllArticleLoads = ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }

/**
 * Graph-reading load rule on the throwing projection. The explicit
 * `ReadOnlyEntClient` type pins the context's client property — this
 * file stops compiling if privacy contexts regress to the full
 * `EntClient` or another capability surface. Under the caller's context a viewer who cannot read users
 * gets the inner read's EntPrivacyDeniedException (rethrown by
 * `getOrThrow`), which the outer root terminal then captures as its
 * own `ReadResult.Failed` — not the row.
 */
private val AllowIfAuthorReadable = ArticleLoadPrivacyRule { context, item ->
    val client: ReadOnlyEntClient = context.client
    if (client.users.findById(context.viewerContext, item.authorId).getOrThrow() != null) PrivacyDecision.Allow
    else PrivacyDecision.Continue
}

/** Same invariant on the filtering projection: root denial collapses to null. */
private val AllowIfAuthorVisiblyReadable = ArticleLoadPrivacyRule { context, item ->
    if (context.client.users.findById(context.viewerContext, item.authorId).visibleOrNull().getOrThrow() != null) {
        PrivacyDecision.Allow
    } else {
        PrivacyDecision.Continue
    }
}

/** Create rule that reads the graph — the transaction-scoping probe. */
private val AuthorRowMustExist = ArticleCreatePrivacyRule { context, item ->
    if (context.client.users.findById(context.viewerContext, item.authorId).getOrThrow() != null) PrivacyDecision.Allow
    else PrivacyDecision.Deny("author row not found")
}

/**
 * Load rule whose decision reads through edges: the `loadUser()` eager
 * load, the `queryUsers()` M2M traversal, and the staged index helper
 * all run through the privacy client, so the hop rows they materialize
 * pass the caller's LOAD privacy like any other rule read — a viewer
 * who cannot load users gets the denial, not the graph. (The rule
 * reads memberships and groups, never articles, so the inner reads
 * cannot re-enter this rule; the traversal's group source rows are
 * structural and never materialize.)
 */
private val MembersReachableViaEdgesUnlockArticles = ArticleLoadPrivacyRule { context, _ ->
    val memberships = context.client.memberships.query { loadUser() }.all(context.viewerContext).getOrThrow()
    val traversedMember = context.client.groups.query { }.queryUsers().firstOrNull(context.viewerContext).getOrThrow()
    val indexedMember = context.client.users.indexes.email("alice@test.com").find(context.viewerContext).getOrThrow()
    if (memberships.any { it.edges.user.requireLoaded() != null } && traversedMember != null && indexedMember != null) {
        PrivacyDecision.Allow
    } else {
        PrivacyDecision.Continue
    }
}

/**
 * A rule that deliberately uses a storage-level existence fact. The
 * explicit bypass context skips user LOAD privacy, which also avoids
 * recursively entering user LOAD rules.
 */
private val BypassUserExistsRule = ArticleLoadPrivacyRule { context, _ ->
    val user = context.client.users.query { }
        .firstOrNull(testBypassContext("privacy rule user existence"))
        .getOrThrow()
    if (user != null) PrivacyDecision.Allow
    else PrivacyDecision.Continue
}

// ---- Policies ----

private object AuthenticatedReadersUserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(AllowUserLoadsForAuthenticated)
            create(AllowAllUserCreates)
        }
    }
}

private object AuthorReadableArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(AllowIfAuthorReadable) }
    }
}

private object AuthorVisiblyReadableArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(AllowIfAuthorVisiblyReadable) }
    }
}

private object AuthorCheckedCreateArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllArticleLoads)
            create(AuthorRowMustExist)
        }
    }
}

private object MemberGateArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(MembersReachableViaEdgesUnlockArticles) }
    }
}

private object BypassUserExistsArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(BypassUserExistsRule) }
    }
}

// ---- Predicate-inference limitation pin ----

private val AllowAllMembershipLoads = MembershipLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }

/**
 * Documented-limitation pin (Privacy Limitations → Predicate-Based
 * Inference): `has { }` compiles to an EXISTS subquery and never
 * LOAD-checks the related rows, so this rule is influenced by the
 * hidden user's email even for viewers who cannot load that user. The
 * materialized rows (memberships) do pass LOAD privacy. Pinned so a
 * future change (e.g. edge-derived LOAD privacy) flips this
 * deliberately, not by accident.
 */
private val SecretMemberEmailUnlocksArticles = ArticleLoadPrivacyRule { context, _ ->
    val secretMembership = context.client.memberships.query {
        where(Membership.user.has { where(User.email.eq("alice@test.com")) })
    }.firstOrNull(context.viewerContext).getOrThrow()
    if (secretMembership != null) PrivacyDecision.Allow else PrivacyDecision.Continue
}

private object OpenMembershipPolicy : EntityPolicy<Membership, MembershipPolicyScope> {
    override fun configure(scope: MembershipPolicyScope) = scope.run {
        privacy { load(AllowAllMembershipLoads) }
    }
}

private object SecretMemberGateArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(SecretMemberEmailUnlocksArticles) }
    }
}

/**
 * End-to-end semantics of the stable `ReadOnlyEntClient`: rule reads are
 * explicitly viewer-scoped with `context.viewerContext` (asserted on
 * both denial projections — the rethrowing `getOrThrow` and the
 * null-collapsing `visibleOrNull`), transaction-scoped, and still pass
 * through read interceptors under the caller's viewer. Rules that need
 * storage-wide facts must explicitly supply a bypass context to an entity
 * terminal. The compile-time no-writes guarantees are pinned in `codegen`'s
 * `PrivacyReadClientCompileTest`.
 */
class PrivacyReadClientIntegrationTest : PostgresTestBase() {
    private val anonymousViewerContext = ViewerContext(Viewer.Anonymous)
    private val transactionViewerContext = ViewerContext(Viewer.User(42L))

    private fun freshClient(config: EntClientConfig.() -> Unit): EntClient =
        EntClient(resetAndDriver(), config)

    private fun seedAuthorAndArticle(client: EntClient): Pair<User, Article> =
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val author = sys.users.create { name = "Alice"; email = "alice@test.com" }.saveAndLoad(testViewerContext).getOrThrow()
            val article = sys.articles.create { title = "T"; authorId = author.id }.saveAndLoad(testViewerContext).getOrThrow()
            author to article
        }

    // ---- Viewer scoping, rethrowing projection ----

    @Test
    fun `rule reads are viewer-scoped - getOrThrow surfaces the inner denial`() {
        val client = freshClient {

            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(AuthorReadableArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)

        // Authenticated viewer: the rule's user read succeeds
        // viewer-scoped, so the article is visible.
        val asUser = run {
            val c = client
            val testViewerContext = ViewerContext(Viewer.User(author.id))
            c.articles.findById(testViewerContext, article.id).getOrThrow()
        }
        assertNotNull(asUser)

        // Anonymous: the rule's findById runs as Anonymous, its
        // getOrThrow rethrows the users LOAD denial, and the article's
        // root terminal captures it — the Failed names "User", not
        // "Article", proving the inner read was viewer-scoped. A
        // bypass-scoped read using `readViewerContext` would
        // have returned the row and allowed the article.
        val failed = assertIs<ReadResult.Failed>(client.articles.findById(anonymousViewerContext, article.id))
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals("User", ex.denials.single().entityType)
    }

    // ---- Viewer scoping, filtering projection ----

    @Test
    fun `rule reads are viewer-scoped - visibleOrNull collapses the denial to null`() {
        val client = freshClient {

            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(AuthorVisiblyReadableArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)

        val asUser = run {
            val c = client
            val testViewerContext = ViewerContext(Viewer.User(author.id))
            c.articles.findById(testViewerContext, article.id).getOrThrow()
        }
        assertNotNull(asUser)

        // Anonymous: visibleOrNull collapses the users denial to
        // Success(null), the rule falls through to Continue, and the
        // fail-closed list denies the ARTICLE — the denial names
        // "Article", the distinct outcome of the filtering projection.
        val failed = assertIs<ReadResult.Failed>(client.articles.findById(anonymousViewerContext, article.id))
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals("Article", ex.denials.single().entityType)
    }

    // ---- Viewer scoping, edge reads ----

    @Test
    fun `rule eager loads, traversals, and index helpers are viewer-scoped`() {
        val client = freshClient {

            policies {
                users(AuthenticatedReadersUserPolicy)
                memberships(OpenMembershipPolicy)
                articles(MemberGateArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val group = sys.groups.create { name = "G" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.memberships.create { userId = author.id; groupId = group.id; role = "member" }.save(testViewerContext).getOrThrow()
        }

        // Authenticated viewer: the eager-loaded membership carries its
        // user, the traversal reaches the member, and the index helper
        // finds the email — the rule allows the article.
        val asUser = run {
            val c = client
            val testViewerContext = ViewerContext(Viewer.User(author.id))
            c.articles.findById(testViewerContext, article.id).getOrThrow()
        }
        assertNotNull(asUser)

        // Anonymous: the same hops run viewer-scoped through the privacy
        // client, so the users LOAD denial surfaces from inside the
        // rule's eager load — a SelectedEdgePath-origin EntPrivacyDeniedException
        // naming "User" — instead of the hidden rows influencing the
        // decision. The rule reads through `all().getOrThrow()`, so the
        // rethrown denial is captured by the article's root terminal as
        // its own Failed.
        val failed = assertIs<ReadResult.Failed>(client.articles.findById(anonymousViewerContext, article.id))
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals("User", ex.denials.single().entityType)
        assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
    }

    // ---- Transaction scoping ----

    @Test
    fun `rule reads inside a transaction see uncommitted writes`() {
        val client = freshClient {

            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(AuthorCheckedCreateArticlePolicy)
            }
        }

        // The author row is uncommitted when the article's create-privacy
        // rule reads it. Only a transaction-scoped rule read can see it —
        // an outer-connection read would return null and the rule would
        // Deny("author row not found").
        val article = client.withTransaction { tx ->
            val author = tx.users.create { name = "Bob"; email = "bob@test.com" }.saveAndLoad(transactionViewerContext).orRollback()
            tx.articles.create { title = "In tx"; authorId = author.id }.saveAndLoad(transactionViewerContext).orRollback()
        }.getOrThrow()
        assertNotNull(article)
    }

    // ---- Explicit bypass reads in privacy rules ----

    @Test
    fun `bypass entity reads can provide storage facts without evaluating LOAD privacy`() {
        val client = freshClient {

            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(BypassUserExistsArticlePolicy)
            }
        }
        val (_, article) = seedAuthorAndArticle(client)

        // Anonymous cannot materialize the user row, but the article
        // rule may deliberately use its storage-level existence as an
        // authorization input by supplying a bypass context.
        val loaded = client.articles.findById(anonymousViewerContext, article.id).getOrThrow()
        assertNotNull(loaded)
    }

    @Test
    fun `privacy rules can aggregate bypass-loaded entities in Kotlin`() {
        val captured = mutableListOf<ReadResult<String?>>()
        val storageAggregateRule = ArticleLoadPrivacyRule { context, _ ->
            val minimumEmail = context.client.users.query { }
                .all(testBypassContext("privacy rule minimum user email"))
                .getOrThrow()
                .minOfOrNull(User::email)
            captured.add(ReadResult.Success(minimumEmail))
            PrivacyDecision.Allow
        }
        val storageAggregatePolicy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy { load(storageAggregateRule) }
            }
        }
        val client = freshClient {

            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(storageAggregatePolicy)
            }
        }
        val (_, article) = seedAuthorAndArticle(client)

        val loaded = client.articles.findById(anonymousViewerContext, article.id).getOrThrow()
        assertNotNull(loaded)
        assertEquals(ReadResult.Success("alice@test.com"), captured.single())
    }

    // ---- Predicate-based inference (documented limitation) ----

    @Test
    fun `has predicates inside rules are EXISTS-scoped, not LOAD-checked - documented limitation`() {
        val client = freshClient {

            policies {
                users(AuthenticatedReadersUserPolicy)
                memberships(OpenMembershipPolicy)
                articles(SecretMemberGateArticlePolicy)
            }
        }
        val (alice, article) = run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val alice = sys.users.create { name = "Alice"; email = "alice@test.com" }.saveAndLoad(testViewerContext).getOrThrow()
            val group = sys.groups.create { name = "G" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.memberships.create { groupId = group.id; userId = alice.id; role = "member" }.save(testViewerContext).getOrThrow()
            val article = sys.articles.create { title = "T"; authorId = alice.id }.saveAndLoad(testViewerContext).getOrThrow()
            alice to article
        }

        // The caller cannot load the user directly…
        val userRead = assertIs<ReadResult.Failed>(client.users.findById(anonymousViewerContext, alice.id))
        assertIs<EntPrivacyDeniedException>(userRead.exception)

        // …yet the rule's has{} predicate matches her email inside the
        // EXISTS subquery, the (LOAD-checked, allowed) membership row
        // comes back, and the article unlocks: the hidden row influenced
        // authorization. This pins the documented predicate-inference
        // limitation — see docs/08-privacy-limitations.md. If this test
        // starts failing because the related row is now LOAD-checked,
        // that is edge-derived-LOAD-privacy-shaped work landing; update
        // the docs with it.
        assertNotNull(client.articles.findById(anonymousViewerContext, article.id).getOrThrow())
    }

    // ---- Interceptor semantics ----

    @Test
    fun `read interceptors run for rule queries and observe the caller viewer`() {
        val seenViewers = mutableListOf<Viewer>()
        val recorder = QueryInterceptor<User> { _, context -> seenViewers.add(context.viewerContext.viewer) }

        val client = freshClient {

            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(AuthorReadableArticlePolicy)
            }
            interceptors { users(recorder, "privacy-read-recorder") }
        }
        val (author, article) = seedAuthorAndArticle(client)

        run {
            val c = client
            val testViewerContext = ViewerContext(Viewer.User(author.id))
            c.articles.findById(testViewerContext, article.id).getOrThrow()
        }

        // The rule's user read passed through the interceptor chain with
        // the CALLER's viewer — not a bypass. Validation rules normally pass
        // their explicit `readViewerContext`; privacy rule reads normally pass
        // `viewerContext`.
        assertTrue(
            seenViewers.any { it is Viewer.User && it.id == author.id },
            "Expected the rule's user read to run through the users interceptor " +
                "with the caller's Viewer.User; saw: $seenViewers",
        )
        assertTrue(
            seenViewers.none { it is Viewer.PrivacyBypass },
            "Privacy rule reads must not run bypass-scoped; saw: $seenViewers",
        )
    }

    @Test
    fun `one client concurrently isolates viewer contexts while reusing its rule client`() {
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val seenClients = ConcurrentLinkedQueue<ReadOnlyEntClient>()
        val seenContexts = ConcurrentLinkedQueue<ViewerContext>()
        val concurrentRule = ArticleLoadPrivacyRule { context, _ ->
            seenClients.add(context.client)
            seenContexts.add(context.viewerContext)
            ready.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "concurrent privacy reads did not overlap" }
            PrivacyDecision.Allow
        }
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy { load(concurrentRule) }
            }
        }
        val client = freshClient { policies { articles(policy) } }
        val (_, article) = seedAuthorAndArticle(client)
        val firstContext = ViewerContext(Viewer.User("first"))
        val secondContext = ViewerContext(Viewer.User("second"))
        val errors = ConcurrentLinkedQueue<Throwable>()

        val threads = listOf(firstContext, secondContext).map { viewerContext ->
            thread {
                runCatching {
                    client.articles.findById(viewerContext, article.id).getOrThrow()
                }.exceptionOrNull()?.let(errors::add)
            }
        }
        check(ready.await(10, TimeUnit.SECONDS)) { "concurrent privacy reads did not reach the rule" }
        release.countDown()
        threads.forEach { it.join(10_000) }

        assertTrue(errors.isEmpty(), "concurrent read failed: ${errors.firstOrNull()}")
        assertTrue(threads.none { it.isAlive }, "concurrent read did not finish")
        val clients = seenClients.toList()
        assertEquals(2, clients.size)
        assertSame(clients[0], clients[1], "privacy rules must reuse one stable read-only client")
        val contexts = seenContexts.toList()
        assertEquals(2, contexts.size)
        assertTrue(contexts.any { it === firstContext })
        assertTrue(contexts.any { it === secondContext })
        assertFalse(contexts.any { it !== firstContext && it !== secondContext })
    }
}
