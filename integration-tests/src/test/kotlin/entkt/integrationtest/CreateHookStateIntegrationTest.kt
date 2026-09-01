package entkt.integrationtest

import entkt.integrationtest.ent.ArticleBeforeCreateState
import entkt.integrationtest.ent.ArticleBeforeSaveState
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.ReminderBeforeCreateState
import entkt.integrationtest.ent.ReminderBeforeSaveState
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.mutation.FieldPatch
import entkt.runtime.mutation.orElse
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** End-to-end coverage for immutable create-hook state transformations. */
class CreateHookStateIntegrationTest : PostgresTestBase() {
    private lateinit var driver: PostgresDriver

    @BeforeTest
    fun setUp() {
        driver = resetAndDriver()
    }

    private fun newClient(
        beforeSave: ((ArticleBeforeSaveState) -> ArticleBeforeSaveState)? = null,
        beforeCreate: ((ArticleBeforeCreateState) -> ArticleBeforeCreateState)? = null,
    ): EntClient = EntClient(driver) {
        hooks {
            articles {
                if (beforeSave != null) beforeSave(beforeSave)
                if (beforeCreate != null) beforeCreate(beforeCreate)
            }
        }
    }

    private fun seedAuthor(client: EntClient): User =
        client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()

    @Test
    fun `beforeSave returns replacement state without changing its input`() {
        lateinit var received: ArticleBeforeSaveState
        val client = newClient(beforeSave = { state ->
            received = state
            state.setTitle("edited")
        })
        val author = seedAuthor(client)

        val saved = client.articles.create {
            title = "draft"
            published = true
            authorId = author.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals("draft", received.title.orElse(null))
        assertEquals("edited", saved.title)
    }

    @Test
    fun `beforeCreate receives the state returned by beforeSave`() {
        val seen = mutableListOf<String?>()
        val client = newClient(
            beforeSave = { state -> state.setTitle("${state.title.orElse(null)}-save") },
            beforeCreate = { state ->
                seen += state.title.orElse(null)
                state.setTitle("${state.title.orElse(null)}-create")
            },
        )
        val author = seedAuthor(client)

        val saved = client.articles.create {
            title = "draft"
            published = true
            authorId = author.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals(listOf<String?>("draft-save"), seen)
        assertEquals("draft-save-create", saved.title)
    }

    @Test
    fun `beforeCreate carries the authoritative viewer context`() {
        var observed: ArticleBeforeCreateState? = null
        val client = newClient(beforeCreate = { state ->
            observed = state
            state
        })
        val author = seedAuthor(client)

        client.articles.create {
            title = "draft"
            published = true
            authorId = author.id
        }.save(testViewerContext).getOrThrow()

        assertSame(testViewerContext, observed?.viewerContext)
    }

    @Test
    fun `beforeSave can read and replace a required FK`() {
        var observed: Long? = null
        val client = newClient(beforeSave = { state ->
            observed = state.authorId.orElse(null)
            state.setAuthorId(9999L)
        })
        val original = seedAuthor(client)
        driver.insert(
            "users",
            mapOf("id" to 9999L, "name" to "Sentinel", "email" to "sentinel@example.com"),
        )

        val saved = client.articles.create {
            title = "draft"
            published = true
            authorId = original.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals(original.id, observed)
        assertEquals(9999L, saved.authorId)
    }

    @Test
    fun `beforeCreate can replace a required FK`() {
        val client = newClient(beforeCreate = { state -> state.setAuthorId(9999L) })
        val original = seedAuthor(client)
        driver.insert(
            "users",
            mapOf("id" to 9999L, "name" to "Sentinel", "email" to "sentinel@example.com"),
        )

        val saved = client.articles.create {
            title = "draft"
            published = true
            authorId = original.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals(9999L, saved.authorId)
    }

    @Test
    fun `beforeSave represents an unassigned nullable FK explicitly`() {
        var observed: FieldPatch<Long?>? = null
        val client = EntClient(driver) {
            hooks {
                reminders {
                    beforeSave { state: ReminderBeforeSaveState ->
                        observed = state.assigneeId
                        state
                    }
                }
            }
        }

        client.reminders.create { body = "no assignee" }.save(testViewerContext).getOrThrow()

        assertIs<FieldPatch.Unset>(observed)
    }

    @Test
    fun `beforeCreate can explicitly clear a nullable FK`() {
        var observed: Long? = null
        val client = EntClient(driver) {
            hooks {
                reminders {
                    beforeCreate { state: ReminderBeforeCreateState ->
                        observed = state.assigneeId.orElse(null)
                        state.setAssigneeId(null)
                    }
                }
            }
        }
        val user = client.users.create {
            name = "U"
            email = "u@example.com"
        }.saveAndLoad(testViewerContext).getOrThrow()

        val saved = client.reminders.create {
            body = "x"
            assigneeId = user.id
        }.saveAndLoad(testViewerContext).getOrThrow()

        assertEquals(user.id, observed)
        assertNull(saved.assigneeId)
    }
}
