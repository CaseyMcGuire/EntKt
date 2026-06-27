package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the boundary-input validation added to query bounds + the
 * client-config overfetch cap. Prior to validation:
 *  - `limit(-1)` / `offset(-1)` propagated to the driver and surfaced
 *    as a SQL syntax error on Postgres — one layer removed from the
 *    caller.
 *  - `visibleOverfetchLimit = 0` made the cap-exhaustion check
 *    `rows.size >= cap` always true, so `visibleAllOrError()` would
 *    return `Err(OverfetchCapExceeded)` for every query — including
 *    queries that returned zero rows.
 *
 * Now: each is `require`-rejected at the setter boundary so the
 * caller sees the bad input immediately.
 */
class QueryBoundsValidationIntegrationTest : PostgresTestBase() {

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
    fun `limit rejects negative values at the boundary`() {
        val client = freshClient()
        val ex = assertFailsWith<IllegalArgumentException> {
            client.articles.query { limit(-1) }
        }
        assertTrue(
            ex.message!!.contains("limit must be non-negative"),
            "message should explain the constraint: ${ex.message}",
        )
    }

    @Test
    fun `limit allows zero (caller-explicit empty result)`() {
        val client = freshClient()
        // zero is meaningful — "I don't want any rows, just exercise the path."
        client.articles.query { limit(0) }
    }

    @Test
    fun `offset rejects negative values at the boundary`() {
        val client = freshClient()
        val ex = assertFailsWith<IllegalArgumentException> {
            client.articles.query { offset(-1) }
        }
        assertTrue(
            ex.message!!.contains("offset must be non-negative"),
            "message should explain the constraint: ${ex.message}",
        )
    }

    @Test
    fun `visibleOverfetchLimit rejects zero`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            val driver = resetAndDriver()
            EntClient(driver) {
                visibleOverfetchLimit = 0
            }
        }
        assertTrue(
            ex.message!!.contains("visibleOverfetchLimit must be positive"),
            "message should explain the constraint: ${ex.message}",
        )
    }

    @Test
    fun `visibleOverfetchLimit rejects negative values`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            val driver = resetAndDriver()
            EntClient(driver) {
                visibleOverfetchLimit = -5
            }
        }
        assertTrue(ex.message!!.contains("visibleOverfetchLimit must be positive"))
    }
}
