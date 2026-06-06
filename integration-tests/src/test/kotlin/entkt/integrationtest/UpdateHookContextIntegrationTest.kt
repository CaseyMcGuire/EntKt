package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleUpdateHookContext
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.EntNoChangesException
import entkt.runtime.FieldPatch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Compile-and-runtime coverage for the generated `_mutationView`
 * adapter. The codegen-string tests in `UpdateGeneratorTest` pin the
 * shape of the emitted code; this test pins that the emitted code
 * *compiles* against the generated `${Entity}UpdateHookContext` /
 * `${Entity}UpdateMutationView` types and that hooks behave as
 * documented when running against a real driver.
 *
 * Article gives us a required scalar (`title`), a nullable scalar
 * (`notes`), a default-bearing scalar (`published`), and a required
 * FK (`authorId`). That covers four of the five shapes the reviewer
 * called out (nullable FK isn't currently in the test schemas).
 *
 * Runs against Postgres so the generated `_mutationView` adapter is
 * exercised end-to-end through the production driver.
 */
class UpdateHookContextIntegrationTest : PostgresTestBase() {

    private lateinit var driver: PostgresDriver

    @BeforeTest
    fun setUp() {
        driver = resetAndDriver()
    }

    private fun newClient(
        beforeUpdate: ((ArticleUpdateHookContext) -> Unit)? = null,
    ): EntClient = sysClient(driver) {
        if (beforeUpdate != null) {
            hooks {
                articles {
                    beforeUpdate(beforeUpdate)
                }
            }
        }
    }

    private fun seedArticle(client: EntClient): Pair<User, Article> {
        val author = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()
        val article = client.articles.create {
            title = "Original"
            published = false
            authorId = author.id
        }.save()
        return author to article
    }

    @Test
    fun `hook reads ctx_patch FieldPatch entries for set fields`() {
        var captured: ArticleUpdateHookContext? = null
        val client = newClient(beforeUpdate = { ctx -> captured = ctx })
        val (_, article) = seedArticle(client)

        client.articles.update(article.id) {
            title = "Updated"
            notes = "a draft note"
        }.save()

        val ctx = captured ?: error("hook did not run")
        assertEquals(FieldPatch.Set("Updated"), ctx.patch.title)
        assertEquals(FieldPatch.Set<String?>("a draft note"), ctx.patch.notes)
        // Untouched scalars remain Unset in the snapshot.
        assertEquals(FieldPatch.Unset, ctx.patch.published)
        // ctx.before reflects the loaded current row.
        assertEquals("Original", ctx.before.title)
        assertEquals(false, ctx.before.published)
    }

    @Test
    fun `hook reads ctx_patch as Set(null) for an explicit nullable clear`() {
        var captured: ArticleUpdateHookContext? = null
        val client = newClient(beforeUpdate = { ctx -> captured = ctx })
        val (_, article) = seedArticle(client)
        // Seed with a non-null notes value so the clear is observable.
        val seeded = client.articles.update(article.id) {
            notes = "initial"
        }.save()!!

        client.articles.update(seeded.id) {
            notes = null
        }.save()

        val ctx = captured ?: error("hook did not run")
        // FieldPatch<String?> for the nullable scalar carries Set(null)
        // as an explicit clear, distinct from Unset.
        assertEquals(FieldPatch.Set<String?>(null), ctx.patch.notes)
    }

    @Test
    fun `hook writes through ctx_mutation field setters and the value flows to the database`() {
        val client = newClient(beforeUpdate = { ctx ->
            // Hook adds a value the caller didn't set.
            ctx.mutation.published = true
        })
        val (_, article) = seedArticle(client)

        val updated = client.articles.update(article.id) {
            title = "Hook touched too"
        }.save()!!

        assertEquals("Hook touched too", updated.title)
        assertEquals(true, updated.published)  // hook's contribution
    }

    @Test
    fun `hook calls ctx_mutation_unsetField and the field is omitted from the write set`() {
        val client = newClient(beforeUpdate = { ctx ->
            // Hook decides not to touch published after all.
            ctx.mutation.unsetPublished()
        })
        val (_, article) = seedArticle(client)
        // Caller asks for both title and published.
        val updated = client.articles.update(article.id) {
            title = "Title sticks"
            published = true   // unset by the hook
        }.save()!!

        assertEquals("Title sticks", updated.title)
        // Hook unset overrode the caller's published assignment.
        assertEquals(false, updated.published)
    }

    @Test
    fun `hook unsetting all dirty fields produces NoChanges`() {
        val client = newClient(beforeUpdate = { ctx ->
            ctx.mutation.unsetTitle()
        })
        val (_, article) = seedArticle(client)

        assertFailsWith<EntNoChangesException> {
            client.articles.update(article.id) {
                title = "About to be unset"
            }.save()
        }
    }

    @Test
    fun `hook can repair an invalid required-field null via mutation_unsetTitle`() {
        val client = newClient(beforeUpdate = { ctx ->
            // Required field set to null is observable via the
            // mutation getter (the throw-on-untouched gate is on
            // dirtyFields, not on the value). The hook clears the
            // bad assignment so _checkRequiredNotNull doesn't fire.
            if (ctx.mutation.title == null) {
                ctx.mutation.unsetTitle()
            }
        })
        val (_, article) = seedArticle(client)

        // Without the repair this would throw IllegalStateException;
        // with the repair, the only dirty field is gone → NoChanges.
        assertFailsWith<EntNoChangesException> {
            @Suppress("CAST_NEVER_SUCCEEDS")
            client.articles.update(article.id) {
                title = null as String?  // explicitly assign null to a required field
            }.save()
        }
    }

    @Test
    fun `mutation getter exposes the dirty value, throws on untouched`() {
        var captured: ArticleUpdateHookContext? = null
        val client = newClient(beforeUpdate = { ctx -> captured = ctx })
        val (_, article) = seedArticle(client)

        client.articles.update(article.id) {
            title = "set by caller"
        }.save()

        val ctx = captured ?: error("hook did not run")
        // title was assigned → mutation getter returns the staged value.
        assertEquals("set by caller", ctx.mutation.title)
        // notes was not touched → getter throws.
        assertFailsWith<IllegalStateException> { ctx.mutation.notes }
        // published was not touched → getter throws.
        assertFailsWith<IllegalStateException> { ctx.mutation.published }
    }
}

