package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleCreateValidationRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntClientConfig
import entkt.integrationtest.ent.EntValidationReadClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreateValidationRule
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyDeniedException
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.requireLoaded
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---- Validation rules exercising the read-only validation client ----

/**
 * The canonical read-validator: uniqueness via the query DSL. The
 * explicit `EntValidationReadClient` type pins the context's client
 * property — this file stops compiling if contexts regress to the full
 * `EntClient`, the shared `EntReadClient` interface, or the privacy
 * posture.
 */
private val UniqueEmailViaQuery = UserCreateValidationRule { ctx ->
    val client: EntValidationReadClient = ctx.client
    val taken = client.users.query { where(User.email.eq(ctx.candidate.email)) }.rawExists()
    if (taken) ValidationDecision.Invalid("email already taken", field = "email")
    else ValidationDecision.Valid
}

/** Same invariant through the staged index helpers (unique terminal). */
private val UniqueEmailViaIndex = UserCreateValidationRule { ctx ->
    if (ctx.client.users.indexes.email(ctx.candidate.email).orNull() != null) {
        ValidationDecision.Invalid("email already taken", field = "email")
    } else {
        ValidationDecision.Valid
    }
}

/** Existence check via the byId family on a *different* entity's repo. */
private val AuthorMustExist = ArticleCreateValidationRule { ctx ->
    if (ctx.client.users.byIdOrNull(ctx.candidate.authorId) == null) {
        ValidationDecision.Invalid("author does not exist", field = "authorId")
    } else {
        ValidationDecision.Valid
    }
}

/**
 * Invariant check across edges: the `withAuthor()` eager load and the
 * `queryAuthor()` traversal both run under the validation client's
 * fixed bypass context, so author rows LOAD privacy hides from the
 * caller are still visible to the invariant check.
 */
private val AuthorReachableViaEdges = ArticleCreateValidationRule { ctx ->
    val prior = ctx.client.articles.query {
        where(Article.authorId eq ctx.candidate.authorId)
        withAuthor()
    }.allOrThrow()
    val traversed = ctx.client.articles.query { where(Article.authorId eq ctx.candidate.authorId) }
        .queryAuthor().allOrThrow()
    if (prior.all { it.edges.author.requireLoaded() != null } && (prior.isEmpty() || traversed.isNotEmpty())) {
        ValidationDecision.Valid
    } else {
        ValidationDecision.Invalid("author graph unreadable", field = "authorId")
    }
}

// ---- Privacy rules ----

private val AllowAllUserLoads = UserLoadPrivacyRule { PrivacyDecision.Allow }
private val DenyAllUserLoads = UserLoadPrivacyRule { PrivacyDecision.Deny("users are locked down") }
private val AllowAllArticleLoads = ArticleLoadPrivacyRule { PrivacyDecision.Allow }
private val AllowAllArticleCreates = ArticleCreatePrivacyRule { PrivacyDecision.Allow }

// ---- Policies ----

private object UniqueEmailViaQueryPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy { load(AllowAllUserLoads) }
        validation { create(UniqueEmailViaQuery) }
    }
}

private object UniqueEmailViaIndexPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy { load(AllowAllUserLoads) }
        validation { create(UniqueEmailViaIndex) }
    }
}

/** Users unreadable by application viewers — only bypass reads succeed. */
private object LockedDownUserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy { load(DenyAllUserLoads) }
    }
}

private object AuthorCheckedArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllArticleLoads)
            create(AllowAllArticleCreates)
        }
        validation { create(AuthorMustExist) }
    }
}

private object EdgeCheckedArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllArticleLoads)
            create(AllowAllArticleCreates)
        }
        validation { create(AuthorReachableViaEdges) }
    }
}

/**
 * End-to-end semantics of the validation-context read client
 * (`asValidationReadClientForInternalUse()`, which fixes the
 * `PrivacyBypass("validation read")` context and is exposed as
 * `EntValidationReadClient`): validator reads work across the whole
 * read surface — including raw terminals, which the bypass posture
 * keeps equivalent to visible ones — run System-scoped, use the
 * transaction-scoped driver, and still pass through read interceptors.
 * The compile-time no-writes guarantee is pinned separately in
 * `codegen`'s `ValidationReadClientCompileTest`.
 */
class ValidationReadClientIntegrationTest : PostgresTestBase() {

