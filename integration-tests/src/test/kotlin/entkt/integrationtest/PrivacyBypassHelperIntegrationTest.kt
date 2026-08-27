package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * End-to-end coverage of [ViewerContext.privacyBypass_DANGEROUS]. Reuses
 * [ArticlePolicy] (drafts are LOAD-denied to non-authors; CREATE requires auth)
 * so the bypass has something real to skip. The same long-lived client executes
 * anonymous and bypassed operations independently: a denied create is
 * `MutationResult.Failed(EntMutationPrivacyDeniedException)` and a denied load
 * is `ReadResult.Failed(EntPrivacyDeniedException)` — never a throw.
 */
class PrivacyBypassHelperIntegrationTest : PostgresTestBase() {

    private fun anonClient(driver: PostgresDriver = resetAndDriver()): EntClient =
        EntClient(driver) {

            policies { articles(ArticlePolicy); users(UserPolicy) }
        }

    @Test
    fun `privacy bypass is explicit per operation on one client`() {
        val client = anonClient()
        val anonymous = ViewerContext(Viewer.Anonymous)
        val bypass = ViewerContext.privacyBypass_DANGEROUS("seed draft")

        val userId = client.users.create { name = "u"; email = "u@x.com" }
            .saveAndLoad(bypass).getOrThrow().id
        val articleId = client.articles.create {
            title = "draft"; published = false; authorId = userId
        }.saveAndLoad(bypass).getOrThrow().id

        // Outside the block, Anonymous CREATE of an article is denied...
        val createFailed = assertIs<MutationResult.Failed>(
            client.articles.create { title = "x"; published = false; authorId = userId }.save(anonymous),
        )
        assertIs<EntMutationPrivacyDeniedException>(createFailed.exception)
        // ...and Anonymous LOAD of the unpublished article is denied...
        val loadFailed = assertIs<ReadResult.Failed>(client.articles.findById(anonymous, articleId))
        assertIs<EntPrivacyDeniedException>(loadFailed.exception)
        // ...but the same client succeeds when that terminal receives a bypass context.
        val loaded = client.articles.findById(
            ViewerContext.privacyBypass_DANGEROUS("read draft"),
            articleId,
        ).getOrThrow()
        assertEquals("draft", loaded?.title)
    }

    @Test
    fun `privacyBypass_DANGEROUS rejects a blank reason`() {
        assertFailsWith<IllegalArgumentException> {
            ViewerContext.privacyBypass_DANGEROUS("")
        }
    }

    @Test
    fun `explicit privacy bypass preserves hooks and other client config`() {
        val created = mutableListOf<String>()
        val client = EntClient(resetAndDriver()) {

            policies { articles(ArticlePolicy); users(UserPolicy) }
            hooks { articles { afterCreate { created.add(it.title) } } }
        }
        val bypass = ViewerContext.privacyBypass_DANGEROUS("seed")
        val uid = client.users.create { name = "u"; email = "u@x.com" }
            .saveAndLoad(bypass).getOrThrow().id
        client.articles.create { title = "hooked"; published = false; authorId = uid }
            .save(bypass).getOrThrow()
        assertEquals(listOf("hooked"), created)
    }
}
