package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntClientConfig
import entkt.integrationtest.ent.EntReadClient
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
import entkt.runtime.privacy.PrivacyDeniedException
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * Graph-reading load rule on the throwing terminal. The explicit
 * `EntReadClient` type pins the context's client property — this file
 * stops compiling if privacy contexts regress to the full `EntClient`.
 * Under the caller's context a viewer who cannot read users gets the
 * inner read's PrivacyDeniedException, not the row.
 */
private val AllowIfAuthorReadable = ArticleLoadPrivacyRule { ctx ->
    val client: EntReadClient = ctx.client
    if (client.users.byIdOrNull(ctx.entity.authorId) != null) PrivacyDecision.Allow
    else PrivacyDecision.Continue
}

/** Same invariant on the filtering terminal: denial collapses to null. */
private val AllowIfAuthorVisiblyReadable = ArticleLoadPrivacyRule { ctx ->
    if (ctx.client.users.visibleByIdOrNull(ctx.entity.authorId) != null) PrivacyDecision.Allow
    else PrivacyDecision.Continue
}

/** Create rule that reads the graph — the transaction-scoping probe. */
private val AuthorRowMustExist = ArticleCreatePrivacyRule { ctx ->
    if (ctx.client.users.byIdOrNull(ctx.candidate.authorId) != null) PrivacyDecision.Allow
    else PrivacyDecision.Deny("author row not found")
}

/**
 * A rule that misuses a raw terminal. rawExists skips LOAD privacy, so
 * inside a viewer-scoped rule it could leak invisible rows into the
 * authorization decision — the runtime gate must reject it loudly.
 */
private val LeakyRawExistsRule = ArticleLoadPrivacyRule { ctx ->
    if (ctx.client.users.query { }.rawExists()) PrivacyDecision.Allow
    else PrivacyDecision.Continue
}

/**
 * Same misuse through an aggregate `*OrError` terminal. These wrap
 * their bodies in a catch-all that converts exceptions to `Err`, so
 * the gate must fire BEFORE the try — if it folded into
 * Err(DriverFailure), this rule would proceed to Allow on a fabricated
 * "no data" answer instead of failing loudly.
 */
private val LeakyAggregateOrErrorRule = ArticleLoadPrivacyRule { ctx ->
    ctx.client.users.query { }.rawMinOrError(User.email)
    PrivacyDecision.Allow
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

private object LeakyRawExistsArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(LeakyRawExistsRule) }
    }
}

private object LeakyAggregateOrErrorArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy { load(LeakyAggregateOrErrorRule) }
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
    }.firstOrNull()
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
 * End-to-end semantics of the read-only privacy client: rule reads are
 * viewer-scoped (asserted on both denial surfaces — the throwing byId
 * and the null-collapsing visibleById), transaction-scoped, and still
 * pass through read interceptors under the caller's viewer. The
 * compile-time no-writes and opt-in-gate guarantees are pinned in
 * `codegen`'s `PrivacyReadClientCompileTest`.
 */
class PrivacyReadClientIntegrationTest : PostgresTestBase() {

    private fun freshClient(config: EntClientConfig.() -> Unit): EntClient =
        EntClient(resetAndDriver(), config)

