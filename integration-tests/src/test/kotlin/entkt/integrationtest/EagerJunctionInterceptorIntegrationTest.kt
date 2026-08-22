package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Group
import entkt.integrationtest.ent.Membership
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.QueryContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the `EAGER_JUNCTION` discovery pass of the junction
 * read-interceptor RFC
 * (`docs/possible-features/query/junction-read-interceptors.md`):
 * an eager many-to-many step runs the JUNCTION entity's read
 * interceptors before its junction driver read, so predicates
 * registered on the junction (tenant scoping, `ExcludeDeleted`)
 * narrow relationship discovery exactly as they narrow direct
 * junction reads — for `throughEntity` and `throughLink` junctions
 * alike.
 *
 * Contract highlights pinned here: the pass fires exactly once per
 * configured M2M step (even with no parents) while the driver read
 * stays data-gated; a junction rejection stops the step before
 * junction I/O and before the target interceptor pass; the context
 * names the junction as `currentEntity` and the eager M2M step as
 * its path. Junction LOAD privacy deliberately
 * does NOT run on discovery.
 */
class EagerJunctionInterceptorIntegrationTest : PostgresTestBase() {

    private fun seedGroupWithMembers(client: EntClient): Triple<Group, User, User> {
        val g1 = client.groups.create { name = "g1" }.saveAndLoad().getOrThrow()
        val member = client.users.create { name = "member"; email = "m@example.com" }.saveAndLoad().getOrThrow()
        val banned = client.users.create { name = "banned"; email = "b@example.com" }.saveAndLoad().getOrThrow()
        client.memberships.create { groupId = g1.id; userId = member.id; role = "member" }.save().getOrThrow()
        client.memberships.create { groupId = g1.id; userId = banned.id; role = "banned" }.save().getOrThrow()
        return Triple(g1, member, banned)
    }

