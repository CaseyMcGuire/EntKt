package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * End-to-end coverage of the generated `bypassPrivacy_DANGEROUS(reason) { ... }`
 * helper. Reuses [ArticlePolicy] (drafts are LOAD-denied to non-authors; CREATE
 * requires auth) so the bypass has something real to skip. The viewer is
 * [Viewer.Anonymous] throughout, so privacy denies these operations unless the
 * block elevates: a denied create is
 * `MutationResult.Failed(EntMutationPrivacyDeniedException)` and a denied load
 * is `ReadResult.Failed(EntPrivacyDeniedException)` — never a throw.
 */
class PrivacyBypassHelperIntegrationTest : PostgresTestBase() {

    private fun anonClient(driver: PostgresDriver = resetAndDriver()): EntClient =
        EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies { articles(ArticlePolicy); users(UserPolicy) }
        }

    @Test
    fun `bypassPrivacy_DANGEROUS scopes the bypass to the block`() {
        val client = anonClient()

        // Seed a user + an unpublished article via the bypass — as Anonymous,
        // CREATE is fail-closed denied for both, so the block is the only way in.
        val (userId, articleId) = client.bypassPrivacy_DANGEROUS("seed draft") { b ->
            val uid = b.users.create { name = "u"; email = "u@x.com" }.saveAndLoad().getOrThrow().id
            val aid = b.articles.create { title = "draft"; published = false; authorId = uid }
                .saveAndLoad().getOrThrow().id
            uid to aid
        }

        // Outside the block, Anonymous CREATE of an article is denied...
        val createFailed = assertIs<MutationResult.Failed>(
            client.articles.create { title = "x"; published = false; authorId = userId }.save(),
        )
        assertIs<EntMutationPrivacyDeniedException>(createFailed.exception)
        // ...and Anonymous LOAD of the unpublished article is denied...
        val loadFailed = assertIs<ReadResult.Failed>(client.articles.findById(articleId))
        assertIs<EntPrivacyDeniedException>(loadFailed.exception)
        // ...but LOAD succeeds inside a bypass block.
        val loaded = client.bypassPrivacy_DANGEROUS("read draft") {
            it.articles.findById(articleId).getOrThrow()
        }
        assertEquals("draft", loaded?.title)
    }

    @Test
    fun `bypassPrivacy_DANGEROUS rejects a blank reason before running the block`() {
        val client = anonClient()
        var ran = false
        assertFailsWith<IllegalArgumentException> {
            client.bypassPrivacy_DANGEROUS("") { ran = true; it }
        }
        assertFalse(ran, "the block must not run when the reason is blank")
    }

    @Test
    fun `bypassPrivacy_DANGEROUS preserves hooks and other client config`() {
        val created = mutableListOf<String>()
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
            policies { articles(ArticlePolicy); users(UserPolicy) }
            hooks { articles { afterCreate { created.add(it.title) } } }
        }
        // The bypass scopes a derived client; its copied config (here, the
        // afterCreate hook) must still fire — bypass skips privacy, not hooks.
        client.bypassPrivacy_DANGEROUS("seed") { b ->
            val uid = b.users.create { name = "u"; email = "u@x.com" }.saveAndLoad().getOrThrow().id
            b.articles.create { title = "hooked"; published = false; authorId = uid }.save().getOrThrow()
        }
        assertEquals(listOf("hooked"), created)
    }
}