    private fun seedAuthorAndArticle(client: EntClient): Pair<User, Article> =
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val author = sys.users.create { name = "Alice"; email = "alice@test.com" }.save()
            val article = sys.articles.create { title = "T"; authorId = author.id }.save()
            author to article
        }

    // ---- Viewer scoping, throwing surface ----

    @Test
    fun `rule reads are viewer-scoped - byIdOrNull surfaces the inner denial`() {
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
            c.articles.byIdOrNull(article.id)
        }
        assertNotNull(asUser)

        // Anonymous: the rule's byIdOrNull runs as Anonymous and the
        // users LOAD denial propagates out — entity "User", not
        // "Article", proving the inner read was viewer-scoped. A
        // bypass-scoped read (the validation client's posture) would
        // have returned the row and allowed the article.
        val ex = assertFailsWith<PrivacyDeniedException> { client.articles.byIdOrNull(article.id) }
        assertEquals("User", ex.entity)
    }

    // ---- Viewer scoping, filtering surface ----

    @Test
    fun `rule reads are viewer-scoped - visibleByIdOrNull collapses the denial to null`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(AuthorVisiblyReadableArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)

        val asUser = client.withPrivacyContext(PrivacyContext(Viewer.User(author.id))) { c ->
            c.articles.byIdOrNull(article.id)
        }
        assertNotNull(asUser)

        // Anonymous: visibleByIdOrNull collapses the users denial to
        // null, the rule falls through to Continue, and the fail-closed
        // list denies the ARTICLE — the denial names "Article", the
        // distinct outcome of the filtering surface.
        val ex = assertFailsWith<PrivacyDeniedException> { client.articles.byIdOrNull(article.id) }
        assertEquals("Article", ex.entity)
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
            val author = tx.users.create { name = "Bob"; email = "bob@test.com" }.save()
            tx.articles.create { title = "In tx"; authorId = author.id }.save()
        }
        assertNotNull(article)
    }

    // ---- Raw terminals are gated on viewer-scoped readers ----

    @Test
    fun `raw terminals throw inside privacy rules instead of bypassing LOAD privacy`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(LeakyRawExistsArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)

        // The rule's rawExists hits the privacy-bypassing-read gate — a
        // loud IllegalStateException, not a silent existence probe over
        // rows the viewer cannot see. (Validation rules keep raw
        // terminals: their reader's bypass context makes raw ≡ visible —
        // pinned by ValidationReadClientIntegrationTest's rawExists rule.)
        val ex = assertFailsWith<IllegalStateException> {
            client.withPrivacyContext(PrivacyContext(Viewer.User(author.id))) { c ->
                c.articles.byIdOrNull(article.id)
            }
        }
        assertTrue(
            "rawExists" in (ex.message ?: "") && "privacy-rule" in (ex.message ?: ""),
            "Expected the raw-terminal gate message naming rawExists; got: ${ex.message}",
        )
    }

    @Test
    fun `aggregate OrError terminals throw at the gate instead of folding into Err`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(AuthenticatedReadersUserPolicy)
                articles(LeakyAggregateOrErrorArticlePolicy)
            }
        }
        val (author, article) = seedAuthorAndArticle(client)

        // If the gate ran inside the OrError catch-all, rawMinOrError
        // would return Err(DriverFailure), the rule would Allow, and this
        // read would SUCCEED — the assertion below is what distinguishes
        // throw-at-gate from fold-into-Err.
        val ex = assertFailsWith<IllegalStateException> {
            client.withPrivacyContext(PrivacyContext(Viewer.User(author.id))) { c ->
                c.articles.byIdOrNull(article.id)
            }
        }
        assertTrue(
            "rawMinOrError" in (ex.message ?: ""),
            "Expected the gate message naming rawMinOrError; got: ${ex.message}",
        )
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
            val alice = sys.users.create { name = "Alice"; email = "alice@test.com" }.save()
            val group = sys.groups.create { name = "G" }.save()
            sys.memberships.create { groupId = group.id; userId = alice.id; role = "member" }.save()
            val article = sys.articles.create { title = "T"; authorId = alice.id }.save()
            alice to article
        }

        // The caller cannot load the user directly…
        assertFailsWith<PrivacyDeniedException> { client.users.byIdOrNull(alice.id) }

        // …yet the rule's has{} predicate matches her email inside the
        // EXISTS subquery, the (LOAD-checked, allowed) membership row
        // comes back, and the article unlocks: the hidden row influenced
        // authorization. This pins the documented predicate-inference
        // limitation — see docs/08-privacy-limitations.md. If this test
        // starts failing because the related row is now LOAD-checked,
        // that is edge-derived-LOAD-privacy-shaped work landing; update
        // the docs with it.
        assertNotNull(client.articles.byIdOrNull(article.id))
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
            c.articles.byIdOrNull(article.id)
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
