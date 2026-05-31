package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.EntResult
import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end coverage for `EntClient.withTransactionOrError`. Pins:
 *
 *  - block returns `T` (not `EntResult<T>`); the helper wraps `T`
 *    into `Ok` on normal completion
 *  - `bind()` on `Err(...)` aborts the transaction, rolls back, and
 *    returns `Err(error)` to the outer caller
 *  - bound `Ok` paths commit if the block completes normally
 *  - exceptions other than `AbortEntResultTransaction` escape the
 *    helper unchanged (caller may want to log / rethrow)
 *  - runtime guard: if the block returns an `EntResult<*>` (e.g.
 *    caller forgot `.bind()`), the helper rolls back and throws
 *    IllegalStateException
 */
class WithTransactionOrErrorIntegrationTest : PostgresTestBase() {

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
            privacyContext { PrivacyContext(Viewer.System) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
        }
    }

    @Test
    fun `commits when all bound results are Ok`() {
        val client = freshClient()

        val result = client.withTransactionOrError { tx ->
            val author = tx.users.create { name = "A"; email = "a@example.com" }.saveOrError().bind()
            val article = tx.articles.create {
                title = "Hello"
                published = true
                authorId = author.id
            }.saveOrError().bind()
            article
        }

        assertTrue(result is EntResult.Ok)
        assertEquals("Hello", result.value.title)

        // Both rows survived the commit.
        assertEquals(1L, client.users.query().rawCount())
        assertEquals(1L, client.articles.query().rawCount())
    }

    @Test
    fun `rolls back when a bound result is Err and surfaces the first error`() {
        val client = freshClient()

        // First create the user so we trip a unique-email violation
        // on the second create inside the transaction.
        client.users.create { name = "Existing"; email = "dup@example.com" }.saveOrThrow()

        val result = client.withTransactionOrError { tx ->
            // Create a fresh author that the article will hang off of.
            val author = tx.users.create { name = "Alice"; email = "alice@example.com" }.saveOrError().bind()
            // This second user create trips the unique constraint
            // (dup email) → Err(ConstraintViolation) → bind aborts.
            tx.users.create { name = "Dup"; email = "dup@example.com" }.saveOrError().bind()
            // Unreachable.
            tx.articles.create {
                title = "Never"
                published = true
                authorId = author.id
            }.saveOrError().bind()
        }

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals(EntOperation.CREATE, error.operation)

        // Rollback verified: only the originally-seeded user survives.
        assertEquals(1L, client.users.query().rawCount())
        assertEquals(0L, client.articles.query().rawCount())
    }

    @Test
    fun `propagates non-Abort exceptions from the block`() {
        val client = freshClient()

        // A non-EntResult exception (e.g. a NullPointerException from
        // application code inside the block) is not caught by the
        // helper's AbortEntResultTransaction handler — it escapes
        // upward (and the driver rolls back).
        val ex = assertFailsWith<IllegalStateException> {
            client.withTransactionOrError<Unit> { _ ->
                error("application bug")
            }
        }
        assertEquals("application bug", ex.message)

        // Nothing committed.
        assertEquals(0L, client.users.query().rawCount())
    }

    @Test
    fun `runtime guard catches blocks that return EntResult and rolls back`() {
        val client = freshClient()

        // The block returns EntResult<User> instead of calling
        // `.bind()`. This is the silent-commit bad pattern the
        // runtime guard exists to defend against: the helper rolls
        // back and throws IllegalStateException so the bug is loud
        // at the first run instead of silently letting earlier
        // writes commit.
        val ex = assertFailsWith<IllegalStateException> {
            client.withTransactionOrError { tx ->
                tx.users.create { name = "A"; email = "a@example.com" }.saveOrError()
            }
        }
        assertTrue(ex.message!!.contains("EntResult"), "message should mention EntResult: ${ex.message}")
        assertTrue(ex.message!!.contains("bind"), "message should mention .bind(): ${ex.message}")

        // No commit.
        assertEquals(0L, client.users.query().rawCount())
    }
}
