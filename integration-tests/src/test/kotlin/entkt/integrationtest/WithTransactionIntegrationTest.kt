package entkt.integrationtest

import entkt.integrationtest.ent.Article
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
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntTransactionFailedException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.getOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the canonical transaction boundary:
 * `withTransaction { tx -> ... }: TransactionResult<T>`.
 *
 * Pins the failure-precedence rules: a mutation failure produced
 * through the transaction client marks the scope rollback-only even
 * when its result is ignored (fail-closed backstop) with the first
 * recorded failure primary and later ones suppressed; a block-thrown
 * application exception is primary with recorded failures suppressed;
 * read failures mark rollback-only only via `orRollback()`; and
 * `getOrThrow()` throws `EntTransactionFailedException` preserving
 * `transactionState` with the stored failure as `exception` and cause.
 */
class WithTransactionIntegrationTest : PostgresTestBase() {

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private val denyLoadArticles = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Deny("hidden") }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            // CREATE is fail-closed too: the User(1L)-viewer tests write
            // users inside the transaction, so both rules must allow.
            privacy {
                load(UserLoadPrivacyRule { PrivacyDecision.Allow })
                create(entkt.integrationtest.ent.UserCreatePrivacyRule { PrivacyDecision.Allow })
            }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAllArticles,
    ): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(OpenUser)
            }
        }
    }

    private fun userCount(client: EntClient): Long =
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.query().rawCount().getOrThrow()
        }

    // ---- Success ----

    @Test
    fun `commits and returns Success with the block value`() {
        val client = freshClient()

        val result = client.withTransaction { tx ->
            val author = tx.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad().orRollback()
            tx.articles.create {
                title = "Hello"
                published = true
                authorId = author.id
            }.saveAndLoad().orRollback()
        }

        val success = assertIs<TransactionResult.Success<Article>>(result)
        assertEquals("Hello", success.value.title)

        // Both rows survived the commit.
        assertEquals(1L, client.users.query().rawCount().getOrThrow())
        assertEquals(1L, client.articles.query().rawCount().getOrThrow())
    }

    // ---- orRollback on mutation results ----

    @Test
    fun `orRollback on a Failed mutation rolls back and surfaces the stored exception`() {
        val client = freshClient()
        client.users.create { name = "Existing"; email = "dup@example.com" }.save().getOrThrow()

        val result = client.withTransaction { tx ->
            tx.users.create { name = "Alice"; email = "alice@example.com" }
                .saveAndLoad().orRollback()
            // Unique-email violation → Failed → orRollback stops the block.
            tx.users.create { name = "Dup"; email = "dup@example.com" }
                .saveAndLoad().orRollback()
            "unreachable"
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)

        // Rollback verified: only the originally-seeded user survives.
        assertEquals(1L, userCount(client))
    }

    // ---- Fail-closed backstop: ignored mutation failures ----

    @Test
    fun `an ignored Failed mutation still rolls back when the block returns normally`() {
        val client = freshClient()

        val result = client.withTransaction { tx ->
            tx.users.create { name = "Kept"; email = "kept@example.com" }
                .saveAndLoad().orRollback()
            // Validation failure (missing email), result ignored.
            tx.users.create { name = "Broken" }.save()
            "block completed normally"
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertIs<EntValidationException>(failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)

        // The earlier successful write did NOT commit.
        assertEquals(0L, userCount(client))
    }

    @Test
    fun `first ignored failure is primary and later distinct failures are suppressed`() {
        val client = freshClient()

        var first: MutationResult<*>? = null
        var second: MutationResult<*>? = null
        val result = client.withTransaction { tx ->
            first = tx.users.create { name = "NoEmail1" }.save()
            second = tx.users.create { name = "NoEmail2" }.save()
            "ignored both"
        }

        val firstEx = assertIs<MutationResult.Failed>(first!!).exception
        val secondEx = assertIs<MutationResult.Failed>(second!!).exception

        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(firstEx, failed.exception)
        assertTrue(
            failed.exception.suppressed.any { it === secondEx },
            "second failure should be suppressed on the first; got ${failed.exception.suppressed.toList()}",
        )
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
    }

    // ---- Block-thrown exception precedence ----

    @Test
    fun `a block-thrown application exception is primary with recorded failures suppressed`() {
        val client = freshClient()

        var recorded: MutationResult<*>? = null
        val appBug = IllegalStateException("application bug")
        val result = client.withTransaction<Unit> { tx ->
            recorded = tx.users.create { name = "NoEmail" }.save()
            throw appBug
        }

        val recordedEx = assertIs<MutationResult.Failed>(recorded!!).exception

        val failed = assertIs<TransactionResult.Failed>(result)
        assertSame(appBug, failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertTrue(
            failed.exception.suppressed.any { it === recordedEx },
            "recorded mutation failure should be suppressed on the block exception",
        )
    }

    // ---- Read failures ----

    @Test
    fun `a read failure does not mark the scope rollback-only unless orRollback'd`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyLoadArticles)
        val article = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad().getOrThrow()
            sys.articles.create { title = "T"; published = true; authorId = author.id }
                .saveAndLoad().getOrThrow()
        }

        val result = client.withTransaction { tx ->
            // Denied read; the caller legitimately tolerates it.
            val read = tx.articles.findById(article.id)
            assertIs<ReadResult.Failed>(read)
            tx.users.create { name = "B"; email = "b@example.com" }.saveAndLoad().orRollback()
            "done"
        }

        assertIs<TransactionResult.Success<String>>(result)
        // The write committed: the tolerated read failure did not poison
        // the transaction.
        assertEquals(2L, userCount(client))
    }

    @Test
    fun `orRollback on a Failed read rolls back the transaction`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyLoadArticles)
        val article = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad().getOrThrow()
            sys.articles.create { title = "T"; published = true; authorId = author.id }
                .saveAndLoad().getOrThrow()
        }

        val result = client.withTransaction { tx ->
            tx.users.create { name = "B"; email = "b@example.com" }.saveAndLoad().orRollback()
            // A REQUIRED read: project through orRollback — the denial
            // stops the block and rolls back the earlier write.
            tx.articles.findById(article.id).orRollback()
            "unreachable"
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
        assertEquals(1L, userCount(client))
    }

    // ---- Outer-client independence ----

    @Test
    fun `mutations through the outer client inside the block are not rolled back`() {
        val client = freshClient()

        val result = client.withTransaction { tx ->
            // Executed on the OUTER client: autocommit, independent of the
            // transaction. Only operations through `tx` participate.
            client.users.create { name = "Outside"; email = "outside@example.com" }
                .save().getOrThrow()
            // Force a rollback of the transaction itself.
            tx.users.create { name = "Broken" }.save().orRollback()
            "unreachable"
        }

        assertIs<TransactionResult.Failed>(result)
        // The outer-client write survives the rollback — documented
        // independent-execution semantics.
        assertEquals(1L, userCount(client))
        assertEquals(
            1L,
            client.users.query { where(User.email eq "outside@example.com") }
                .rawCount().getOrThrow(),
        )
    }

    // ---- getOrThrow ----

    @Test
    fun `getOrThrow returns the committed value on Success`() {
        val client = freshClient()

        val value = client.withTransaction { tx ->
            tx.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().orRollback().name
        }.getOrThrow()

        assertEquals("A", value)
    }

    @Test
    fun `getOrThrow wraps failure in EntTransactionFailedException preserving state and cause`() {
        val client = freshClient()

        val result = client.withTransaction { tx ->
            tx.users.create { name = "Broken" }.save().orRollback()
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntTransactionFailedException) {
            assertEquals(TransactionFailureState.NotCommitted, e.transactionState)
            assertSame(failed.exception, e.exception)
            assertSame(failed.exception, e.cause)
        }
    }

    // ---- Failed does not expose the block value ----

    @Test
    fun `Failed carries only the exception and transaction state`() {
        val client = freshClient()

        val result: TransactionResult<String> = client.withTransaction { tx ->
            tx.users.create { name = "Broken" }.save()
            "the block value"
        }

        val failed = assertIs<TransactionResult.Failed>(result)
        // Structurally: TransactionResult.Failed is TransactionResult<Nothing>;
        // there is no value property to leak the block's return.
        assertFalse(failed.transactionState == TransactionFailureState.OutcomeUnknown)
    }
}
