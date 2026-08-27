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
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionResult
import entkt.runtime.validation.ValidationDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---- Validation rules exercising the read-only validation client ----

/**
 * The canonical read-validator: uniqueness via the query DSL. The
 * explicit `EntValidationReadClient` type pins the context's client
 * property — this file stops compiling if contexts regress to the full
 * `EntClient`, the shared `EntReadClient` interface, or the privacy
 * posture. The raw terminal stays available under the validation
 * client's bypass posture and answers as `ReadResult` like every read
 * terminal.
 */
private val UniqueEmailViaQuery = UserCreateValidationRule { context, item ->
    val client: EntValidationReadClient = context.client
    val taken = client.users.query { where(User.email.eq(item.candidate.email)) }.rawExists(context.readViewerContext).getOrThrow()
    if (taken) ValidationDecision.Invalid("email already taken", field = "email")
    else ValidationDecision.Valid
}

/** Same invariant through the staged index helpers (unique `find()` terminal). */
private val UniqueEmailViaIndex = UserCreateValidationRule { context, item ->
    if (context.client.users.indexes.email(item.candidate.email).find(context.readViewerContext).getOrThrow() != null) {
        ValidationDecision.Invalid("email already taken", field = "email")
    } else {
        ValidationDecision.Valid
    }
}

/** Existence check via findById on a *different* entity's repo. */
private val AuthorMustExist = ArticleCreateValidationRule { context, item ->
    if (context.client.users.findById(context.readViewerContext, item.candidate.authorId).getOrThrow() == null) {
        ValidationDecision.Invalid("author does not exist", field = "authorId")
    } else {
        ValidationDecision.Valid
    }
}

/**
 * Invariant check across edges: the `loadAuthor()` eager load and the
 * `queryAuthor()` traversal both run under the validation client's
 * fixed bypass context, so author rows LOAD privacy hides from the
 * caller are still visible to the invariant check.
 */
private val AuthorReachableViaEdges = ArticleCreateValidationRule { context, item ->
    val prior = context.client.articles.query {
        where(Article.authorId eq item.candidate.authorId)
        loadAuthor()
    }.all(context.readViewerContext).getOrThrow()
    val traversed = context.client.articles.query { where(Article.authorId eq item.candidate.authorId) }
        .queryAuthor().all(context.readViewerContext).getOrThrow()
    if (prior.all { it.edges.author.requireLoaded() != null } && (prior.isEmpty() || traversed.isNotEmpty())) {
        ValidationDecision.Valid
    } else {
        ValidationDecision.Invalid("author graph unreadable", field = "authorId")
    }
}

// ---- Privacy rules ----

private val AllowAllUserLoads = UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }
private val DenyAllUserLoads = UserLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("users are locked down") }
private val AllowAllArticleLoads = ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }
private val AllowAllArticleCreates = ArticleCreatePrivacyRule { _, _ -> PrivacyDecision.Allow }

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
 * End-to-end semantics of the stable `EntValidationReadClient`, used with
 * `context.readViewerContext` (`PrivacyBypass("validation read")`): validator
 * reads work across the whole
 * read surface — including raw terminals, which the bypass posture
 * keeps available (they return `ReadResult` like every read terminal,
 * unlike viewer-scoped privacy readers where the gate captures them as
 * `Failed(IllegalStateException)`) — run bypass-scoped, use the
 * transaction-scoped driver, and still pass through read interceptors.
 * A failed validator becomes
 * `MutationResult.Failed(EntValidationException)` at the mutation
 * terminal. The compile-time no-writes guarantee is pinned separately
 * in `codegen`'s `ValidationReadClientCompileTest`.
 */
class ValidationReadClientIntegrationTest : PostgresTestBase() {
    private val anonymousViewerContext = ViewerContext(Viewer.Anonymous)

    private fun freshClient(config: EntClientConfig.() -> Unit): EntClient =
        EntClient(resetAndDriver(), config)

    // ---- Reads through the validation client ----

