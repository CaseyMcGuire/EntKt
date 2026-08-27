package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.ReadOnlyEntClient
import entkt.integrationtest.ent.Group
import entkt.integrationtest.ent.GroupLoadPrivacyRule
import entkt.integrationtest.ent.GroupPolicyScope
import entkt.integrationtest.ent.Note
import entkt.integrationtest.ent.NoteLoadPrivacyRule
import entkt.integrationtest.ent.NotePolicyScope
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.PostLoadPrivacyRule
import entkt.integrationtest.ent.PostPolicyScope
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.ent.TagLoadPrivacyItem
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
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.SelectedEdgeStep
import entkt.runtime.result.ReadResult
import entkt.runtime.result.visibleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Strict eager-edge privacy: a LOAD-denied eager target makes the
 * ENTIRE root terminal `Failed(EntPrivacyDeniedException(SelectedEdgePath(steps),
 * exactly one keyed denial))` — no partial graph, no silent omission.
 * The path names every traversed source type, edge name, and target
 * type (schema names only — no hydrated data). Targets within an edge
 * are evaluated as a batch; across eager edges, a failing edge prevents
 * later eager-edge work in declaration order. `visibleOrNull()` never
 * maps an eager denial to root absence.
 */
class EagerEdgePrivacyIntegrationTest : PostgresTestBase() {
    private val viewerContext = ViewerContext(Viewer.User(1L))

    // ---- fixture policies ----

    private fun openNotes() = object : EntityPolicy<Note, NotePolicyScope> {
        override fun configure(scope: NotePolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    private fun openUsers() = object : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    private fun openPosts() = object : EntityPolicy<Post, PostPolicyScope> {
        override fun configure(scope: PostPolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    private fun openTags() = object : EntityPolicy<Tag, TagPolicyScope> {
        override fun configure(scope: TagPolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    // ---- to-one eager denial ----

    @Test
    fun `a denied to-one eager target fails with a SelectedEdgePath origin`() {
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
        val authorId = run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val author = sys.users.create { name = "H"; email = "h@example.com" }
                .saveAndLoad(viewerContext).getOrThrow()
            sys.notes.create { body = "note"; writer = author.id }.save(viewerContext).getOrThrow()
            author.id
        }

        val result = client.notes.query { loadAuthor() }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
        assertEquals(listOf(SelectedEdgeStep("Note", "author", "User")), origin.steps)
        // Exactly one keyed denial, no hydrated data.
        assertEquals(1, ex.denials.size)
        val denial = ex.denials.single()
        assertEquals("User", denial.entityType)
        assertEquals("id", denial.entityKey.field)
        assertEquals(authorId, denial.entityKey.value)
        assertEquals("author hidden", denial.reason)
    }

    // ---- to-many eager denial: exactly one keyed denial after batch evaluation ----

    @Test
    fun `a to-many eager denial reports exactly the first denied target in traversal order`() {
        val evaluated = mutableListOf<String>()
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy {
                            load(TagLoadPrivacyRule { _, item ->
                                evaluated.add(item.entity.name)
                                PrivacyDecision.Deny("tag ${item.entity.name} hidden")
                            })
                        }
                    }
                })
            }
        }
        val (post, tagA) = run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val post = sys.posts.create { title = "P1" }.saveAndLoad(viewerContext).getOrThrow()
            val tagA = sys.tags.create { name = "a-first" }.saveAndLoad(viewerContext).getOrThrow()
            val tagB = sys.tags.create { name = "b-second" }.saveAndLoad(viewerContext).getOrThrow()
            sys.postTags.create { postId = post.id; tagId = tagA.id }.save(viewerContext).getOrThrow()
            sys.postTags.create { postId = post.id; tagId = tagB.id }.save(viewerContext).getOrThrow()
            Pair(post, tagA)
        }

