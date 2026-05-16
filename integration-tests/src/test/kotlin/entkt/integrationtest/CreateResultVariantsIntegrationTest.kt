package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleCreateValidationRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.runtime.EntConstraintViolationException
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.EntPrivacyDeniedException
import entkt.runtime.EntResult
import entkt.runtime.EntValidationException
import entkt.runtime.EntityPolicy
import entkt.runtime.InMemoryDriver
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.ValidationDecision
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for create-side `saveOrError()` / `saveOrThrow()`
 * (Result Variants RFC, Phase 3). Exercises the full failure surface
 * — validation, privacy, unique constraint, FK constraint — against
 * the in-memory driver. Postgres SQLSTATE coverage lives in Phase 7.
 *
 * The in-memory driver's classifier (Phase 2) maps its own validator
 * message prefixes to [EntError.ConstraintViolation], so generated
 * create-side `saveOrError()` returns the structured error variant
 * for both unique and FK conflicts here. The Postgres path uses
 * SQLSTATE 23xxx and is asserted separately in
 * `PostgresDriverClassifyTest`.
 */
class CreateResultVariantsIntegrationTest {

    private object AllowAll : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.System,
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAll,
        userPolicy: EntityPolicy<User, UserPolicyScope> = OpenUser,
    ): EntClient {
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(userPolicy)
            }
        }
    }

    // ---- Success path ----

    @Test
    fun `saveOrError returns Ok on success`() {
        val client = freshClient()

        val result = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveOrError()

        assertTrue(result is EntResult.Ok)
        val user = result.value
        assertNotNull(user.id)
        assertEquals("Alice", user.name)
    }

    @Test
    fun `saveOrThrow returns the entity on success`() {
        val client = freshClient()

        val user = client.users.create {
            name = "Bob"
            email = "bob@example.com"
        }.saveOrThrow()

        assertNotNull(user.id)
        assertEquals("Bob", user.name)
    }

    // ---- Validation ----

    @Test
    fun `saveOrError returns Err(ValidationFailed) when a required field is missing`() {
        val client = freshClient()

        // `email` is required on the User schema. Omitting it lands
        // ValidationException inside save(), which saveOrError lifts
        // into Err(ValidationFailed) with the rule-DSL's
        // ValidationDecision.Invalid bridged into ValidationViolation.
        val result = client.users.create {
            name = "Carol"
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ValidationFailed)
        assertEquals("User", error.entity)
        assertEquals(EntOperation.CREATE, error.operation)
        assertEquals(1, error.violations.size)
        assertEquals("email", error.violations[0].field)
        assertTrue(error.violations[0].message.contains("email is required"))
    }

    @Test
    fun `saveOrError returns Err(ValidationFailed) when a rule rejects the candidate`() {
        val rejectUnpublished = ArticleCreateValidationRule { ctx ->
            if (!ctx.candidate.published) {
                ValidationDecision.Invalid("must be published", field = "published")
            } else {
                ValidationDecision.Valid
            }
        }
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
                validation { create(rejectUnpublished) }
            }
        }
        val client = freshClient(articlePolicy = policy)
        val author = client.users.create { name = "Alice"; email = "a@example.com" }.saveOrThrow()

        val result = client.articles.create {
            title = "Draft"
            published = false
            authorId = author.id
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ValidationFailed)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.CREATE, error.operation)
        assertEquals(1, error.violations.size)
        assertEquals("published", error.violations[0].field)
    }

    @Test
    fun `saveOrThrow throws EntValidationException carrying the EntError`() {
        val client = freshClient()

        val ex = assertFailsWith<EntValidationException> {
            client.users.create { name = "Dan" }.saveOrThrow()
        }
        val validationFailed = ex.validationFailed
        assertEquals("User", validationFailed.entity)
        assertEquals(EntOperation.CREATE, validationFailed.operation)
        assertEquals(1, validationFailed.violations.size)
        assertEquals("email", validationFailed.violations[0].field)
    }

    // ---- Privacy ----

    @Test
    fun `saveOrError returns Err(PrivacyDenied) when CREATE privacy denies`() {
        val requireAuth = ArticleCreatePrivacyRule { ctx ->
            if (ctx.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
            else PrivacyDecision.Continue
        }
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { PrivacyDecision.Allow })
                    create(requireAuth)
                }
            }
        }
        val client = freshClient(viewer = Viewer.Anonymous, articlePolicy = policy)
        // Seed an author with a system context — the test's privacy
        // boundary is on Article, not User.
        val author = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.users.create { name = "Eve"; email = "eve@example.com" }.saveOrThrow()
        }

        val result = client.articles.create {
            title = "Hidden"
            published = true
            authorId = author.id
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.PrivacyDenied)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.CREATE, error.operation)
        assertEquals("authentication required", error.reason)
    }

    @Test
    fun `saveOrThrow throws EntPrivacyDeniedException`() {
        val deny = ArticleCreatePrivacyRule { PrivacyDecision.Deny("nope") }
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { PrivacyDecision.Allow })
                    create(deny)
                }
            }
        }
        // Viewer.System bypasses privacy checks by design — use an
        // authenticated viewer so the deny rule actually fires.
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = policy)
        val author = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.users.create { name = "F"; email = "f@example.com" }.saveOrThrow()
        }

        val ex = assertFailsWith<EntPrivacyDeniedException> {
            client.articles.create {
                title = "x"
                published = true
                authorId = author.id
            }.saveOrThrow()
        }
        assertEquals("Article", ex.privacyDenied.entity)
        assertEquals("nope", ex.privacyDenied.reason)
    }

    // ---- Constraint violations ----

    @Test
    fun `saveOrError returns Err(ConstraintViolation) for unique violation`() {
        val client = freshClient()
        client.users.create { name = "G"; email = "dup@example.com" }.saveOrThrow()

        val result = client.users.create {
            name = "H"
            email = "dup@example.com"
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals("User", error.entity)
        assertEquals(EntOperation.CREATE, error.operation)
        // InMemoryDriver's classifier emits 23505 for both "Unique
        // violation:" and "Primary key violation:" prefixes — see
        // InMemoryDriverClassifyTest for the message-prefix coverage.
        assertEquals("23505", error.code)
        assertEquals("unique", error.constraint)
    }

    @Test
    fun `saveOrThrow throws EntConstraintViolationException for unique violation`() {
        val client = freshClient()
        client.users.create { name = "I"; email = "dup2@example.com" }.saveOrThrow()

        val ex = assertFailsWith<EntConstraintViolationException> {
            client.users.create { name = "J"; email = "dup2@example.com" }.saveOrThrow()
        }
        assertEquals("User", ex.constraintViolation.entity)
        assertEquals("23505", ex.constraintViolation.code)
    }

    @Test
    fun `saveOrError returns Err(ConstraintViolation) for FK violation`() {
        val client = freshClient()

        // Article.authorId references a User row that doesn't exist.
        val result = client.articles.create {
            title = "Orphan"
            published = true
            authorId = 999_999L
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.CREATE, error.operation)
        assertEquals("23503", error.code)
        assertEquals("foreign_key", error.constraint)
    }

    // ---- saveOrError doesn't persist on Err ----

    @Test
    fun `saveOrError on validation failure does not persist a row`() {
        val client = freshClient()

        val result = client.users.create { name = "K" }.saveOrError()
        assertTrue(result is EntResult.Err)

        // Driver count must be zero — nothing reached insert().
        assertEquals(0L, client.users.query().rawCount())
    }

    @Test
    fun `saveOrError on unique conflict does not persist the second row`() {
        val client = freshClient()
        client.users.create { name = "L"; email = "once@example.com" }.saveOrThrow()

        val result = client.users.create { name = "M"; email = "once@example.com" }.saveOrError()
        assertTrue(result is EntResult.Err)

        // Still one row — the conflicting insert rolled back at the
        // driver's row-level uniqueness check.
        assertEquals(1L, client.users.query().rawCount())
    }
}
