package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreate
import entkt.integrationtest.ent.ArticleCreateHookContext
import entkt.integrationtest.ent.ArticleCreateMutationView
import entkt.integrationtest.ent.ArticleMutation
import entkt.integrationtest.ent.ArticleUpdateMutationView
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.ReminderCreateHookContext
import entkt.integrationtest.ent.ReminderMutation
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * End-to-end coverage for RFC 08's create-hook adapter pattern
 * (`docs/implemented-features/edge-mutation/08-create-hook-mutation-view-adapter.md`).
 *
 * Pins both the runtime narrowing and the behavioral
 * compatibility:
 *
 *  - `beforeSave` hooks receive a `${Entity}Mutation`-typed
 *    adapter (`_beforeSaveView`) that fails casts back to
 *    `${Entity}Create` AND to `${Entity}CreateMutationView`.
 *  - `beforeCreate` hooks see `ctx.mutation` as a
 *    `${Entity}CreateMutationView`-typed adapter
 *    (`_createMutationView`) that fails casts to
 *    `${Entity}Create` AND to `${Entity}UpdateMutationView`.
 *  - The one allowed widening — `ctx.mutation as ${Entity}Mutation`
 *    — succeeds (the create view's declared supertype).
 *  - Hooks can still read and write field values through
 *    either adapter; writes flow back to the outer
 *    `${Entity}Create` and are persisted by `save()`.
 */
class CreateHookAdapterIntegrationTest : PostgresTestBase() {

    private lateinit var driver: PostgresDriver

    @BeforeTest
    fun setUp() {
        driver = resetAndDriver()
    }

    private fun newClient(
        beforeSave: ((ArticleMutation) -> Unit)? = null,
        beforeCreate: ((ArticleCreateHookContext) -> Unit)? = null,
    ): EntClient = EntClient(driver) {
        hooks {
            articles {
                if (beforeSave != null) beforeSave(beforeSave)
                if (beforeCreate != null) beforeCreate(beforeCreate)
            }
        }
    }

    private fun seedAuthor(client: EntClient): User =
        client.users.create { name = "Alice"; email = "alice@example.com" }.save()

    // ──────────────────────────────────────────────────────────────
    // Cast-failure surface (the 4 disallowed casts + 1 allowed)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `beforeSave arg cannot be cast to ArticleCreate`() {
        var captured: ClassCastException? = null
        val client = newClient(beforeSave = { mutation ->
            try { @Suppress("UNUSED_VARIABLE") val asCreate = mutation as ArticleCreate }
            catch (e: ClassCastException) { captured = e }
        })
        val author = seedAuthor(client)
        client.articles.create { title = "x"; published = true; authorId = author.id }.save()

        assertNotNull(captured, "casting beforeSave arg to ArticleCreate must throw ClassCastException")
    }

    @Test
    fun `beforeSave arg cannot be cast to ArticleCreateMutationView`() {
        // The create view extends Mutation with immutable-scalar /
        // immutable-FK setters; a beforeSave hook must not be able
        // to widen up to it (those members are create-phase only).
        var captured: ClassCastException? = null
        val client = newClient(beforeSave = { mutation ->
            try { @Suppress("UNUSED_VARIABLE") val asView = mutation as ArticleCreateMutationView }
            catch (e: ClassCastException) { captured = e }
        })
        val author = seedAuthor(client)
        client.articles.create { title = "x"; published = true; authorId = author.id }.save()

        assertNotNull(captured, "casting beforeSave arg to ArticleCreateMutationView must throw")
    }

    @Test
    fun `beforeCreate ctx-mutation cannot be cast to ArticleCreate`() {
        var captured: ClassCastException? = null
        val client = newClient(beforeCreate = { ctx ->
            try { @Suppress("UNUSED_VARIABLE") val asCreate = ctx.mutation as ArticleCreate }
            catch (e: ClassCastException) { captured = e }
        })
        val author = seedAuthor(client)
        client.articles.create { title = "x"; published = true; authorId = author.id }.save()

        assertNotNull(captured, "casting ctx.mutation to ArticleCreate must throw ClassCastException")
    }

    @Test
    fun `beforeCreate ctx-mutation cannot be cast to ArticleUpdateMutationView`() {
        // The create-view adapter must not accidentally satisfy
        // the update-side view (which would expose unset{X}()
        // methods that have no meaning on the create path).
        var captured: ClassCastException? = null
        val client = newClient(beforeCreate = { ctx ->
            try { @Suppress("UNUSED_VARIABLE") val asUpdate = ctx.mutation as ArticleUpdateMutationView }
            catch (e: ClassCastException) { captured = e }
        })
        val author = seedAuthor(client)
        client.articles.create { title = "x"; published = true; authorId = author.id }.save()

        assertNotNull(captured, "casting ctx.mutation to ArticleUpdateMutationView must throw")
    }

    @Test
    fun `beforeCreate ctx-mutation cast to ArticleMutation succeeds (allowed widening)`() {
        // ArticleMutation is the create view's declared supertype.
        // This is the one permitted widening — a hook reaching
        // the shared writable surface through the create view
        // is well-defined.
        var widenedOk = false
        val client = newClient(beforeCreate = { ctx ->
            val asMutation: ArticleMutation = ctx.mutation as ArticleMutation
            widenedOk = true
            // Sanity: the widening retains write access.
            asMutation.title = (asMutation.title ?: "") + "!"
        })
        val author = seedAuthor(client)
        val saved = client.articles.create {
            title = "hello"
            published = true
            authorId = author.id
        }.save()

        assertEquals(true, widenedOk, "ctx.mutation as ArticleMutation must succeed")
        assertEquals("hello!", saved.title, "writes through the widened reference must reach the outer builder")
    }

    // ──────────────────────────────────────────────────────────────
    // Behavioral compatibility (reads / writes through each adapter)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `beforeSave hook can read and write field values through the adapter`() {
        val client = newClient(beforeSave = { mutation ->
            // Read: returns whatever the caller staged on the builder.
            val before = mutation.title
            assertEquals("draft", before)
            // Write: flows back to the outer ArticleCreate.
            mutation.title = "edited-by-beforeSave"
        })
        val author = seedAuthor(client)
        val saved = client.articles.create {
            title = "draft"
            published = true
            authorId = author.id
        }.save()

        assertEquals("edited-by-beforeSave", saved.title)
    }

    @Test
    fun `beforeCreate hook can read and write field values through ctx-mutation`() {
        val client = newClient(beforeCreate = { ctx ->
            val before = ctx.mutation.title
            assertEquals("draft", before)
            ctx.mutation.title = "edited-by-beforeCreate"
        })
        val author = seedAuthor(client)
        val saved = client.articles.create {
            title = "draft"
            published = true
            authorId = author.id
        }.save()

        assertEquals("edited-by-beforeCreate", saved.title)
    }

    @Test
    fun `beforeSave and beforeCreate both see the same staged field state`() {
        // Sanity: chaining both hooks on the same save() exercises
        // the full pipeline through both adapters. The beforeSave
        // edit must be visible to beforeCreate (it runs first).
        val client = newClient(
            beforeSave = { mutation -> mutation.title = mutation.title + "-A" },
            beforeCreate = { ctx -> ctx.mutation.title = ctx.mutation.title + "-B" },
        )
        val author = seedAuthor(client)
        val saved = client.articles.create {
            title = "x"
            published = true
            authorId = author.id
        }.save()

        // beforeSave runs first → "x-A". beforeCreate runs next → "x-A-B".
        assertEquals("x-A-B", saved.title)
    }

    // ──────────────────────────────────────────────────────────────
    // FK adapter read/write coverage. Article's `authorId` is a
    // required implicit FK — the Mutation interface declares it
    // as non-null, so the forwarder property's getter is typed
    // non-null. These tests prove the forwarder routes through
    // the outer Create builder's throw-on-unassigned getter for
    // required FKs, and that writes through the adapter flow
    // back to the outer builder's setter (which validates the
    // FK before persistence).
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `beforeSave can read a required FK through the adapter`() {
        val seen = mutableListOf<Long>()
        val client = newClient(beforeSave = { mutation ->
            // Adapter forwarder type matches the Mutation
            // interface: required FK is non-null.
            val current: Long = mutation.authorId
            seen.add(current)
        })
        val author = seedAuthor(client)
        client.articles.create {
            title = "x"
            published = true
            authorId = author.id
        }.save()

        assertEquals(listOf(author.id), seen)
    }

    @Test
    fun `beforeSave can rewrite a required FK through the adapter`() {
        val client = EntClient(driver) {
            hooks {
                articles {
                    beforeSave { mutation ->
                        // Force every save to point at a sentinel
                        // author so we can pin the post-hook state.
                        mutation.authorId = 9999L
                    }
                }
            }
        }
        // Seed two real authors — Alice is who the caller picks;
        // Sentinel is the rewrite target.
        val alice = client.users.create { name = "Alice"; email = "alice@example.com" }.save()
        // Insert the sentinel directly so the FK target exists.
        driver.insert("users", mapOf("id" to 9999L, "name" to "Sentinel", "email" to "sentinel@example.com"))

        val saved = client.articles.create {
            title = "rewritten"
            published = true
            authorId = alice.id
        }.save()

        assertEquals(9999L, saved.authorId, "beforeSave's adapter write should reach the persisted row")
    }

    @Test
    fun `beforeCreate can read a required FK through ctx-mutation`() {
        val seen = mutableListOf<Long>()
        val client = newClient(beforeCreate = { ctx ->
            val current: Long = ctx.mutation.authorId
            seen.add(current)
        })
        val author = seedAuthor(client)
        client.articles.create {
            title = "x"
            published = true
            authorId = author.id
        }.save()

        assertEquals(listOf(author.id), seen)
    }

    @Test
    fun `beforeCreate can rewrite a required FK through ctx-mutation`() {
        val client = EntClient(driver) {
            hooks {
                articles {
                    beforeCreate { ctx ->
                        ctx.mutation.authorId = 9999L
                    }
                }
            }
        }
        val alice = client.users.create { name = "Alice"; email = "alice@example.com" }.save()
        driver.insert("users", mapOf("id" to 9999L, "name" to "Sentinel", "email" to "sentinel@example.com"))

        val saved = client.articles.create {
            title = "rewritten-by-create"
            published = true
            authorId = alice.id
        }.save()

        assertEquals(9999L, saved.authorId)
    }

    // ──────────────────────────────────────────────────────────────
    // Nullable FK adapter coverage. Reminder.assignee is
    // `belongsTo<User>("assignee").nullable()`, so the Mutation
    // interface declares `assigneeId: Long?` and the adapter
    // forwarder is also nullable. Covers all 4 read/write
    // combinations across both adapters: read while null, read
    // after assignment, write a non-null id, write null to clear.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `beforeSave nullable-FK adapter read returns null when unassigned`() {
        var observed: Long? = 1L  // sentinel; overwritten by the hook
        val client = EntClient(driver) {
            hooks {
                reminders {
                    beforeSave { mutation: ReminderMutation ->
                        observed = mutation.assigneeId
                    }
                }
            }
        }
        client.reminders.create { body = "no assignee" }.save()
        assertNull(observed, "unassigned nullable FK should read as null through the adapter")
    }

    @Test
    fun `beforeSave nullable-FK adapter read returns the staged id when set`() {
        var observed: Long? = null
        val client = EntClient(driver) {
            hooks {
                reminders {
                    beforeSave { mutation -> observed = mutation.assigneeId }
                }
            }
        }
        val user = client.users.create { name = "U"; email = "u@example.com" }.save()
        client.reminders.create {
            body = "with assignee"
            assigneeId = user.id
        }.save()

        assertEquals(user.id, observed)
    }

    @Test
    fun `beforeSave can write a non-null id to a nullable FK through the adapter`() {
        val client = EntClient(driver) {
            hooks {
                reminders {
                    beforeSave { mutation -> mutation.assigneeId = 7777L }
                }
            }
        }
        driver.insert("users", mapOf("id" to 7777L, "name" to "Forced", "email" to "forced@example.com"))
        val saved = client.reminders.create { body = "x" }.save()
        assertEquals(7777L, saved.assigneeId)
    }

    @Test
    fun `beforeSave can write null to clear a nullable FK through the adapter`() {
        val client = EntClient(driver) {
            hooks {
                reminders {
                    beforeSave { mutation -> mutation.assigneeId = null }
                }
            }
        }
        val user = client.users.create { name = "U"; email = "u@example.com" }.save()
        // Caller sets assignee to `user.id`, but the hook clears it.
        val saved = client.reminders.create {
            body = "x"
            assigneeId = user.id
        }.save()
        assertNull(saved.assigneeId, "writing null through the adapter should clear the FK")
    }

    @Test
    fun `beforeCreate nullable-FK ctx-mutation reads and rewrites`() {
        // Combined read + write through the create view adapter
        // in a single hook — pins both directions on the
        // `_createMutationView` side, parallel to the per-direction
        // beforeSave coverage above.
        var observed: Long? = null
        val client = EntClient(driver) {
            hooks {
                reminders {
                    beforeCreate { ctx: ReminderCreateHookContext ->
                        observed = ctx.mutation.assigneeId
                        ctx.mutation.assigneeId = null
                    }
                }
            }
        }
        val user = client.users.create { name = "U"; email = "u@example.com" }.save()
        val saved = client.reminders.create {
            body = "x"
            assigneeId = user.id
        }.save()

        assertEquals(user.id, observed, "ctx.mutation.assigneeId should read the caller-staged id")
        assertNull(saved.assigneeId, "writing null through ctx.mutation should clear the FK")
    }
}
