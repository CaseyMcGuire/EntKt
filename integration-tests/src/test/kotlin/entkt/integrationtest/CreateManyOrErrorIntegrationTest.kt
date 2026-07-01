package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.result.EntError
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntResult
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.privacy.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end coverage for `createManyOrError`.
 *
 * The legacy throwing `createMany(*blocks): List<T>` is removed; this
 * suite pins the structured-result variant's contract:
 *
 *  - Transaction-required: throws TransactionRequiredException outside
 *    a tx, regardless of the client-level TransactionRequirement
 *    (the helper's all-or-nothing semantics require a tx).
 *  - Zero-blocks: returns Ok(emptyList()) without preflight.
 *  - Per-row failure: returns Err for the first failing block; the
 *    helper itself does not call rollback (caller decides via bind()
 *    inside withTransactionOrError). On Postgres, a constraint
 *    violation inside the surrounding tx poisons the tx anyway, so
 *    earlier writes also roll back at commit-time — see the
 *    `withTransactionOrError + bind()` test below for the canonical
 *    all-or-nothing usage.
 */
class CreateManyOrErrorIntegrationTest : PostgresTestBase() {

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
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
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
        }
    }

    @Test
    fun `zero-block call returns Ok(emptyList()) outside any tx`() {
        val client = freshClient()
        val result = client.users.createManyOrError()
        assertTrue(result is EntResult.Ok)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `single-block call outside a tx throws TransactionRequiredException`() {
        val client = freshClient()
        val ex = assertFailsWith<TransactionRequiredException> {
            client.users.createManyOrError({
                name = "A"
                email = "a@example.com"
            })
        }
        assertTrue(ex.message!!.contains("createManyOrError"))
        // No row inserted — the preflight rejected before any block ran.
        assertEquals(0L, client.users.query().rawCount())
    }

    @Test
    fun `multi-block call inside a tx returns Ok with all created entities`() {
        val client = freshClient()

        val result = client.withTransaction { tx ->
            tx.users.createManyOrError(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
                { name = "C"; email = "c@example.com" },
            )
        }

        assertTrue(result is EntResult.Ok)
        val users = result.value
        assertEquals(3, users.size)
        assertEquals(setOf("A", "B", "C"), users.map { it.name }.toSet())

        // Rows persisted via the committed tx.
        assertEquals(3L, client.users.query().rawCount())
    }

    @Test
    fun `per-row failure returns Err for the first failing block AND short-circuits the rest`() {
        val client = freshClient()
        // Seed a user so block 2 trips the unique-email constraint.
        client.users.create { name = "Existing"; email = "dup@example.com" }.saveOrThrow()

        val result = client.withTransaction { tx ->
            tx.users.createManyOrError(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "dup@example.com" },  // unique violation
                { name = "C"; email = "c@example.com" },  // must NOT run
            )
        }

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals(EntOperation.CREATE, error.operation)
        assertEquals("User", error.entity)
        assertEquals("23505", error.code)

        // Short-circuit verified: C was never attempted, so the
        // c@example.com row is absent. A was attempted but the 23505
        // on B poisoned the tx, so Postgres rolls back A at commit
        // time — only the pre-tx "Existing" row survives. The
        // canonical "all-or-nothing on Err" pattern is
        // withTransactionOrError + bind(), exercised below.
        assertEquals(0L, client.users.query { where(User.email eq "c@example.com") }.rawCount())
        assertEquals(1L, client.users.query().rawCount())  // Existing only
    }

    @Test
    fun `Err inside withTransactionOrError + bind() rolls back the entire tx`() {
        val client = freshClient()
        client.users.create { name = "Existing"; email = "dup@example.com" }.saveOrThrow()

        // Combine createManyOrError with withTransactionOrError +
        // bind() — the classic all-or-nothing usage. bind() on the
        // Err triggers the rollback at the boundary.
        val result = client.withTransactionOrError<List<User>> { tx ->
            tx.users.createManyOrError(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "dup@example.com" },
            ).bind()
        }

        assertTrue(result is EntResult.Err)
        assertTrue(result.error is EntError.ConstraintViolation)

        // Rollback verified: only the originally-seeded user survives.
        assertEquals(1L, client.users.query().rawCount())
    }
}
