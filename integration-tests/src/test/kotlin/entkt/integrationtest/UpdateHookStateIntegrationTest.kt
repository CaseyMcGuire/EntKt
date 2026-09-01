package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleBeforeUpdateState
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.mutation.FieldPatch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** End-to-end coverage for immutable update-hook state transformations. */
class UpdateHookStateIntegrationTest : PostgresTestBase() {
    private lateinit var driver: PostgresDriver

    @BeforeTest
    fun setUp() {
        driver = resetAndDriver()
    }

    private fun newClient(
        beforeUpdate: ((ArticleBeforeUpdateState) -> ArticleBeforeUpdateState)? = null,
    ): EntClient = EntClient(driver) {
        if (beforeUpdate != null) {
            hooks { articles { beforeUpdate(beforeUpdate) } }
        }
    }

    private fun seedArticle(client: EntClient): Pair<User, Article> {
        val author = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()
        val article = client.articles.create {
            title = "Original"
            published = false
            authorId = author.id
        }.saveAndLoad(testViewerContext).getOrThrow()
        return author to article
    }

    @Test
    fun `hook state exposes explicit field patches and the loaded row`() {
        var captured: ArticleBeforeUpdateState? = null
        val client = newClient { state -> state.also { captured = it } }
        val (_, article) = seedArticle(client)

        client.articles.update(article.id) {
            title = "Updated"
            notes = "a draft note"
        }.save(testViewerContext).getOrThrow()

        val state = captured ?: error("hook did not run")
        assertEquals(FieldPatch.Set<String?>("Updated"), state.title)
        assertEquals(FieldPatch.Set<String?>("a draft note"), state.notes)
        assertEquals(FieldPatch.Unset, state.published)
        assertEquals("Original", state.before.title)
        assertEquals(false, state.before.published)
    }

    @Test
    fun `hook state distinguishes explicit nullable clear from unset`() {
        var captured: ArticleBeforeUpdateState? = null
        val client = newClient { state -> state.also { captured = it } }
        val (_, article) = seedArticle(client)
        val seeded = client.articles.update(article.id) {
            notes = "initial"
        }.saveAndLoad(testViewerContext).getOrThrow()

        client.articles.update(seeded.id) { notes = null }.save(testViewerContext).getOrThrow()

        assertEquals(FieldPatch.Set<String?>(null), captured?.notes)
        assertEquals(FieldPatch.Unset, captured?.published)
    }

    @Test
    fun `returned state can add a field assignment`() {
        val client = newClient { state -> state.setPublished(true) }
        val (_, article) = seedArticle(client)

        val updated = client.articles.update(article.id) {
            title = "Hook touched too"
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals("Hook touched too", updated.title)
        assertEquals(true, updated.published)
    }

    @Test
    fun `returned state can remove one field assignment`() {
        val client = newClient { state -> state.unsetPublished() }
        val (_, article) = seedArticle(client)

        val updated = client.articles.update(article.id) {
            title = "Title sticks"
            published = true
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals("Title sticks", updated.title)
        assertEquals(false, updated.published)
    }

    @Test
    fun `removing every assignment produces a no-op update`() {
        val client = newClient { state -> state.unsetTitle() }
        val (_, article) = seedArticle(client)

        val current = client.articles.update(article.id) {
            title = "About to be unset"
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals("Original", current.title)
        assertEquals(
            "Original",
            client.articles.findById(testViewerContext, article.id).getOrThrow()!!.title,
        )
    }

    @Test
    fun `hook can repair an invalid required-field null`() {
        val client = newClient { state ->
            val title = state.title as? FieldPatch.Set<String?>
            if (title?.value == null) state.unsetTitle() else state
        }
        val (_, article) = seedArticle(client)

        @Suppress("CAST_NEVER_SUCCEEDS")
        val current = client.articles.update(article.id) {
            title = null as String?
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals("Original", current.title)
    }

    @Test
    fun `one hook receives the state returned by the preceding hook`() {
        val observed = mutableListOf<FieldPatch<String?>>()
        val client = EntClient(driver) {
            hooks {
                articles {
                    beforeUpdate { state -> state.setTitle("first") }
                    beforeUpdate { state ->
                        observed += state.title
                        state.setTitle("second")
                    }
                }
            }
        }
        val (_, article) = seedArticle(client)

        val updated = client.articles.update(article.id) {
            title = "caller"
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals(listOf<FieldPatch<String?>>(FieldPatch.Set("first")), observed)
        assertEquals("second", updated.title)
    }
}
