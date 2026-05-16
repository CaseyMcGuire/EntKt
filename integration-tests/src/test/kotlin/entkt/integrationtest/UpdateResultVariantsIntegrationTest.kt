package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.runtime.EntConstraintViolationException
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.EntResult
import entkt.runtime.EntityPolicy
import entkt.runtime.InMemoryDriver
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the update-side `saveOrError()` /
 * `saveOrThrow()` driver-classification wiring landed in Phase 4 of
 * the Result Variants RFC. The Phase 1 catches (NotFound, NoChanges,
 * Privacy, Validation) are exercised elsewhere; this suite focuses on
 * the new `catch (Exception)` arm that routes through
 * `classifyDriverError` to produce `Err(ConstraintViolation)` for
 * recognized constraint failures (and `Err(DriverFailure)` for
 * uncategorized ones).
 */
class UpdateResultVariantsIntegrationTest {

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

    private fun freshClient(): EntClient {
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        return EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            policies {
                articles(AllowAll)
                users(OpenUser)
            }
        }
    }

    @Test
    fun `saveOrError returns Err(ConstraintViolation) for unique violation on update`() {
        val client = freshClient()
        client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        val bob = client.users.create { name = "B"; email = "b@example.com" }.saveOrThrow()

        // Attempting to retitle bob's email to "a@example.com" trips
        // the unique-email constraint on the second insert path.
        val result = client.users.update(bob.id) {
            email = "a@example.com"
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals("User", error.entity)
        assertEquals(EntOperation.UPDATE, error.operation)
        assertEquals("23505", error.code)
        assertEquals("unique", error.constraint)
    }

    @Test
    fun `saveOrThrow throws EntConstraintViolationException for unique violation on update`() {
        val client = freshClient()
        client.users.create { name = "C"; email = "c@example.com" }.saveOrThrow()
        val dan = client.users.create { name = "D"; email = "d@example.com" }.saveOrThrow()

        val ex = assertFailsWith<EntConstraintViolationException> {
            client.users.update(dan.id) {
                email = "c@example.com"
            }.saveOrThrow()
        }
        assertEquals("User", ex.constraintViolation.entity)
        assertEquals(EntOperation.UPDATE, ex.constraintViolation.operation)
        assertEquals("23505", ex.constraintViolation.code)
    }

    @Test
    fun `saveOrError returns Err(ConstraintViolation) for FK violation on update`() {
        val client = freshClient()
        val author = client.users.create { name = "E"; email = "e@example.com" }.saveOrThrow()
        val article = client.articles.create {
            title = "Hello"
            published = true
            authorId = author.id
        }.saveOrThrow()

        // Repoint authorId to a non-existent user.
        val result = client.articles.update(article.id) {
            authorId = 999_999L
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.UPDATE, error.operation)
        assertEquals("23503", error.code)
        assertEquals("foreign_key", error.constraint)
    }

    @Test
    fun `saveOrError on unique violation leaves owner row unchanged`() {
        val client = freshClient()
        client.users.create { name = "F"; email = "f@example.com" }.saveOrThrow()
        val guy = client.users.create { name = "G"; email = "g@example.com" }.saveOrThrow()

        val result = client.users.update(guy.id) {
            name = "Guy"
            email = "f@example.com"
        }.saveOrError()
        assertTrue(result is EntResult.Err)

        // The conflicting update did not partially apply — guy's email
        // is still its original value.
        val reread = client.users.byIdOrNull(guy.id)!!
        assertEquals("g@example.com", reread.email)
        assertEquals("G", reread.name)
    }
}