    @Test
    fun `uniqueness validator via the query DSL rejects duplicates and allows fresh emails`() {
        val client = freshClient {

            policies { users(UniqueEmailViaQueryPolicy) }
        }

        client.users.create { name = "Alice"; email = "alice@test.com" }.save(testViewerContext).getOrThrow()
        client.users.create { name = "Bob"; email = "bob@test.com" }.save(testViewerContext).getOrThrow()

        val failed = assertIs<MutationResult.Failed>(
            client.users.create { name = "Mallory"; email = "alice@test.com" }.save(testViewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals("email", ex.violations.single().field)
    }

    @Test
    fun `uniqueness validator via the index helper rejects duplicates`() {
        val client = freshClient {

            policies { users(UniqueEmailViaIndexPolicy) }
        }

        client.users.create { name = "Alice"; email = "alice@test.com" }.save(testViewerContext).getOrThrow()

        val failed = assertIs<MutationResult.Failed>(
            client.users.create { name = "Mallory"; email = "alice@test.com" }.save(testViewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("email", ex.violations.single().field)
    }

    // ---- Bypass privacy scoping ----

    @Test
    fun `validator reads bypass LOAD privacy that blocks the calling viewer`() {
        val client = freshClient {

            policies {
                users(LockedDownUserPolicy)
                articles(AuthorCheckedArticlePolicy)
            }
        }
        val author = run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            sys.users.create { name = "Alice"; email = "alice@test.com" }.saveAndLoad(testViewerContext).getOrThrow()
        }

        // The calling viewer cannot read users at all…
        val denied = assertIs<ReadResult.Failed>(client.users.findById(anonymousViewerContext, author.id))
        assertIs<EntPrivacyDeniedException>(denied.exception)

        // …but the validator's existence check runs bypass-scoped, so the
        // create is validated against real data rather than blocked by
        // LOAD privacy.
        val article = client.articles.create {
            title = "Validated"
            authorId = author.id
        }.saveAndLoad(anonymousViewerContext).getOrThrow()
        assertNotNull(article)

        val failed = assertIs<MutationResult.Failed>(
            client.articles.create {
                title = "Orphaned"
                authorId = author.id + 999_999L
            }.save(anonymousViewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("authorId", ex.violations.single().field)
    }

    @Test
    fun `validator eager loads and traversals bypass LOAD privacy`() {
        val client = freshClient {

            policies {
                users(LockedDownUserPolicy)
                articles(EdgeCheckedArticlePolicy)
            }
        }
        val author = run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val alice = sys.users.create { name = "Alice"; email = "alice@test.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "First"; authorId = alice.id }.save(testViewerContext).getOrThrow()
            alice
        }

        // The calling viewer cannot read users at all…
        val denied = assertIs<ReadResult.Failed>(client.users.findById(anonymousViewerContext, author.id))
        assertIs<EntPrivacyDeniedException>(denied.exception)

        // …but the validator's eager load and traversal for the second
        // create run under the fixed bypass context: the hidden author
        // row materializes on both edge paths and the invariant passes
        // instead of surfacing the caller's denial.
        val second = client.articles.create { title = "Second"; authorId = author.id }.saveAndLoad(anonymousViewerContext).getOrThrow()
        assertNotNull(second)
    }

    // ---- Transaction scoping ----

    @Test
    fun `validator reads inside a transaction see uncommitted writes`() {
        val client = freshClient {

            policies { users(UniqueEmailViaQueryPolicy) }
        }

        // The first create is uncommitted when the second one validates.
        // Only a transaction-scoped validation read can see it — a read on
        // the outer (non-tx) connection would miss the row, validation
        // would pass, and the DB unique constraint would surface a
        // driver-level failure instead of EntValidationException.
        val result = client.withTransaction { tx ->
            tx.users.create { name = "First"; email = "dup@test.com" }.save(testViewerContext).orRollback()
            tx.users.create { name = "Second"; email = "dup@test.com" }.save(testViewerContext).orRollback()
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        assertIs<EntValidationException>(failed.exception)
    }

    // ---- Interceptor semantics ----

    @Test
    fun `read interceptors run for validator queries and observe the bypass context`() {
        val seenViewers = mutableListOf<Viewer>()
        val recorder = QueryInterceptor<User> { _, context -> seenViewers.add(context.viewerContext.viewer) }

        val client = freshClient {

            policies { users(UniqueEmailViaQueryPolicy) }
            interceptors { users(recorder, "validation-read-recorder") }
        }

        client.users.create { name = "Alice"; email = "alice@test.com" }.save(testViewerContext).getOrThrow()

        // The validator's uniqueness query passed through the interceptor
        // chain with the validation client's fixed bypass context.
        assertTrue(
            seenViewers.any { it is Viewer.PrivacyBypass && it.reason == "validation read" },
            "Expected the validation read to run through the users interceptor " +
                "with the fixed PrivacyBypass(\"validation read\") context; saw: $seenViewers",
        )
    }
}
