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
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.validation.ValidationDecision
import kotlin.Unit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the create-side canonical terminals:
 * `save(): MutationResult<Unit>` and
 * `saveAndLoad(): MutationResult<Entity>`. Exercises the full failure
 * surface — validation, privacy, unique constraint, FK constraint —
 * against `PostgresDriver`, pinning that pre-write failures hardcode
 * `MutationWriteState.NotPersisted` and that `getOrThrow()` throws the
 * exact stored exception instance. Constraint classification
 * (SQLSTATE 23xxx) is also covered driver-side in
 * `SqlstateConstraintMappingPostgresIntegrationTest`.
 */
class CreateResultVariantsIntegrationTest : PostgresTestBase() {

    private object AllowAll : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAll,
        userPolicy: EntityPolicy<User, UserPolicyScope> = OpenUser,
    ): EntClient {
        val driver = resetAndDriver()
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
    fun `save returns Success(Unit) on success`() {
        val client = freshClient()

        val result = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        assertEquals(MutationResult.Success(Unit), result)
    }

    @Test
    fun `saveAndLoad returns Success with a non-null entity`() {
        val client = freshClient()

        val result = client.users.create {
            name = "Bob"
            email = "bob@example.com"
        }.saveAndLoad()

        val success = assertIs<MutationResult.Success<User>>(result)
        assertNotNull(success.value.id)
        assertEquals("Bob", success.value.name)
    }

    @Test
    fun `save does not apply returned-entity LOAD privacy`() {
        // A viewer that can create but never load: save() discloses no
        // entity, so it succeeds where saveAndLoad() would fail.
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("no disclosure") })
                    create(ArticleCreatePrivacyRule { _, _ -> PrivacyDecision.Allow })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = policy)
        val author = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        }

        val result = client.articles.create {
            title = "Sealed"
            published = true
            authorId = author.id
        }.save()

        assertEquals(MutationResult.Success(Unit), result)
    }

    // ---- Validation ----

    @Test
    fun `save returns Failed(EntValidationException) when a required field is missing`() {
        val client = freshClient()

        // `email` is required on the User schema.
        val result = client.users.create { name = "Carol" }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals(1, ex.violations.size)
        assertEquals("email", ex.violations[0].field)
        assertTrue(ex.violations[0].message.contains("email is required"))
    }

    @Test
    fun `saveAndLoad returns Failed(EntValidationException) when a rule rejects the candidate`() {
        val rejectUnpublished = ArticleCreateValidationRule { _, item ->
            if (!item.candidate.published) {
                ValidationDecision.Invalid("must be published", field = "published")
            } else {
                ValidationDecision.Valid
            }
        }
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
                validation { create(rejectUnpublished) }
            }
        }
        val client = freshClient(articlePolicy = policy)
        val author = client.users.create { name = "Alice"; email = "a@example.com" }
            .saveAndLoad().getOrThrow()

        val result = client.articles.create {
            title = "Draft"
            published = false
            authorId = author.id
        }.saveAndLoad()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals(1, ex.violations.size)
        assertEquals("published", ex.violations[0].field)
    }

    @Test
    fun `getOrThrow throws the exact stored EntValidationException`() {
        val client = freshClient()

        val result = client.users.create { name = "Dan" }.saveAndLoad()
        val failed = assertIs<MutationResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntValidationException) {
            assertSame(failed.exception, e)
            assertEquals(MutationWriteState.NotPersisted, e.writeState)
        }
    }

    // ---- Privacy ----

    @Test
    fun `save returns Failed(EntMutationPrivacyDeniedException) when CREATE privacy denies`() {
        val requireAuth = ArticleCreatePrivacyRule { context, _ ->
            if (context.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
            else PrivacyDecision.Allow
        }
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow })
                    create(requireAuth)
                }
            }
        }
        val client = freshClient(viewer = Viewer.Anonymous, articlePolicy = policy)
        // Seed an author with a system context — the test's privacy
        // boundary is on Article, not User.
        val author = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "Eve"; email = "eve@example.com" }.saveAndLoad().getOrThrow()
        }

        val result = client.articles.create {
            title = "Hidden"
            published = true
            authorId = author.id
        }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("authentication required", ex.reason)
        // A create denial happens before any row exists — no usable key.
        assertNull(ex.entityKey)
    }

    @Test
    fun `getOrThrow throws the exact stored EntMutationPrivacyDeniedException`() {
        val deny = ArticleCreatePrivacyRule { _, _ -> PrivacyDecision.Deny("nope") }
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow })
                    create(deny)
                }
            }
        }
        // Viewer.PrivacyBypass bypasses privacy checks by design — use an
        // authenticated viewer so the deny rule actually fires.
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = policy)
        val author = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "F"; email = "f@example.com" }.saveAndLoad().getOrThrow()
        }

        val result = client.articles.create {
            title = "x"
            published = true
            authorId = author.id
        }.saveAndLoad()
        val failed = assertIs<MutationResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntMutationPrivacyDeniedException) {
            assertSame(failed.exception, e)
            assertEquals("nope", e.reason)
        }
    }

    // ---- Constraint violations ----

    @Test
    fun `save returns Failed(EntConstraintViolationException) for unique violation`() {
        val client = freshClient()
        client.users.create { name = "G"; email = "dup@example.com" }.save().getOrThrow()

        val result = client.users.create {
            name = "H"
            email = "dup@example.com"
        }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("23505", ex.driverCode)
        // PSQLException carries the unique-index name on
        // ServerErrorMessage.constraint — the classifier surfaces it as a
        // direct property, and the original driver exception is the cause.
        assertNotNull(ex.constraint)
        assertTrue(ex.constraint!!.contains("email"), "constraint should mention email: ${ex.constraint}")
        assertIs<org.postgresql.util.PSQLException>(ex.cause)
    }

    @Test
    fun `getOrThrow throws the exact stored EntConstraintViolationException`() {
        val client = freshClient()
        client.users.create { name = "I"; email = "dup2@example.com" }.save().getOrThrow()

        val result = client.users.create { name = "J"; email = "dup2@example.com" }.saveAndLoad()
        val failed = assertIs<MutationResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntConstraintViolationException) {
            assertSame(failed.exception, e)
            assertEquals("23505", e.driverCode)
            assertEquals(MutationWriteState.NotPersisted, e.writeState)
        }
    }

    @Test
    fun `save returns Failed(EntConstraintViolationException) for FK violation`() {
        val client = freshClient()

        // Article.authorId references a User row that doesn't exist.
        val result = client.articles.create {
            title = "Orphan"
            published = true
            authorId = 999_999L
        }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("23503", ex.driverCode)
        assertNotNull(ex.constraint)
    }

    // ---- Failed does not persist on the pre-write / constraint paths ----

    @Test
    fun `a validation failure does not persist a row`() {
        val client = freshClient()

        assertIs<MutationResult.Failed>(client.users.create { name = "K" }.save())

        // Driver count must be zero — nothing reached insert().
        assertEquals(0L, client.users.query().rawCount().getOrThrow())
    }

    @Test
    fun `a unique conflict does not persist the second row`() {
        val client = freshClient()
        client.users.create { name = "L"; email = "once@example.com" }.save().getOrThrow()

        assertIs<MutationResult.Failed>(
            client.users.create { name = "M"; email = "once@example.com" }.save(),
        )

        // Still one row — the conflicting insert rolled back at the
        // driver's row-level uniqueness check.
        assertEquals(1L, client.users.query().rawCount().getOrThrow())
    }
}
