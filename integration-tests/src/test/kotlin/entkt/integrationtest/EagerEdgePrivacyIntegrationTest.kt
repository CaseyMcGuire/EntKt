package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
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
import entkt.runtime.result.EagerEdgeStep
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import entkt.runtime.result.visibleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Strict eager-edge privacy: a LOAD-denied eager target makes the
 * ENTIRE root terminal `Failed(EntPrivacyDeniedException(EagerEdge(path),
 * exactly one keyed denial))` — no partial graph, no silent omission.
 * The path names every traversed source type, edge name, and target
 * type (schema names only — no hydrated data). Targets within an edge
 * are evaluated as a batch; across eager edges, a failing edge prevents
 * later eager-edge work in declaration order. `visibleOrNull()` never
 * maps an eager denial to root absence.
 */
class EagerEdgePrivacyIntegrationTest : PostgresTestBase() {

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
    fun `a denied to-one eager target fails the root terminal with an EagerEdge origin`() {
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
        val authorId = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val author = sys.users.create { name = "H"; email = "h@example.com" }
                .saveAndLoad().getOrThrow()
            sys.notes.create { body = "note"; writer = author.id }.save().getOrThrow()
            author.id
        }

        val result = client.notes.query { withAuthor() }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
        assertEquals(listOf(EagerEdgeStep("Note", "author", "User")), origin.path)
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
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy {
                            load(TagLoadPrivacyRule { ctx ->
                                evaluated.add(ctx.entity.name)
                                PrivacyDecision.Deny("tag ${ctx.entity.name} hidden")
                            })
                        }
                    }
                })
            }
        }
        val (post, tagA) = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val post = sys.posts.create { title = "P1" }.saveAndLoad().getOrThrow()
            val tagA = sys.tags.create { name = "a-first" }.saveAndLoad().getOrThrow()
            val tagB = sys.tags.create { name = "b-second" }.saveAndLoad().getOrThrow()
            sys.postTags.create { postId = post.id; tagId = tagA.id }.save().getOrThrow()
            sys.postTags.create { postId = post.id; tagId = tagB.id }.save().getOrThrow()
            Pair(post, tagA)
        }

        val result = client.posts.query {
            where(Post.id eq post.id)
            withTags { orderBy(Tag.name.asc()) }
        }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
        assertEquals(listOf(EagerEdgeStep("Post", "tags", "Tag")), origin.path)
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
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                posts(openPosts())
                tags(object : EntityPolicy<Tag, TagPolicyScope> {
                    override fun configure(scope: TagPolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<TagLoadPrivacyContext> { contexts ->
                                invocations += contexts.map { it.entity.name }
                                contexts.decide {
                                    PrivacyDecision.Deny("tag ${it.entity.name} hidden")
                                }
                            })
                        }
                    }
                })
            }
        }
        val firstDeniedId = client.withPrivacyContext(
            PrivacyContext(Viewer.PrivacyBypass("test")),
        ) { sys ->
            val postA = sys.posts.create { title = "a-parent" }.saveAndLoad().getOrThrow()
            val postB = sys.posts.create { title = "b-parent" }.saveAndLoad().getOrThrow()
            val outside = sys.tags.create { name = "z-outside-shared" }.saveAndLoad().getOrThrow()
            val right = sys.tags.create { name = "c-right" }.saveAndLoad().getOrThrow()
            val left = sys.tags.create { name = "b-left" }.saveAndLoad().getOrThrow()
            val first = sys.tags.create { name = "a-first-shared" }.saveAndLoad().getOrThrow()
            for (tag in listOf(first, left, outside)) {
                sys.postTags.create { postId = postA.id; tagId = tag.id }.save().getOrThrow()
            }
            for (tag in listOf(first, right, outside)) {
                sys.postTags.create { postId = postB.id; tagId = tag.id }.save().getOrThrow()
            }
            first.id
        }

        val result = client.posts.query {
            orderBy(Post.title.asc())
            withTags { orderBy(Tag.name.asc()); limit(2) }
        }.all()

        assertEquals(
            listOf(listOf("a-first-shared", "b-left", "c-right")),
            invocations,
        )
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
        assertEquals(1, ex.denials.size)
        assertEquals(firstDeniedId, ex.denials.single().entityKey.value)
    }

    // ---- nested hop path ----

    @Test
    fun `a nested eager denial lists every hop from the root`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                tags(openTags())
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
        val hidden = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val p1 = sys.posts.create { title = "P1" }.saveAndLoad().getOrThrow()
            val p2 = sys.posts.create { title = "P2-hidden" }.saveAndLoad().getOrThrow()
            val tag = sys.tags.create { name = "shared" }.saveAndLoad().getOrThrow()
            sys.postTags.create { postId = p1.id; tagId = tag.id }.save().getOrThrow()
            sys.postTags.create { postId = p2.id; tagId = tag.id }.save().getOrThrow()
            p2
        }

        // Root selects only visible P1; the hidden P2 is reached solely
        // through the nested eager hop Tag → posts.
        val result = client.posts.query {
            where(Post.title eq "P1")
            withTags { withPosts() }
        }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
        assertEquals(
            listOf(
                EagerEdgeStep("Post", "tags", "Tag"),
                EagerEdgeStep("Tag", "posts", "Post"),
            ),
            origin.path,
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
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies {
                users(openUsers())
                articles(object : EntityPolicy<Article, ArticlePolicyScope> {
                    override fun configure(scope: ArticlePolicyScope) = scope.run {
                        privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Deny("article hidden") }) }
                    }
                })
                groups(object : EntityPolicy<Group, GroupPolicyScope> {
                    override fun configure(scope: GroupPolicyScope) = scope.run {
                        privacy {
                            load(GroupLoadPrivacyRule {
                                groupRuleEvaluations++
                                PrivacyDecision.Deny("group hidden")
                            })
                        }
                    }
                })
            }
        }
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val user = sys.users.create { name = "U"; email = "u@example.com" }
                .saveAndLoad().getOrThrow()
            sys.articles.create { title = "A"; published = true; authorId = user.id }
                .save().getOrThrow()
            val group = sys.groups.create { name = "G" }.saveAndLoad().getOrThrow()
            sys.memberships.create { userId = user.id; groupId = group.id; role = "member" }
                .save().getOrThrow()
        }

        // Both eager targets are denied; the articles edge is declared
        // (and generated) first, so it wins, and the groups edge is not
        // evaluated solely for diagnostics.
        val result = client.users.query {
            withArticles()
            withGroups()
        }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
        assertEquals("articles", origin.path.single().edgeName)
        assertEquals(1, ex.denials.size)
        assertEquals(0, groupRuleEvaluations, "later eager edges must not run after the first denial")
    }

    // ---- root privacy completes before eager loading ----

    @Test
    fun `a denied root wins over a denied eager target — origins never mix`() {
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

        val result = client.notes.query { withAuthor() }.all()

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(ex.origin)
        assertTrue(ex.denials.all { it.entityType == "Note" }, "root denial must not mix in eager denials")
    }

    // ---- visibleOrNull never maps eager denial ----

    @Test
    fun `visibleOrNull propagates an EagerEdge denial unchanged`() {
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

        // The root note is visible; only the eager author is denied.
        // Adding an eager load must never convert a visible root into
        // apparent absence.
        val result = client.notes.query { withAuthor() }.firstOrNull()
        val failed = assertIs<ReadResult.Failed>(result)
        assertIs<LoadDenialOrigin.EagerEdge>(
            assertIs<EntPrivacyDeniedException>(failed.exception).origin,
        )

        val projected = result.visibleOrNull()
        assertSame(result, projected, "EagerEdge denial must pass through visibleOrNull unchanged")
    }
}