        val result = client.posts.query {
            where(Post.id eq post.id)
            loadTags { orderBy(Tag.name.asc()) }
        }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
        assertEquals(listOf(SelectedEdgeStep("Post", "tags", "Tag")), origin.steps)
        // Exactly ONE denial — the first denied target in traversal
        // order — even though the scalar adapter evaluated and denied
        // both targets in the batch.
        assertEquals(1, ex.denials.size)
        assertEquals(tagA.id, ex.denials.single().entityKey.value)
        assertEquals(listOf("a-first", "b-second"), evaluated)
    }

    @Test
    fun `strict M2M privacy batch is ordered deduplicated and limited to parent windows`() {
        val invocations = mutableListOf<List<String>>()
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<ReadOnlyEntClient, TagLoadPrivacyItem> { _, batch ->
                                invocations += batch.map { it.entity.name }
                                batch.decideEach {
                                    PrivacyDecision.Deny("tag ${it.entity.name} hidden")
                                }
                            })
                        }
                    }
                })
            }
        }
        val firstDeniedId = run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val postA = sys.posts.create { title = "a-parent" }.saveAndLoad(viewerContext).getOrThrow()
            val postB = sys.posts.create { title = "b-parent" }.saveAndLoad(viewerContext).getOrThrow()
            val outside = sys.tags.create { name = "z-outside-shared" }.saveAndLoad(viewerContext).getOrThrow()
            val right = sys.tags.create { name = "c-right" }.saveAndLoad(viewerContext).getOrThrow()
            val left = sys.tags.create { name = "b-left" }.saveAndLoad(viewerContext).getOrThrow()
            val first = sys.tags.create { name = "a-first-shared" }.saveAndLoad(viewerContext).getOrThrow()
            for (tag in listOf(first, left, outside)) {
                sys.postTags.create { postId = postA.id; tagId = tag.id }.save(viewerContext).getOrThrow()
            }
            for (tag in listOf(first, right, outside)) {
                sys.postTags.create { postId = postB.id; tagId = tag.id }.save(viewerContext).getOrThrow()
            }
            first.id
        }

        val result = client.posts.query {
            orderBy(Post.title.asc())
            loadTags { orderBy(Tag.name.asc()); limit(2) }
        }.all(viewerContext)

        assertEquals(
            listOf(listOf("a-first-shared", "b-left", "c-right")),
            invocations,
        )
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
        assertEquals(1, ex.denials.size)
        assertEquals(firstDeniedId, ex.denials.single().entityKey.value)
    }

    // ---- nested hop path ----

    @Test
    fun `a nested eager denial lists every hop from the root`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                tags(openTags())
                posts(object : EntityPolicy<Post, PostPolicyScope> {
                    override fun configure(scope: PostPolicyScope) = scope.run {
                        privacy {
                            load(PostLoadPrivacyRule { _, item ->
                                if (item.entity.title == "P2-hidden") PrivacyDecision.Deny("post hidden")
                                else PrivacyDecision.Allow
                            })
                        }
                    }
                })
            }
        }
        val hidden = run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val p1 = sys.posts.create { title = "P1" }.saveAndLoad(viewerContext).getOrThrow()
            val p2 = sys.posts.create { title = "P2-hidden" }.saveAndLoad(viewerContext).getOrThrow()
            val tag = sys.tags.create { name = "shared" }.saveAndLoad(viewerContext).getOrThrow()
            sys.postTags.create { postId = p1.id; tagId = tag.id }.save(viewerContext).getOrThrow()
            sys.postTags.create { postId = p2.id; tagId = tag.id }.save(viewerContext).getOrThrow()
            p2
        }

        // Root selects only visible P1; the hidden P2 is reached solely
        // through the nested eager hop Tag → posts.
        val result = client.posts.query {
            where(Post.title eq "P1")
            loadTags { loadPosts() }
        }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
        assertEquals(
            listOf(
                SelectedEdgeStep("Post", "tags", "Tag"),
                SelectedEdgeStep("Tag", "posts", "Post"),
            ),
            origin.steps,
        )
        assertEquals(1, ex.denials.size)
        assertEquals(hidden.id, ex.denials.single().entityKey.value)
    }

    // ---- fail-fast declaration order across edges ----

    @Test
    fun `eager edges are evaluated in declaration order and later eager work is not executed`() {
        var groupRuleEvaluations = 0
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                users(openUsers())
                articles(object : EntityPolicy<Article, ArticlePolicyScope> {
                    override fun configure(scope: ArticlePolicyScope) = scope.run {
                        privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("article hidden") }) }
                    }
                })
                groups(object : EntityPolicy<Group, GroupPolicyScope> {
                    override fun configure(scope: GroupPolicyScope) = scope.run {
                        privacy {
                            load(GroupLoadPrivacyRule { _, _ ->
                                groupRuleEvaluations++
                                PrivacyDecision.Deny("group hidden")
                            })
                        }
                    }
                })
            }
        }
        run {
            val sys = client
            val viewerContext = testBypassContext("test")
            val user = sys.users.create { name = "U"; email = "u@example.com" }
                .saveAndLoad(viewerContext).getOrThrow()
            sys.articles.create { title = "A"; published = true; authorId = user.id }
                .save(viewerContext).getOrThrow()
            val group = sys.groups.create { name = "G" }.saveAndLoad(viewerContext).getOrThrow()
            sys.memberships.create { userId = user.id; groupId = group.id; role = "member" }
                .save(viewerContext).getOrThrow()
        }

        // Both eager targets are denied; the articles edge is declared
        // (and generated) first, so it wins, and the groups edge is not
        // evaluated solely for diagnostics.
        val result = client.users.query {
            loadArticles()
            loadGroups()
        }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
        assertEquals("articles", origin.steps.single().edgeName)
        assertEquals(1, ex.denials.size)
        assertEquals(0, groupRuleEvaluations, "later eager edges must not run after the first denial")
    }

    // ---- root privacy completes before eager loading ----

    @Test
    fun `a denied root wins over a denied eager target — origins never mix`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                notes(object : EntityPolicy<Note, NotePolicyScope> {
                    override fun configure(scope: NotePolicyScope) = scope.run {
                        privacy { load(NoteLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("note hidden") }) }
                    }
                })
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

        val result = client.notes.query { loadAuthor() }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(ex.origin)
        assertTrue(ex.denials.all { it.entityType == "Note" }, "root denial must not mix in eager denials")
    }

    // ---- visibleOrNull never maps eager denial ----

    @Test
    fun `visibleOrNull propagates a SelectedEdgePath denial unchanged`() {
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

        // The root note is visible; only the eager author is denied.
        // Adding an eager load must never convert a visible root into
        // apparent absence.
        val result = client.notes.query { loadAuthor() }.firstOrNull(viewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        assertIs<LoadDenialOrigin.SelectedEdgePath>(
            assertIs<EntPrivacyDeniedException>(failed.exception).origin,
        )

        val projected = result.visibleOrNull()
        assertSame(
            result,
            projected,
            "SelectedEdgePath denial must pass through visibleOrNull unchanged",
        )
    }
}