    @Test
    fun `a junction interceptor narrows eager discovery exactly as it narrows direct junction reads`() {
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                memberships(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("role", Op.EQ, "member"))
                    },
                    name = "members-only",
                )
            }
        }
        val (_, member, _) = seedGroupWithMembers(client)

        val groups = client.groups.query { loadUsers() }.all().getOrThrow()

        // The banned membership row never contributes a relationship —
        // the same rows a direct junction read returns drive discovery.
        assertEquals(
            listOf(member.id),
            groups.single().edges.users.requireLoaded().map { it.id },
        )
        assertEquals(1, client.memberships.query().all().getOrThrow().size)
    }

    @Test
    fun `the junction pass exposes the discovery context and the source-ID structural predicate`() {
        val contexts = mutableListOf<QueryContext>()
        val inValues = mutableListOf<List<Any?>>()
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                memberships(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_JUNCTION) {
                            contexts.add(ctx)
                            inValues.add(
                                (
                                    scope.shape.predicates
                                        .filterIsInstance<Predicate.Leaf<*>>()
                                        .single { it.field == "group_id" && it.op == Op.IN }
                                        .value as List<*>
                                    ).toList(),
                            )
                        }
                    },
                    name = "junction-context-observer",
                )
            }
        }
        val (g1, _, _) = seedGroupWithMembers(client)

        client.groups.query { loadUsers() }.all().getOrThrow()

        // currentEntity is the JUNCTION; source/edge/path describe the
        // eager M2M step being discovered.
        val ctx = contexts.single()
        assertEquals(ReadOperation.EAGER_JUNCTION, ctx.operation)
        assertTrue(ctx.isEagerSubquery)
        assertEquals(Membership::class, ctx.currentEntity)
        assertEquals(Group::class, ctx.sourceEntity)
        assertEquals("users", ctx.edgeName)
        assertEquals(listOf(EdgeStep(Group::class, "users", User::class)), ctx.path)
        assertEquals(Group::class, ctx.rootEntity)
        assertEquals(listOf(listOf<Any?>(g1.id)), inValues)
    }

    @Test
    fun `a nested M2M step's junction pass carries the full path from the root`() {
        val contexts = mutableListOf<QueryContext>()
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                postTags(
                    QueryInterceptor { _, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_JUNCTION) contexts.add(ctx)
                    },
                    name = "junction-context-observer",
                )
            }
        }
        val post = client.posts.create { title = "p" }.saveAndLoad().getOrThrow()
        val tag = client.tags.create { name = "t" }.saveAndLoad().getOrThrow()
        client.postTags.create { postId = post.id; tagId = tag.id }.save().getOrThrow()

        // Post → tags (M2M) → posts (M2M): the SAME junction entity
        // serves both steps, so one interceptor observes both
        // discovery passes and their distinct contexts.
        client.posts.query { loadTags { loadPosts() } }.all().getOrThrow()

        val tagsStep = EdgeStep(entkt.integrationtest.ent.Post::class, "tags", entkt.integrationtest.ent.Tag::class)
        val nestedStep = EdgeStep(entkt.integrationtest.ent.Tag::class, "posts", entkt.integrationtest.ent.Post::class)
        assertEquals(2, contexts.size, "one junction pass per configured M2M step")
        val (top, nested) = contexts
        assertEquals(listOf(tagsStep), top.path)
        assertEquals("tags", top.edgeName)
        // The nested step's junction pass sees the complete
        // root-to-target path — parent scoping/restore must not have
        // clipped it — and the chain's true root.
        assertEquals(listOf(tagsStep, nestedStep), nested.path)
        assertEquals("posts", nested.edgeName)
        assertEquals(entkt.integrationtest.ent.Tag::class, nested.sourceEntity)
        assertEquals(entkt.integrationtest.ent.Post::class, nested.rootEntity)
    }

    @Test
    fun `a junction rejection stops the step before junction IO and the target pass`() {
        val recording = RecordingDriver(resetAndDriver())
        var targetPassFires = 0
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                memberships(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_JUNCTION) {
                            scope.reject("no discovery", code = "junction_rej")
                        }
                    },
                    name = "junction-rejector",
                )
                users(
                    QueryInterceptor { _, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) targetPassFires++
                    },
                    name = "target-pass-counter",
                )
            }
        }
        seedGroupWithMembers(client)
        recording.reset()

        val result = client.groups.query { loadUsers() }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("junction_rej", ex.code)
        assertEquals("Membership", ex.entityType)
        assertEquals(0, targetPassFires, "the target interceptor pass must not run after a junction rejection")
        assertEquals(listOf("query:groups"), recording.calls, "no junction I/O after the rejection")
    }

    @Test
    fun `the junction pass runs exactly once with an empty predicate when no parents exist`() {
        val recording = RecordingDriver(resetAndDriver())
        val inValues = mutableListOf<List<Any?>>()
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                memberships(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_JUNCTION) {
                            inValues.add(
                                (
                                    scope.shape.predicates
                                        .filterIsInstance<Predicate.Leaf<*>>()
                                        .single { it.field == "group_id" && it.op == Op.IN }
                                        .value as List<*>
                                    ).toList(),
                            )
                        }
                    },
                    name = "junction-pass-observer",
                )
            }
        }
        client.groups.create { name = "g1" }.save().getOrThrow()
        recording.reset()

        client.groups.query {
            where(Group.name eq "nobody")
            loadUsers()
        }.all().getOrThrow()

        // Whether the pass runs must not depend on the parent data;
        // whether the driver is consulted must.
        assertEquals(listOf(emptyList<Any?>()), inValues)
        assertEquals(listOf("query:groups"), recording.calls)
    }

    @Test
    fun `a throughLink junction's interceptors apply the same way`() {
        val seeder = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        val post = seeder.posts.create { title = "p" }.saveAndLoad().getOrThrow()
        val keep = seeder.tags.create { name = "keep" }.saveAndLoad().getOrThrow()
        val drop = seeder.tags.create { name = "drop" }.saveAndLoad().getOrThrow()
        seeder.postTags.create { postId = post.id; tagId = keep.id }.save().getOrThrow()
        seeder.postTags.create { postId = post.id; tagId = drop.id }.save().getOrThrow()
        // A link junction is payload-free, but its interceptor slot is
        // just as real: predicates registered on it narrow discovery.
        val narrowing = EntClient(newDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                postTags(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("tag_id", Op.EQ, keep.id))
                    },
                    name = "keep-only",
                )
            }
        }

        val posts = narrowing.posts.query { loadTags() }.all().getOrThrow()

        assertEquals(
            listOf("keep"),
            posts.single().edges.tags.requireLoaded().map { it.name },
        )
    }

}
