package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.EntResult
import entkt.runtime.EntWriteSucceededLoadDeniedException
import entkt.runtime.EntityPolicy
import entkt.runtime.InMemoryDriver
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for `EntError.WriteSucceededLoadDenied` — the
 * "write happened, you just can't see it" variant added so that
 * `saveOrError`'s `Err` shape cleanly distinguishes:
 *
 *  - `Err(PrivacyDenied(CREATE/UPDATE))` — the write was rejected
 *    up-front and did NOT happen
 *  - `Err(WriteSucceededLoadDenied(CREATE/UPDATE))` — the write
 *    DID happen, but the post-write LOAD privacy check denied the
 *    viewer; the row is in the DB
 *
 * Without the variant, both cases would surface as `Err(PrivacyDenied)`
 * and a caller treating `Err` as "operation didn't happen" would be
 * wrong for the second case.
 *
 * The helper does not roll back the surrounding tx (same caveat as
 * `createManyOrError` per-row Err) — composing with
 * `withTransactionOrError + bind()` is the all-or-nothing path.
 */
class WriteSucceededLoadDeniedIntegrationTest {

    /** Article LOAD denies for the active viewer; CREATE/UPDATE allow. */
    private object DenyLoadAllowWrite : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Deny("can't read") }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(viewer: Viewer = Viewer.User(1L)): EntClient {
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(DenyLoadAllowWrite)
                users(OpenUser)
            }
        }
    }

    private fun seedAuthor(client: EntClient): User {
        return client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        }
    }

    // ---- CREATE path ----

    @Test
    fun `create saveOrError returns Err(WriteSucceededLoadDenied) when post-write LOAD denies`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val result = client.articles.create {
            title = "secret"
            published = true
            authorId = author.id
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(
            error is EntError.WriteSucceededLoadDenied,
            "expected WriteSucceededLoadDenied; got $error",
        )
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.CREATE, error.operation)
        assertNotNull(error.id, "id should be the newly-written article's id")
        assertEquals("can't read", error.reason)
    }

    @Test
    fun `create saveOrError Err(WriteSucceededLoadDenied) leaves the row in the database`() {
        // The whole point of the distinct variant is to signal that
        // the write actually committed even though Err was returned.
        // Pin that by reading the row back via a System-elevated
        // context that bypasses LOAD privacy.
        val client = freshClient()
        val author = seedAuthor(client)

        val result = client.articles.create {
            title = "ghost"
            published = true
            authorId = author.id
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error as EntError.WriteSucceededLoadDenied

        val stillExists = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.byIdOrNull(error.id as Long)
        }
        assertNotNull(stillExists, "row should be persisted even though saveOrError returned Err")
        assertEquals("ghost", stillExists.title)
    }

    @Test
    fun `create saveOrThrow throws EntWriteSucceededLoadDeniedException when post-write LOAD denies`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val ex = assertFailsWith<EntWriteSucceededLoadDeniedException> {
            client.articles.create {
                title = "boom"
                published = true
                authorId = author.id
            }.saveOrThrow()
        }
        assertEquals("Article", ex.writeSucceededLoadDenied.entity)
        assertEquals(EntOperation.CREATE, ex.writeSucceededLoadDenied.operation)
        assertNotNull(ex.writeSucceededLoadDenied.id)
    }

    // ---- UPDATE path ----

    @Test
    fun `update saveOrError returns Err(WriteSucceededLoadDenied) when post-write LOAD denies`() {
        val client = freshClient()
        val author = seedAuthor(client)
        // Seed via System so the row exists and we have an id to update.
        val articleId = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.create {
                title = "original"
                published = true
                authorId = author.id
            }.saveOrThrow().id
        }

        val result = client.articles.update(articleId) {
            title = "renamed"
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(
            error is EntError.WriteSucceededLoadDenied,
            "expected WriteSucceededLoadDenied; got $error",
        )
        assertEquals(EntOperation.UPDATE, error.operation)
        assertEquals(articleId, error.id)
    }

    @Test
    fun `update Err(WriteSucceededLoadDenied) leaves the new value in the database`() {
        val client = freshClient()
        val author = seedAuthor(client)
        val articleId = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.create {
                title = "original"
                published = true
                authorId = author.id
            }.saveOrThrow().id
        }

        val result = client.articles.update(articleId) {
            title = "renamed"
        }.saveOrError()
        assertTrue(result is EntResult.Err)

        // Verify the write actually committed despite Err.
        val reread = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.byIdOrNull(articleId)
        }
        assertNotNull(reread)
        assertEquals("renamed", reread.title)
    }

    // ---- Distinct from PrivacyDenied(CREATE) ----

    @Test
    fun `pre-write CREATE denial surfaces as Err(PrivacyDenied), not Err(WriteSucceededLoadDenied)`() {
        // CREATE privacy denies → write does NOT happen → Err(PrivacyDenied).
        val denyCreate = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { PrivacyDecision.Allow })
                    create(entkt.integrationtest.ent.ArticleCreatePrivacyRule {
                        PrivacyDecision.Deny("auth required")
                    })
                }
            }
        }
        val driver = InMemoryDriver()
        EntClient.SCHEMAS.forEach(driver::register)
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                articles(denyCreate)
                users(OpenUser)
            }
        }
        val author = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        }

        val result = client.articles.create {
            title = "x"
            published = true
            authorId = author.id
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(
            error is EntError.PrivacyDenied,
            "pre-write CREATE denial should be PrivacyDenied, not WriteSucceededLoadDenied; got $error",
        )
        assertEquals(EntOperation.CREATE, error.operation)

        // No row was written (verified to distinguish from the
        // post-write LOAD path which DOES write).
        assertEquals(0L, client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query().rawCount()
        })
    }
}
