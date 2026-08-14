package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the "write happened, you just can't see it"
 * contract: `saveAndLoad()`'s post-write LOAD disclosure denial is
 * `Failed(EntMutationPrivacyDeniedException)` with `operation = LOAD`
 * and a `writeState` that tells the truth about persistence:
 *
 *  - autocommit: `Committed` — the row IS in the database
 *  - inside a caller-owned transaction: `TransactionPending`, and the
 *    scope goes rollback-only (the boundary later reports
 *    `NotCommitted` after confirmed rollback)
 *  - allowed no-op update whose LOAD check denies: `NotPersisted` —
 *    nothing was written
 *
 * A pre-write denial uses the mutation's own operation
 * (CREATE/UPDATE) with `NotPersisted`, so the two cases are cleanly
 * distinguishable from the exception alone.
 */
class WriteSucceededLoadDeniedIntegrationTest : PostgresTestBase() {

    /** Article LOAD denies for the active viewer; CREATE/UPDATE explicitly allow. */
    private object DenyLoadAllowWrite : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy {
                load(ArticleLoadPrivacyRule { PrivacyDecision.Deny("can't read") })
                create(allowAll)
                update(allowAll)
            }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(allowAll) }
        }
    }

    private fun freshClient(viewer: Viewer = Viewer.User(1L)): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(DenyLoadAllowWrite)
                users(OpenUser)
            }
        }
    }

    private fun seedAuthor(client: EntClient): User {
        return client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        }
    }

    // ---- CREATE path (autocommit) ----

    @Test
    fun `create saveAndLoad disclosure denial is Failed(Committed, LOAD, keyed)`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val result = client.articles.create {
            title = "secret"
            published = true
            authorId = author.id
        }.saveAndLoad()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.LOAD, ex.operation)
        assertEquals(MutationWriteState.Committed, ex.writeState)
        assertEquals("can't read", ex.reason)
        assertNotNull(ex.entityKey, "the key of the written-but-undisclosed row must be carried")
        assertEquals("id", ex.entityKey!!.field)
    }

    @Test
    fun `create disclosure denial leaves the row committed in the database`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val result = client.articles.create {
            title = "ghost"
            published = true
            authorId = author.id
        }.saveAndLoad()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)

        val stillExists = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.findById(ex.entityKey!!.value as Long).getOrThrow()
        }
        assertNotNull(stillExists, "row should be persisted even though saveAndLoad returned Failed")
        assertEquals("ghost", stillExists.title)
    }

    @Test
    fun `getOrThrow throws the exact stored exception preserving Committed`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val result = client.articles.create {
            title = "boom"
            published = true
            authorId = author.id
        }.saveAndLoad()
        val failed = assertIs<MutationResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntMutationPrivacyDeniedException) {
            assertSame(failed.exception, e)
            assertEquals(MutationWriteState.Committed, e.writeState)
            assertEquals(EntOperation.LOAD, e.operation)
        }
    }

    // ---- UPDATE path (autocommit) ----

    @Test
    fun `update saveAndLoad disclosure denial is Failed(Committed, LOAD) and the write sticks`() {
        val client = freshClient()
        val author = seedAuthor(client)
        val articleId = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.create {
                title = "original"
                published = true
                authorId = author.id
            }.saveAndLoad().getOrThrow().id
        }

        val result = client.articles.update(articleId) {
            title = "renamed"
        }.saveAndLoad()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.LOAD, ex.operation)
        assertEquals(MutationWriteState.Committed, ex.writeState)
        assertEquals(articleId, ex.entityKey?.value)

        // The write actually committed despite Failed.
        val reread = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.findById(articleId).getOrThrow()
        }
        assertEquals("renamed", reread?.title)
    }

    // ---- No-op update disclosure denial: NotPersisted ----

    @Test
    fun `a no-op update whose LOAD check denies reports NotPersisted`() {
        val client = freshClient()
        val author = seedAuthor(client)
        val articleId = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.create {
                title = "untouched"
                published = true
                authorId = author.id
            }.saveAndLoad().getOrThrow().id
        }

        // Assignment-free update: the UPDATE rule allows, nothing is
        // written, then the disclosure check denies. LOAD + NotPersisted
        // is distinguishable from UPDATE + NotPersisted (pre-write
        // rejection) and from LOAD + Committed (real write).
        val result = client.articles.update(articleId) { }.saveAndLoad()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.LOAD, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals(articleId, ex.entityKey?.value)
    }

    // ---- Inside a caller-owned transaction ----

    @Test
    fun `inside withTransaction the disclosure denial is TransactionPending and marks rollback-only`() {
        val client = freshClient()
        val author = seedAuthor(client)

        var inner: MutationResult<Article>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.articles.create {
                title = "pending"
                published = true
                authorId = author.id
            }.saveAndLoad()
            // Ignore the failure: the fail-closed backstop still rolls back.
            "completed"
        }

        val innerFailed = assertIs<MutationResult.Failed>(inner!!)
        val ex = assertIs<EntMutationPrivacyDeniedException>(innerFailed.exception)
        assertEquals(EntOperation.LOAD, ex.operation)
        assertEquals(MutationWriteState.TransactionPending, ex.writeState)

        // Boundary reports the recorded failure after confirmed rollback.
        val txFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(ex, txFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, txFailed.transactionState)

        // Nothing committed.
        val count = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query().rawCount().getOrThrow()
        }
        assertEquals(0L, count)
    }

    // ---- Distinct from a pre-write denial ----

    @Test
    fun `pre-write CREATE denial carries operation CREATE and NotPersisted, and writes nothing`() {
        val denyCreate = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    load(ArticleLoadPrivacyRule { PrivacyDecision.Allow })
                    create(ArticleCreatePrivacyRule { PrivacyDecision.Deny("auth required") })
                }
            }
        }
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                articles(denyCreate)
                users(OpenUser)
            }
        }
        val author = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
        }

        val result = client.articles.create {
            title = "x"
            published = true
            authorId = author.id
        }.saveAndLoad()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        // No row was written — unlike the post-write LOAD path.
        val count = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query().rawCount().getOrThrow()
        }
        assertEquals(0L, count)
        assertTrue(ex.entityKey == null, "pre-write create denial has no usable key; got ${ex.entityKey}")
    }
}
