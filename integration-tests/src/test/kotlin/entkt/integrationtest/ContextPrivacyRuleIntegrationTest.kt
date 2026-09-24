package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.PostPolicyScope
import entkt.integrationtest.ent.ReadOnlyEntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.privacy.ContextPrivacyRule
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContextPrivacyRuleIntegrationTest : PostgresTestBase() {
    private val viewer = ViewerContext(Viewer.User(7L))

    @Test
    fun `one context rule handles CRUD across entities including an owned transaction`() {
        val seen = mutableListOf<PrivacyRuleContext<ReadOnlyEntClient>>()
        val shared = ContextPrivacyRule<ReadOnlyEntClient> { context ->
            seen += context
            PrivacyDecision.Allow
        }
        val client = client(resetAndDriver(), shared)

        val user = client.users.create { name = "Before"; email = "user@example.com" }.saveAndLoad(viewer).getOrThrow()
        client.users.update(user.id) { name = "After" }.save(viewer).getOrThrow()
        assertEquals("After", client.users.findById(viewer, user.id).getOrThrow()?.name)
        client.users.deleteById(viewer, user.id).getOrThrow()

        assertEquals(5, seen.size, "CREATE, returned LOAD, UPDATE, explicit LOAD, DELETE")
        seen.forEach { context ->
            assertSame(viewer, context.viewerContext)
            assertSame(client.readOnlyClient, context.client)
        }

        seen.clear()
        val posts = client.posts.createMany(viewer, { title = "First" }, { title = "Second" }).getOrThrow()
        assertEquals(4, seen.size, "two CREATE items and two returned LOAD items")
        seen.forEach { assertSame(viewer, it.viewerContext) }
        val transactionClient = seen[0].client
        assertTrue(transactionClient !== client.readOnlyClient, "owned execution must supply its transaction's read client")
        seen.forEach { assertSame(transactionClient, it.client) }
        assertSame(seen[0], seen[1], "items share their CREATE phase context")
        assertSame(seen[2], seen[3], "items share their LOAD phase context")
        client.posts.update(posts[0].id) { title = "Updated" }.save(viewer).getOrThrow()
        assertEquals("Updated", client.posts.findById(viewer, posts[0].id).getOrThrow()?.title)
        assertEquals(2, client.posts.deleteMany(viewer).getOrThrow())
        assertEquals(8, seen.size, "UPDATE, LOAD and both DELETE items also use the shared rule")
    }

    @Test
    fun `context rules deny all four operations while explicit bypass skips them`() {
        var calls = 0
        val denied = ContextPrivacyRule<ReadOnlyEntClient> {
            calls++
            PrivacyDecision.Deny("context denied")
        }
        val client = client(resetAndDriver(), denied)
        val bypass = testBypassContext("seed a row for context-rule denials")
        val user = client.users.create { name = "Seed"; email = "seed@example.com" }.saveAndLoad(bypass).getOrThrow()
        assertEquals(0, calls)

        val read = assertIs<ReadResult.Failed>(client.users.findById(viewer, user.id))
        assertEquals("context denied", assertIs<EntPrivacyDeniedException>(read.exception).denials.single().reason)
        val mutations = listOf(
            client.users.create { name = "Denied"; email = "denied@example.com" }.save(viewer),
            client.users.update(user.id) { name = "Denied" }.save(viewer),
            client.users.deleteById(viewer, user.id),
        )
        for (result in mutations) {
            val failure = assertIs<EntMutationPrivacyDeniedException>(assertIs<MutationResult.Failed>(result).exception)
            assertEquals("context denied", failure.reason)
            assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        }
        assertEquals(4, calls)
        assertEquals(listOf("Seed"), client.users.query().all(bypass).getOrThrow().map { it.name })
        assertEquals(4, calls, "bypassed reads must not invoke the context rule")
    }

    private fun client(driver: DatabaseDriver, shared: ContextPrivacyRule<ReadOnlyEntClient>): EntClient = EntClient(driver) {
        policies {
            users(object : EntityPolicy<User, UserPolicyScope> {
                override fun configure(scope: UserPolicyScope) = scope.run {
                    privacy {
                        load(shared)
                        create(shared)
                        update(shared)
                        delete(shared)
                    }
                }
            })
            posts(object : EntityPolicy<Post, PostPolicyScope> {
                override fun configure(scope: PostPolicyScope) = scope.run {
                    privacy {
                        load(shared)
                        create(shared)
                        update(shared)
                        delete(shared)
                    }
                }
            })
        }
    }
}