    private fun freshClient(config: EntClientConfig.() -> Unit): EntClient =
        EntClient(resetAndDriver(), config)

    // ---- Reads through the validation client ----

    @Test
    fun `uniqueness validator via the query DSL rejects duplicates and allows fresh emails`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(UniqueEmailViaQueryPolicy) }
        }

        client.users.create { name = "Alice"; email = "alice@test.com" }.save()
        client.users.create { name = "Bob"; email = "bob@test.com" }.save()

        val ex = assertFailsWith<ValidationException> {
            client.users.create { name = "Mallory"; email = "alice@test.com" }.save()
        }
        assertEquals("User", ex.entity)
        assertEquals("email", ex.violations.single().field)
    }

    @Test
    fun `uniqueness validator via the index helper rejects duplicates`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(UniqueEmailViaIndexPolicy) }
        }

        client.users.create { name = "Alice"; email = "alice@test.com" }.save()

        val ex = assertFailsWith<ValidationException> {
            client.users.create { name = "Mallory"; email = "alice@test.com" }.save()
        }
        assertEquals("email", ex.violations.single().field)
    }

    // ---- System privacy scoping ----

    @Test
    fun `validator reads bypass LOAD privacy that blocks the calling viewer`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(LockedDownUserPolicy)
                articles(AuthorCheckedArticlePolicy)
            }
        }
        val author = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            sys.users.create { name = "Alice"; email = "alice@test.com" }.save()
        }

        // The calling viewer cannot read users at all…
        assertFailsWith<PrivacyDeniedException> { client.users.byIdOrNull(author.id) }

        // …but the validator's existence check runs System-scoped, so the
        // create is validated against real data rather than blocked by
        // LOAD privacy.
        val article = client.articles.create {
            title = "Validated"
            authorId = author.id
        }.save()
        assertNotNull(article)

        val ex = assertFailsWith<ValidationException> {
            client.articles.create {
                title = "Orphaned"
                authorId = author.id + 999_999L
            }.save()
        }
        assertEquals("authorId", ex.violations.single().field)
    }

    @Test
    fun `validator eager loads and traversals bypass LOAD privacy`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies {
                users(LockedDownUserPolicy)
                articles(EdgeCheckedArticlePolicy)
            }
        }
        val author = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val alice = sys.users.create { name = "Alice"; email = "alice@test.com" }.save()
            sys.articles.create { title = "First"; authorId = alice.id }.save()
            alice
        }

        // The calling viewer cannot read users at all…
        assertFailsWith<PrivacyDeniedException> { client.users.byIdOrNull(author.id) }

        // …but the validator's eager load and traversal for the second
        // create run under the fixed bypass context: the hidden author
        // row materializes on both edge paths and the invariant passes
        // instead of surfacing the caller's denial.
        val second = client.articles.create { title = "Second"; authorId = author.id }.save()
        assertNotNull(second)
    }

    // ---- Transaction scoping ----

    @Test
    fun `validator reads inside a transaction see uncommitted writes`() {
        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(UniqueEmailViaQueryPolicy) }
        }

        // The first create is uncommitted when the second one validates.
        // Only a transaction-scoped validation read can see it — a read on
        // the outer (non-tx) connection would miss the row, validation
        // would pass, and the DB unique constraint would throw a
        // driver-level error instead of ValidationException.
        assertFailsWith<ValidationException> {
            client.withTransaction { tx ->
                tx.users.create { name = "First"; email = "dup@test.com" }.save()
                tx.users.create { name = "Second"; email = "dup@test.com" }.save()
            }
        }
    }

    // ---- Interceptor semantics ----

    @Test
    fun `read interceptors run for validator queries and observe the bypass context`() {
        val seenViewers = mutableListOf<Viewer>()
        val recorder = QueryInterceptor<User> { _, context -> seenViewers.add(context.privacy.viewer) }

        val client = freshClient {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(UniqueEmailViaQueryPolicy) }
            interceptors { users(recorder, "validation-read-recorder") }
        }

        client.users.create { name = "Alice"; email = "alice@test.com" }.save()

        // The validator's uniqueness query passed through the interceptor
        // chain with the validation client's fixed bypass context.
        assertTrue(
            seenViewers.any { it is Viewer.PrivacyBypass && it.reason == "validation read" },
            "Expected the validation read to run through the users interceptor " +
                "with the fixed PrivacyBypass(\"validation read\") context; saw: $seenViewers",
        )
    }
}
