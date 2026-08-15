package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntClientConfig
import entkt.integrationtest.ent.EntPrivacyReadClient
import entkt.integrationtest.ent.Membership
import entkt.integrationtest.ent.MembershipLoadPrivacyRule
import entkt.integrationtest.ent.MembershipPolicyScope
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreatePrivacyRule
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import entkt.runtime.result.visibleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---- Privacy rules exercising the read-only privacy client ----

/** Users readable by authenticated viewers only (fail-closed otherwise). */
private val AllowUserLoadsForAuthenticated = UserLoadPrivacyRule { ctx ->
    if (ctx.privacy.viewer is Viewer.User) PrivacyDecision.Allow else PrivacyDecision.Continue
}

private val AllowAllUserCreates = UserCreatePrivacyRule { PrivacyDecision.Allow }
private val AllowAllArticleLoads = ArticleLoadPrivacyRule { PrivacyDecision.Allow }

/**
 * Graph-reading load rule on the throwing projection. The explicit
 * `EntPrivacyReadClient` type pins the context's client property — this
 * file stops compiling if privacy contexts regress to the full
 * `EntClient`, the shared `EntReadClient` interface, or the validation
 * posture. Under the caller's context a viewer who cannot read users
 * gets the inner read's EntPrivacyDeniedException (rethrown by
 * `getOrThrow`), which the outer root terminal then captures as its
 * own `ReadResult.Failed` — not the row.
 */
private val AllowIfAuthorReadable = ArticleLoadPrivacyRule { ctx ->
    val client: EntPrivacyReadClient = ctx.client
    if (client.users.findById(ctx.entity.authorId).getOrThrow() != null) PrivacyDecision.Allow
    else PrivacyDecision.Continue
}

/** Same invariant on the filtering projection: root denial collapses to null. */
private val AllowIfAuthorVisiblyReadable = ArticleLoadPrivacyRule { ctx ->
    if (ctx.client.users.findById(ctx.entity.authorId).visibleOrNull().getOrThrow() != null) {
        PrivacyDecision.Allow
    } else {
        PrivacyDecision.Continue
    }
}

/** Create rule that reads the graph — the transaction-scoping probe. */
private val AuthorRowMustExist = ArticleCreatePrivacyRule { ctx ->
    if (ctx.client.users.findById(ctx.candidate.authorId).getOrThrow() != null) PrivacyDecision.Allow
    else PrivacyDecision.Deny("author row not found")
}

/**
 * Load rule whose decision reads through edges: the `withUser()` eager
 * load, the `queryUsers()` M2M traversal, and the staged index helper
 * all run through the privacy client, so the hop rows they materialize
 * pass the caller's LOAD privacy like any other rule read — a viewer
 * who cannot load users gets the denial, not the graph. (The rule
 * reads memberships and groups, never articles, so the inner reads
 * cannot re-enter this rule; the traversal's group source rows are
 * structural and never materialize.)
 */
private val MembersReachableViaEdgesUnlockArticles = ArticleLoadPrivacyRule { ctx ->
    val memberships = ctx.client.memberships.query { withUser() }.all().getOrThrow()
    val traversedMember = ctx.client.groups.query { }.queryUsers().firstOrNull().getOrThrow()
    val indexedMember = ctx.client.users.indexes.email("alice@test.com").find().getOrThrow()
    if (memberships.any { it.edges.user.requireLoaded() != null } && traversedMember != null && indexedMember != null) {
        PrivacyDecision.Allow
    } else {
        PrivacyDecision.Continue
    }
}

/**
 * A rule that deliberately uses a storage-level existence fact. The
 * raw terminal does not materialize users or evaluate their LOAD
 * policy, which also avoids recursively entering user LOAD rules.
 */
private val RawUserExistsRule = ArticleLoadPrivacyRule { ctx ->
    if (ctx.client.users.query { }.rawExists().getOrThrow()) PrivacyDecision.Allow
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

private object RawUserExistsArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(RawUserExistsRule) }
    }
}

// ---- Predicate-inference limitation pin ----

private val AllowAllMembershipLoads = MembershipLoadPrivacyRule { PrivacyDecision.Allow }

/**
 * Documented-limitation pin (Privacy Limitations → Predicate-Based
 * Inference): `has { }` compiles to an EXISTS subquery and never
 * LOAD-checks the related rows, so this rule is influenced by the
 * hidden user's email even for viewers who cannot load that user. The
 * materialized rows (memberships) do pass LOAD privacy. Pinned so a
 * future change (e.g. edge-derived LOAD privacy) flips this
 * deliberately, not by accident.
 */
