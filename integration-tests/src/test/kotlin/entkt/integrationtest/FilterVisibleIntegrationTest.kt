package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.ReadOnlyEntClient
import entkt.integrationtest.ent.Note
import entkt.integrationtest.ent.NoteLoadPrivacyRule
import entkt.integrationtest.ent.NotePolicyScope
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.PostLoadPrivacyRule
import entkt.integrationtest.ent.PostPolicyScope
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.ent.TagLoadPrivacyRule
import entkt.integrationtest.ent.TagPolicyScope
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.query.EdgeState
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * `filterVisible()` on an eager-edge handle opts that ONE edge out of
 * strict eager privacy:
 *
 *  - a denied to-one target becomes `EdgeState.Loaded(null)`
 *  - denied to-many targets are omitted from the loaded window with
 *    no replacement scanning
 *  - the opt-out is not inherited by nested eager loads
 *  - root LOAD denial is unaffected
 *  - a rule-thrown ordinary exception still fails the terminal
 */
class FilterVisibleIntegrationTest : PostgresTestBase() {
    private val viewerContext = ViewerContext(Viewer.User(1L))

    private fun openNotes() = object : EntityPolicy<Note, NotePolicyScope> {
        override fun configure(scope: NotePolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    private fun openPosts() = object : EntityPolicy<Post, PostPolicyScope> {
        override fun configure(scope: PostPolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    // ---- to-one ----

    @Test
    fun `a denied to-one target becomes EdgeState Loaded(null)`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                notes(openNotes())
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run {
                        privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("author hidden") }) }
                    }
                })
            }
        }
        run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val author = sys.users.create { name = "H"; email = "h@example.com" }
                .saveAndLoad(viewerContext).getOrThrow()
            sys.notes.create { body = "note"; writer = author.id }.save(viewerContext).getOrThrow()
        }

        val notes = client.notes.query { loadAuthor().filterVisible() }.all(viewerContext).getOrThrow()

        assertEquals(1, notes.size)
        assertEquals(EdgeState.Loaded(null), notes.single().edges.author)
    }

    // ---- to-many: omission without replacement scanning ----

    @Test
    fun `denied to-many targets are omitted while visible ones remain`() {
        val client = postsWithHiddenTag(hidden = "a-first")
        val post = seedPostWithTwoTags(client)

        val loaded = client.posts.query {
            where(Post.id eq post.id)
            loadTags { orderBy(Tag.name.asc()) }.filterVisible()
        }.all(viewerContext).getOrThrow()

        val tags = assertIs<EdgeState.Loaded<List<Tag>>>(loaded.single().edges.tags)
        assertEquals(listOf("b-second"), tags.value.map { it.name })
    }

    @Test
    fun `a shared M2M target is batch checked once and filtered from every parent`() {
        val invocations = mutableListOf<List<String>>()
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<ReadOnlyEntClient, Tag> { _, batch ->
                                invocations += batch.map { it.name }
                                batch.decideEach {
                                    if (it.name == "a-denied-shared") {
                                        PrivacyDecision.Deny("tag hidden")
                                    } else {
                                        PrivacyDecision.Allow
                                    }
                                }
                            })
                        }
                    }
                })
            }
        }
        run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val postA = sys.posts.create { title = "a-parent" }.saveAndLoad(viewerContext).getOrThrow()
            val postB = sys.posts.create { title = "b-parent" }.saveAndLoad(viewerContext).getOrThrow()
            val visible = sys.tags.create { name = "b-visible-shared" }.saveAndLoad(viewerContext).getOrThrow()
            val denied = sys.tags.create { name = "a-denied-shared" }.saveAndLoad(viewerContext).getOrThrow()
            for (post in listOf(postA, postB)) {
                sys.postTags.create { postId = post.id; tagId = denied.id }.save(viewerContext).getOrThrow()
                sys.postTags.create { postId = post.id; tagId = visible.id }.save(viewerContext).getOrThrow()
            }
        }

        val loaded = client.posts.query {
            orderBy(Post.title.asc())
            loadTags { orderBy(Tag.name.asc()) }.filterVisible()
        }.all(viewerContext).getOrThrow()

        assertEquals(listOf(listOf("a-denied-shared", "b-visible-shared")), invocations)
        assertEquals(
            listOf(listOf("b-visible-shared"), listOf("b-visible-shared")),
            loaded.map { post -> post.edges.tags.requireLoaded().map { it.name } },
        )
    }

    @Test
    fun `a denied target inside a limit window is omitted, not replaced`() {
        val evaluated = mutableListOf<String>()
        val client = postsWithHiddenTag(hidden = "a-first", evaluated = evaluated)
        val post = seedPostWithTwoTags(client)

        // Window = the first tag by name, which is the denied one. The
        // visible second tag is OUTSIDE the window and must not be
        // scanned in as a replacement.
        val loaded = client.posts.query {
            where(Post.id eq post.id)
            loadTags { orderBy(Tag.name.asc()); limit(1) }.filterVisible()
        }.all(viewerContext).getOrThrow()

        val tags = assertIs<EdgeState.Loaded<List<Tag>>>(loaded.single().edges.tags)
        assertEquals(emptyList(), tags.value)
        // Privacy ran only on the windowed row.
        assertEquals(listOf("a-first"), evaluated)
    }

    // ---- not inherited by nested eager loads ----

    @Test
    fun `filterVisible is not inherited by nested eager loads`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run { privacy { load(allowAll) } }
                })
                posts(object : EntityPolicy<Post, PostPolicyScope> {
                    override fun configure(scope: PostPolicyScope) = scope.run {
                        privacy {
                            load(PostLoadPrivacyRule { _, item ->
                                if (item.title == "P2-hidden") PrivacyDecision.Deny("post hidden")
                                else PrivacyDecision.Allow
                            })
                        }
                    }
                })
            }
        }
        run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val p1 = sys.posts.create { title = "P1" }.saveAndLoad(viewerContext).getOrThrow()
            val p2 = sys.posts.create { title = "P2-hidden" }.saveAndLoad(viewerContext).getOrThrow()
            val tag = sys.tags.create { name = "shared" }.saveAndLoad(viewerContext).getOrThrow()
            sys.postTags.create { postId = p1.id; tagId = tag.id }.save(viewerContext).getOrThrow()
            sys.postTags.create { postId = p2.id; tagId = tag.id }.save(viewerContext).getOrThrow()
        }

        // filterVisible() applies to the TAGS edge only; the nested
        // Tag → posts eager load stays strict, so the hidden nested post
        // still fails the whole terminal.
        val result = client.posts.query {
            where(Post.title eq "P1")
            loadTags { loadPosts() }.filterVisible()
        }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
        assertEquals(listOf("tags", "posts"), origin.steps.map { it.edgeName })
    }

    // ---- root denial unaffected ----

    @Test
    fun `filterVisible never changes root LOAD-denial behavior`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                notes(object : EntityPolicy<Note, NotePolicyScope> {
                    override fun configure(scope: NotePolicyScope) = scope.run {
                        privacy { load(NoteLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("note hidden") }) }
                    }
                })
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run { privacy { load(allowAll) } }
                })
            }
        }
        run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val author = sys.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad(viewerContext).getOrThrow()
            sys.notes.create { body = "note"; writer = author.id }.save(viewerContext).getOrThrow()
        }

        val result = client.notes.query { loadAuthor().filterVisible() }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(ex.origin)
    }

    // ---- ordinary rule exceptions still fail ----

    @Test
    fun `a rule-thrown exception on a filterVisible edge still fails the terminal`() {
        val boom = IllegalStateException("tag rule blew up")
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy { load(TagLoadPrivacyRule { _, _ -> throw boom }) }
                    }
                })
            }
        }
        val post = seedPostWithTwoTags(client)

        val result = client.posts.query {
            where(Post.id eq post.id)
            loadTags().filterVisible()
        }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        assertSame(boom, failed.exception)
    }

    // ---- helpers ----

    private fun postsWithHiddenTag(
        hidden: String,
        evaluated: MutableList<String> = mutableListOf(),
    ): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {

            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy {
                            load(TagLoadPrivacyRule { _, item ->
                                evaluated.add(item.name)
                                if (item.name == hidden) PrivacyDecision.Deny("tag hidden")
                                else PrivacyDecision.Allow
                            })
                        }
                    }
                })
            }
        }
    }

    private fun seedPostWithTwoTags(client: EntClient): Post =
        run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val post = sys.posts.create { title = "P" }.saveAndLoad(viewerContext).getOrThrow()
            val tagA = sys.tags.create { name = "a-first" }.saveAndLoad(viewerContext).getOrThrow()
            val tagB = sys.tags.create { name = "b-second" }.saveAndLoad(viewerContext).getOrThrow()
            sys.postTags.create { postId = post.id; tagId = tagA.id }.save(viewerContext).getOrThrow()
            sys.postTags.create { postId = post.id; tagId = tagB.id }.save(viewerContext).getOrThrow()
            post
        }
}
