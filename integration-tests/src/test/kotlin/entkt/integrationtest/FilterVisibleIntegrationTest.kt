package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Note
import entkt.integrationtest.ent.NoteLoadPrivacyRule
import entkt.integrationtest.ent.NotePolicyScope
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.PostLoadPrivacyRule
import entkt.integrationtest.ent.PostPolicyScope
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.ent.TagLoadPrivacyContext
import entkt.integrationtest.ent.TagLoadPrivacyRule
import entkt.integrationtest.ent.TagPolicyScope
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
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
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                notes(openNotes())
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run {
                        privacy { load(UserLoadPrivacyRule { PrivacyDecision.Deny("author hidden") }) }
                    }
                })
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "H"; email = "h@example.com" }
                .saveAndLoad().getOrThrow()
            sys.notes.create { body = "note"; writer = author.id }.save().getOrThrow()
        }

        val notes = client.notes.query { withAuthor().filterVisible() }.all().getOrThrow()

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
            withTags { orderBy(Tag.name.asc()) }.filterVisible()
        }.all().getOrThrow()

        val tags = assertIs<EdgeState.Loaded<List<Tag>>>(loaded.single().edges.tags)
        assertEquals(listOf("b-second"), tags.value.map { it.name })
    }

    @Test
    fun `a shared M2M target is batch checked once and filtered from every parent`() {
        val invocations = mutableListOf<List<String>>()
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<TagLoadPrivacyContext> { contexts ->
                                invocations += contexts.map { it.entity.name }
                                contexts.decide {
                                    if (it.entity.name == "a-denied-shared") {
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
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val postA = sys.posts.create { title = "a-parent" }.saveAndLoad().getOrThrow()
            val postB = sys.posts.create { title = "b-parent" }.saveAndLoad().getOrThrow()
            val visible = sys.tags.create { name = "b-visible-shared" }.saveAndLoad().getOrThrow()
            val denied = sys.tags.create { name = "a-denied-shared" }.saveAndLoad().getOrThrow()
            for (post in listOf(postA, postB)) {
                sys.postTags.create { postId = post.id; tagId = denied.id }.save().getOrThrow()
                sys.postTags.create { postId = post.id; tagId = visible.id }.save().getOrThrow()
            }
        }

        val loaded = client.posts.query {
            orderBy(Post.title.asc())
            withTags { orderBy(Tag.name.asc()) }.filterVisible()
        }.all().getOrThrow()

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
            withTags { orderBy(Tag.name.asc()); limit(1) }.filterVisible()
        }.all().getOrThrow()

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
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run { privacy { load(allowAll) } }
                })
                posts(object : EntityPolicy<Post, PostPolicyScope> {
                    override fun configure(scope: PostPolicyScope) = scope.run {
                        privacy {
                            load(PostLoadPrivacyRule { ctx ->
                                if (ctx.entity.title == "P2-hidden") PrivacyDecision.Deny("post hidden")
                                else PrivacyDecision.Allow
                            })
                        }
                    }
                })
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val p1 = sys.posts.create { title = "P1" }.saveAndLoad().getOrThrow()
            val p2 = sys.posts.create { title = "P2-hidden" }.saveAndLoad().getOrThrow()
            val tag = sys.tags.create { name = "shared" }.saveAndLoad().getOrThrow()
            sys.postTags.create { postId = p1.id; tagId = tag.id }.save().getOrThrow()
            sys.postTags.create { postId = p2.id; tagId = tag.id }.save().getOrThrow()
        }

        // filterVisible() applies to the TAGS edge only; the nested
        // Tag → posts eager load stays strict, so the hidden nested post
        // still fails the whole terminal.
        val result = client.posts.query {
            where(Post.title eq "P1")
            withTags { withPosts() }.filterVisible()
        }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
        assertEquals(listOf("tags", "posts"), origin.path.map { it.edgeName })
    }

    // ---- root denial unaffected ----

    @Test
    fun `filterVisible never changes root LOAD-denial behavior`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                notes(object : EntityPolicy<Note, NotePolicyScope> {
                    override fun configure(scope: NotePolicyScope) = scope.run {
                        privacy { load(NoteLoadPrivacyRule { PrivacyDecision.Deny("note hidden") }) }
                    }
                })
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run { privacy { load(allowAll) } }
                })
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "A"; email = "a@example.com" }
                .saveAndLoad().getOrThrow()
            sys.notes.create { body = "note"; writer = author.id }.save().getOrThrow()
        }

        val result = client.notes.query { withAuthor().filterVisible() }.all()

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
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy { load(TagLoadPrivacyRule { throw boom }) }
                    }
                })
            }
        }
        val post = seedPostWithTwoTags(client)

        val result = client.posts.query {
            where(Post.id eq post.id)
            withTags().filterVisible()
        }.all()

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
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy {
                            load(TagLoadPrivacyRule { ctx ->
                                evaluated.add(ctx.entity.name)
                                if (ctx.entity.name == hidden) PrivacyDecision.Deny("tag hidden")
                                else PrivacyDecision.Allow
                            })
                        }
                    }
                })
            }
        }
    }

    private fun seedPostWithTwoTags(client: EntClient): Post =
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val post = sys.posts.create { title = "P" }.saveAndLoad().getOrThrow()
            val tagA = sys.tags.create { name = "a-first" }.saveAndLoad().getOrThrow()
            val tagB = sys.tags.create { name = "b-second" }.saveAndLoad().getOrThrow()
            sys.postTags.create { postId = post.id; tagId = tagA.id }.save().getOrThrow()
            sys.postTags.create { postId = post.id; tagId = tagB.id }.save().getOrThrow()
            post
        }
}