private val SecretMemberEmailUnlocksArticles = ArticleLoadPrivacyRule { ctx ->
    val secretMembership = ctx.client.memberships.query {
        where(Membership.user.has { where(User.email.eq("alice@test.com")) })
    }.firstOrNull().getOrThrow()
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
 * End-to-end semantics of the privacy-context read client
 * (`asPrivacyReadClientForInternalUse(privacy)`, exposed as
 * `EntPrivacyReadClient`): rule reads are viewer-scoped (asserted on
 * both denial projections — the rethrowing `getOrThrow` and the
 * null-collapsing `visibleOrNull`), transaction-scoped, and still pass
 * through read interceptors under the caller's viewer. Raw terminals
 * (`rawCount` / `rawExists` / raw aggregates) are explicit
 * storage-level reads: they remain available and skip LOAD privacy and
 * entity materialization. The compile-time no-writes and opt-in-gate
 * guarantees are pinned in `codegen`'s `PrivacyReadClientCompileTest`.
 */
class PrivacyReadClientIntegrationTest : PostgresTestBase() {

    private fun freshClient(config: EntClientConfig.() -> Unit): EntClient =
        EntClient(resetAndDriver(), config)

    private fun seedAuthorAndArticle(client: EntClient): Pair<User, Article> =
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val author = sys.users.create { name = "Alice"; email = "alice@test.com" }.saveAndLoad().getOrThrow()
            val article = sys.articles.create { title = "T"; authorId = author.id }.saveAndLoad().getOrThrow()
            author to article
        }

    // ---- Viewer scoping, rethrowing projection ----

    @Test
    fun `rule reads are viewer-scoped - getOrThrow surfaces the inner denial`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(AuthorReadableArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)

        // Authenticated viewer: the rule's user read succeeds
        // viewer-scoped, so the article is visible.
        val asUser = client.withPrivacyContext(PrivacyContext(Viewer.User(author.id))) { c ->
            c.articles.findById(article.id).getOrThrow()
        }
        assertNotNull(asUser)

        // Anonymous: the rule's findById runs as Anonymous, its
        // getOrThrow rethrows the users LOAD denial, and the article's
        // root terminal captures it — the Failed names "User", not
        // "Article", proving the inner read was viewer-scoped. A
        // bypass-scoped read (the validation client's posture) would
        // have returned the row and allowed the article.
        val failed = assertIs<ReadResult.Failed>(client.articles.findById(article.id))
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals("User", ex.denials.single().entityType)
    }

    // ---- Viewer scoping, filtering projection ----

    @Test
    fun `rule reads are viewer-scoped - visibleOrNull collapses the denial to null`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(AuthorVisiblyReadableArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)

        val asUser = client.withPrivacyContext(PrivacyContext(Viewer.User(author.id))) { c ->
            c.articles.findById(article.id).getOrThrow()
        }
        assertNotNull(asUser)

        // Anonymous: visibleOrNull collapses the users denial to
        // Success(null), the rule falls through to Continue, and the
        // fail-closed list denies the ARTICLE — the denial names
        // "Article", the distinct outcome of the filtering projection.
        val failed = assertIs<ReadResult.Failed>(client.articles.findById(article.id))
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals("Article", ex.denials.single().entityType)
    }

    // ---- Viewer scoping, edge reads ----

    @Test
    fun `rule eager loads, traversals, and index helpers are viewer-scoped`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                memberships(OpenMembershipPolicy)
                articles(MemberGateArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val group = sys.groups.create { name = "G" }.saveAndLoad().getOrThrow()
            sys.memberships.create { userId = author.id; groupId = group.id; role = "member" }.save().getOrThrow()
        }

        // Authenticated viewer: the eager-loaded membership carries its
        // user, the traversal reaches the member, and the index helper
        // finds the email — the rule allows the article.
        val asUser = client.withPrivacyContext(PrivacyContext(Viewer.User(author.id))) { c ->
            c.articles.findById(article.id).getOrThrow()
        }
        assertNotNull(asUser)

        // Anonymous: the same hops run viewer-scoped through the privacy
        // client, so the users LOAD denial surfaces from inside the
        // rule's eager load — an EagerEdge-origin EntPrivacyDeniedException
        // naming "User" — instead of the hidden rows influencing the
        // decision. The rule reads through `all().getOrThrow()`, so the
        // rethrown denial is captured by the article's root terminal as
        // its own Failed.
        val failed = assertIs<ReadResult.Failed>(client.articles.findById(article.id))
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals("User", ex.denials.single().entityType)
        assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
    }

    // ---- Transaction scoping ----

    @Test
    fun `rule reads inside a transaction see uncommitted writes`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.User(42L)) }
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
            val author = tx.users.create { name = "Bob"; email = "bob@test.com" }.saveAndLoad().orRollback()
            tx.articles.create { title = "In tx"; authorId = author.id }.saveAndLoad().orRollback()
        }.getOrThrow()
        assertNotNull(article)
    }

    // ---- Raw terminals are storage-level on viewer-scoped readers ----

    @Test
    fun `raw terminals can provide storage facts without evaluating LOAD privacy`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(RawUserExistsArticlePolicy)
            }
        }
        val (_, article) = seedAuthorAndArticle(client)

        // Anonymous cannot materialize the user row, but the article
        // rule may deliberately use its storage-level existence as an
        // authorization input. No User entity is returned from the raw
        // terminal, so User LOAD privacy is never evaluated.
        val loaded = client.articles.findById(article.id).getOrThrow()
        assertNotNull(loaded)
    }

    @Test
    fun `raw aggregates are available inside privacy rules`() {
        val captured = mutableListOf<ReadResult<String?>>()
        val storageAggregateRule = ArticleLoadPrivacyRule { ctx ->
            captured.add(ctx.client.users.query { }.rawMin(User.email))
            PrivacyDecision.Allow
        }
        val storageAggregatePolicy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy { load(storageAggregateRule) }
            }
        }
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(storageAggregatePolicy)
            }
        }
        val (_, article) = seedAuthorAndArticle(client)

        val loaded = client.articles.findById(article.id).getOrThrow()
        assertNotNull(loaded)
        assertEquals(ReadResult.Success("alice@test.com"), captured.single())
    }

    // ---- Predicate-based inference (documented limitation) ----

    @Test
    fun `has predicates inside rules are EXISTS-scoped, not LOAD-checked - documented limitation`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                memberships(OpenMembershipPolicy)
                articles(SecretMemberGateArticlePolicy)
            }
        }
        val (alice, article) = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val alice = sys.users.create { name = "Alice"; email = "alice@test.com" }.saveAndLoad().getOrThrow()
            val group = sys.groups.create { name = "G" }.saveAndLoad().getOrThrow()
            sys.memberships.create { groupId = group.id; userId = alice.id; role = "member" }.save().getOrThrow()
            val article = sys.articles.create { title = "T"; authorId = alice.id }.saveAndLoad().getOrThrow()
            alice to article
        }

        // The caller cannot load the user directly…
        val userRead = assertIs<ReadResult.Failed>(client.users.findById(alice.id))
        assertIs<EntPrivacyDeniedException>(userRead.exception)

        // …yet the rule's has{} predicate matches her email inside the
        // EXISTS subquery, the (LOAD-checked, allowed) membership row
        // comes back, and the article unlocks: the hidden row influenced
        // authorization. This pins the documented predicate-inference
        // limitation — see docs/08-privacy-limitations.md. If this test
        // starts failing because the related row is now LOAD-checked,
        // that is edge-derived-LOAD-privacy-shaped work landing; update
        // the docs with it.
        assertNotNull(client.articles.findById(article.id).getOrThrow())
    }

    // ---- Interceptor semantics ----

    @Test
    fun `read interceptors run for rule queries and observe the caller viewer`() {
        val seenViewers = mutableListOf<Viewer>()
        val recorder = QueryInterceptor<User> { _, context -> seenViewers.add(context.privacy.viewer) }

        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(AuthorReadableArticlePolicy)
            }
            interceptors { users(recorder, "privacy-read-recorder") }
        }
        val (author, article) = seedAuthorAndArticle(client)

        client.withPrivacyContext(PrivacyContext(Viewer.User(author.id))) { c ->
            c.articles.findById(article.id).getOrThrow()
        }

        // The rule's user read passed through the interceptor chain with
        // the CALLER's viewer — not a bypass. (The validation client is
        // the one that fixes PrivacyBypass; privacy rule reads must not.)
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
}
