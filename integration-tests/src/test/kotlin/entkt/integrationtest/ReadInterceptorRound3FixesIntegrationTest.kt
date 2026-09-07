// Same opt-in story as ReadInterceptorReviewFixesIntegrationTest:
// directly fabricates `Predicate.HasEdgeWith(...)` to exercise the
// walker's per-edge dispatch.
@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.query.GlobalQueryInterceptor
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.ReadResult
import entkt.runtime.result.visibleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression coverage for the post-review round-3 fixes, expressed
 * against the canonical operation-result algebra.
 *
 *  - Traversal-source interceptor invocation deferred to terminal
 *    time, so `.queryX().all(testViewerContext)` captures source-step rejection as
 *    `ReadResult.Failed(EntQueryRejectedException)` instead of
 *    having queryX() throw.
 *  - Identity-based skipWalk: a caller-authored predicate that's
 *    structurally equal to a framework structural is still walked
 *    through the edge-predicate processor.
 *  - Recursion guard on the edge-predicate walker: a cyclic
 *    interceptor configuration trips the guard with a clear error,
 *    captured as `Failed(IllegalStateException)` by the terminal.
 *  - deleteMany candidate selection routes through
 *    DELETE_CANDIDATES interceptors; its rejection surfaces as
 *    `MutationResult.Failed(EntUnexpectedMutationException(NotPersisted,
 *    cause = EntQueryRejectedException))`.
 */
class ReadInterceptorRound3FixesIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    @Test
    fun `one privacy context is shared by traversal edge predicate eager load and LOAD privacy`() {
        val driver = freshDriver()
        val firstPrivacy = ViewerContext(Viewer.User("viewer-a"))
        val interceptorObservations = mutableListOf<Pair<ReadOperation, ViewerContext>>()
        val loadContexts = mutableListOf<ViewerContext>()
        val client = EntClient(driver) {
            interceptors {
                users(
                    QueryInterceptor { _, ctx -> interceptorObservations += ctx.operation to ctx.viewerContext },
                    name = "user-context-observer",
                )
                articles(
                    QueryInterceptor { _, ctx -> interceptorObservations += ctx.operation to ctx.viewerContext },
                    name = "article-context-observer",
                )
            }
            policies {
                users(object : entkt.runtime.privacy.EntityPolicy<User, entkt.integrationtest.ent.UserPolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.UserPolicyScope) = scope.run {
                        privacy {
                            load(entkt.integrationtest.ent.UserLoadPrivacyRule { context, _ ->
                                loadContexts += context.viewerContext
                                entkt.runtime.privacy.PrivacyDecision.Allow
                            })
                        }
                    }
                })
                articles(object : entkt.runtime.privacy.EntityPolicy<Article, entkt.integrationtest.ent.ArticlePolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.ArticlePolicyScope) = scope.run {
                        privacy {
                            load(entkt.integrationtest.ent.ArticleLoadPrivacyRule { context, _ ->
                                loadContexts += context.viewerContext
                                entkt.runtime.privacy.PrivacyDecision.Allow
                            })
                        }
                    }
                })
            }
        }

        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val author = sys.users.create { name = "author"; email = "author@x" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "article"; authorId = author.id }.saveAndLoad(testViewerContext).getOrThrow()
        }
        interceptorObservations.clear()
        loadContexts.clear()

        val articles = client.users.query().queryArticles {
            where(Predicate.HasEdge<Article>("author"))
            loadAuthor()
        }.all(firstPrivacy).getOrThrow()

        assertEquals(1, articles.size)
        assertEquals(
            listOf(
                ReadOperation.EDGE_TRAVERSAL,
                ReadOperation.ALL,
                ReadOperation.EDGE_PREDICATE,
                ReadOperation.EAGER_LOAD,
            ),
            interceptorObservations.map { it.first },
        )
        assertEquals(2, loadContexts.size, "root and eager-target LOAD privacy should both run")
        (interceptorObservations.map { it.second } + loadContexts).forEach { captured ->
            assertSame(firstPrivacy, captured, "every nested read phase must receive the terminal's supplied context")
        }
    }

    // ---------- traversal-source rejection captured by the terminal ----------

    @Test
    fun `traversal-source rejection surfaces as Failed(EntQueryRejectedException) on chained all`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                users(
                    QueryInterceptor { scope, _ -> scope.reject("source nope", code = "src_rej") },
                    name = "user-rejector",
                )
            }
        }
        // queryArticles() must NOT throw; the rejection materializes
        // when all() runs its source-step inside the capture boundary.
        val target = client.users.query().queryArticles()
        val result = target.all(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result, "expected Failed, got $result")
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("src_rej", ex.code)
        assertEquals("user-rejector", ex.interceptor)
        assertEquals("User", ex.entityType)
    }

    @Test
    fun `traversal-source rejection surfaces as Failed on chained firstOrNull`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                users(QueryInterceptor { scope, _ -> scope.reject("nope") }, name = "rej")
            }
        }
        val first = assertIs<ReadResult.Failed>(client.users.query().queryArticles().firstOrNull(testViewerContext))
        assertIs<EntQueryRejectedException>(first.exception)
    }

    @Test
    fun `traversal-source rejection throws the stored exception through getOrThrow`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                users(QueryInterceptor { scope, _ -> scope.reject("nope", code = "src") }, name = "rej")
            }
        }
        val result = client.users.query().queryArticles().all(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val thrown = assertFailsWith<EntQueryRejectedException> { result.getOrThrow() }
        assertSame(failed.exception, thrown)
        assertEquals("src", thrown.code)
    }

    @Test
    fun `caller-authored HasEdge structurally equal to a framework-structural is still walked`() {
        // Construct a caller HasEdge whose target has a soft-delete-
        // style interceptor; the walker should fire that target
        // interceptor on the caller predicate (not skip it).
        val driver = freshDriver()
        var fired = false
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { _, _ -> fired = true },
                    name = "article-edge-observer",
                )
            }
        }
        // User.articles.exists() — HasEdge("articles") added by caller.
        // No traversal context, no extraStructural, so skipWalk is
        // empty — the walker must process it and fire Article.interceptors.
        client.users.query { where(User.articles.exists()) }.all(testViewerContext).getOrThrow()
        assertTrue(fired, "Article EDGE_PREDICATE interceptor should fire for the caller's User.articles.exists()")
    }

    // ---------- recursion guard ----------

    @Test
    fun `edge-predicate interceptor cycle trips the recursion guard`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                // User interceptor adds User.articles.has → walker
                // dispatches to Article. Article interceptor adds
                // Article.author.has → walker dispatches back to
                // User. Cycle.
                users(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(
                            Predicate.HasEdgeWith("articles", Predicate.Leaf("published", Op.EQ, true)),
                        )
                    },
                    name = "user-edge-loop",
                )
                articles(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(
                            Predicate.HasEdgeWith("author", Predicate.Leaf("name", Op.EQ, "x")),
                        )
                    },
                    name = "article-edge-loop",
                )
            }
        }
        // The guard's clear IllegalStateException (rather than a
        // StackOverflowError) is a terminal-level failure — captured
        // in the result, not thrown.
        val result = client.users.query().all(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<IllegalStateException>(failed.exception)
        assertTrue(
            ex.message!!.contains("edge-predicate interceptor recursion exceeded depth"),
            "expected clear recursion-guard message, got: ${ex.message}",
        )
    }

    @Test
    fun `mutating source query after queryX does not leak into target's terminal`() {
        val driver = freshDriver()
        val client = EntClient(driver)
        // Two users, one article each. The post-queryX where on
        // the source MUST NOT affect what rows queryArticles
        // sees at terminal time — pre-snapshot the deferred
        // lambda would re-read `users.predicates` at terminal
        // time and include the late `name = "alice"` filter.
        val alice = client.users.create { name = "alice"; email = "alice@x" }.saveAndLoad(testViewerContext).getOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "alice-article"; authorId = alice.id }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "bob-article"; authorId = bob.id }.saveAndLoad(testViewerContext).getOrThrow()

        val users = client.users.query()
        val articles = users.queryArticles()

        // Mutate the source AFTER queryX. If the lambda captures
        // `this` live, this where leaks into the bridge and
        // articles.all(testViewerContext) returns only "alice-article".
        // If the lambda captures a snapshot, this where is
        // invisible to the bridge.
        users.where(Predicate.Leaf("name", Op.EQ, "alice"))

        val result = articles.all(testViewerContext).getOrThrow()
        assertEquals(
            setOf("alice-article", "bob-article"),
            result.map { it.title }.toSet(),
            "queryX should snapshot source state at construction; post-queryX mutations to source must not leak into the target's terminal call",
        )
    }

    @Test
    fun `M2M traversal also snapshots source at queryX time`() {
        val driver = freshDriver()
        val client = EntClient(driver)
        // Seed: two posts, one tag each (linked via the junction
        // table). The snapshot case returns [keeperTag]; a leak
        // of the post-queryX `where(title eq "intruder")` into
        // the bridge would intersect to zero matching posts
        // (id = keeper.id has title = "keeper", not "intruder")
        // and return []. So [keeperTag] vs [] cleanly
        // distinguishes the two implementations.
        val keeper = client.posts.create { title = "keeper" }.saveAndLoad(testViewerContext).getOrThrow()
        val intruder = client.posts.create { title = "intruder" }.saveAndLoad(testViewerContext).getOrThrow()
        val keeperTag = client.tags.create { name = "keeper-tag" }.saveAndLoad(testViewerContext).getOrThrow()
        val intruderTag = client.tags.create { name = "intruder-tag" }.saveAndLoad(testViewerContext).getOrThrow()
        client.postTags.create { postId = keeper.id; tagId = keeperTag.id }.saveAndLoad(testViewerContext).getOrThrow()
        client.postTags.create { postId = intruder.id; tagId = intruderTag.id }.saveAndLoad(testViewerContext).getOrThrow()

        val posts = client.posts.query { where(Post.id eq keeper.id) }
        val tags = posts.queryTags()

        // Mutate AFTER queryX. Pre-snapshot fix this leaked into
        // the bridge predicate; with the snapshot it does not.
        posts.where(Predicate.Leaf("title", Op.EQ, "intruder"))

        val result = tags.all(testViewerContext).getOrThrow()
        assertEquals(
            listOf("keeper-tag"),
            result.map { it.name },
            "M2M queryX should snapshot source state at construction; post-queryX mutations must not leak into the bridge predicate",
        )
    }

    // ---------- eager-target denial vs root denial on firstOrNull ----------

    @Test
    fun `firstOrNull fails on eager-target privacy denial and visibleOrNull does not mask it`() {
        val driver = freshDriver()
        // Article repo allows everything; User repo denies the
        // specific viewer. firstOrNull on Article with an eager
        // `loadAuthor()` fails with EntPrivacyDeniedException whose
        // origin is EagerEdge when the article's author is denied.
        // visibleOrNull maps only ROOT denials to absence, so the
        // eager denial must stay Failed — an eager load can never
        // turn a visible root into apparent absence.
        val viewer = Viewer.User("denied-user")
        val viewerContext = ViewerContext(viewer)
        val client = EntClient(driver) {

            policies {
                articles(object : entkt.runtime.privacy.EntityPolicy<Article, entkt.integrationtest.ent.ArticlePolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.ArticlePolicyScope) = scope.run {
                        privacy { load(entkt.integrationtest.ent.ArticleLoadPrivacyRule { _, _ -> entkt.runtime.privacy.PrivacyDecision.Allow }) }
                    }
                })
                users(object : entkt.runtime.privacy.EntityPolicy<User, entkt.integrationtest.ent.UserPolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.UserPolicyScope) = scope.run {
                        privacy { load(entkt.integrationtest.ent.UserLoadPrivacyRule { _, _ -> entkt.runtime.privacy.PrivacyDecision.Deny("user is hidden") }) }
                    }
                })
            }
        }
        // Seed via bypass viewer (bypasses policies).
        run {
            val sys = client
            val testViewerContext = testBypassContext("test")
            val u = sys.users.create { name = "denied-user"; email = "d@x" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "with-denied-author"; authorId = u.id }.saveAndLoad(testViewerContext).getOrThrow()
        }

        val result = client.articles.query { loadAuthor() }.firstOrNull(viewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
        // Eager denial is NOT root invisibility — visibleOrNull leaves
        // the failure untouched.
        assertIs<ReadResult.Failed>(result.visibleOrNull())
    }

    @Test
    fun `firstOrNull fails with a Root denial on a denied first row and visibleOrNull maps it to absence`() {
        val viewerContext = ViewerContext(Viewer.User("u1"))
        val driver = freshDriver()
        // Two articles, one published, one draft. Article privacy
        // denies the draft. The canonical firstOrNull evaluates ONLY
        // the selected first row — it never scans past a denied row —
        // so with the draft ordered first the result is
        // Failed(EntPrivacyDeniedException(Root, one denial)), and
        // visibleOrNull() projects that to authoritative absence.
        // Use a non-bypass viewer because PrivacyBypass skips privacy
        // entirely (in which case the draft would be returned and the
        // test would defeat itself).
        val client = EntClient(driver) {

            policies {
                articles(object : entkt.runtime.privacy.EntityPolicy<Article, entkt.integrationtest.ent.ArticlePolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.ArticlePolicyScope) = scope.run {
                        privacy {
                            load(entkt.integrationtest.ent.ArticleLoadPrivacyRule { _, item ->
                                if (item.published) entkt.runtime.privacy.PrivacyDecision.Allow
                                else entkt.runtime.privacy.PrivacyDecision.Deny("draft")
                            })
                        }
                    }
                })
                users(object : entkt.runtime.privacy.EntityPolicy<User, entkt.integrationtest.ent.UserPolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.UserPolicyScope) = scope.run {
                        privacy { load(entkt.integrationtest.ent.UserLoadPrivacyRule { _, _ -> entkt.runtime.privacy.PrivacyDecision.Allow }) }
                    }
                })
            }
        }
        // Seed via bypass viewer so the create path's post-write LOAD
        // check on "draft" doesn't trip.
        run {
            val sys = client
            val testViewerContext = testBypassContext("test")
            val u = sys.users.create { name = "u"; email = "u@x" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "draft"; published = false; authorId = u.id }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "published"; published = true; authorId = u.id }.saveAndLoad(testViewerContext).getOrThrow()
        }

        // Order by title so "draft" is deterministically the selected
        // first row.
        val result = client.articles.query {
            orderBy(OrderField("title", OrderDirection.ASC))
        }.firstOrNull(viewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(ex.origin)
        assertEquals(1, ex.denials.size)
        assertEquals("Article", ex.denials.single().entityType)
        // Root denial → visibleOrNull projects to Success(null).
        assertNull(result.visibleOrNull().getOrThrow())
    }

    // ---------- deleteMany routes through DELETE_CANDIDATES interceptors ----------

    @Test
    fun `deleteMany fires interceptors with DELETE_CANDIDATES operation`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "obs",
                )
            }
        }
        client.posts.create { title = "x" }.saveAndLoad(testViewerContext).getOrThrow()
        client.posts.create { title = "y" }.saveAndLoad(testViewerContext).getOrThrow()
        client.posts.deleteMany(testViewerContext, ).getOrThrow()
        assertEquals(listOf(ReadOperation.DELETE_CANDIDATES), ops)
    }

    @Test
    fun `deleteMany honors interceptor-added predicate on candidate fetch`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                // Tenant-scoping-style interceptor: only "scope-A"
                // posts are candidates. Pre-fix deleteMany bypassed
                // this and deleted both posts.
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "scope-A"))
                    },
                    name = "scope-filter",
                )
            }
        }
        client.posts.create { title = "scope-A" }.saveAndLoad(testViewerContext).getOrThrow()
        client.posts.create { title = "scope-B" }.saveAndLoad(testViewerContext).getOrThrow()

        val deleted: Int = client.posts.deleteMany(testViewerContext, ).getOrThrow()
        assertEquals(1, deleted)
        // Verify scope-B survived by inspecting the raw table
        // (findById would also hit the interceptor's
        // `title = scope-A` filter and report absence for the
        // survivor — that's correct uniform interceptor
        // behavior, but doesn't help us verify physical survival).
        val remainingRows = driver.query("posts", emptyList(), emptyList(), null, null)
        assertEquals(1, remainingRows.size)
        assertEquals("scope-B", remainingRows.single()["title"])
    }

    @Test
    fun `deleteMany interceptor rejection surfaces as Failed(EntUnexpectedMutationException) with rejection cause`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("no broad delete", code = "broad_delete_denied") },
                    name = "broad-delete-guard",
                )
            }
        }
        client.posts.create { title = "x" }.saveAndLoad(testViewerContext).getOrThrow()
        val result = client.posts.deleteMany(testViewerContext, )
        val failed = assertIs<MutationResult.Failed>(result)
        // Candidate-selection rejection is not a classified mutation
        // failure kind — it surfaces as the unexpected wrapper with
        // NotPersisted (nothing was written) and the typed rejection
        // as cause.
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        val rejection = assertIs<EntQueryRejectedException>(ex.cause)
        assertEquals("broad_delete_denied", rejection.code)
        assertEquals("broad-delete-guard", rejection.interceptor)
        assertEquals("Post", rejection.entityType)
        // Nothing was deleted.
        assertEquals(1, driver.query("posts", emptyList(), emptyList(), null, null).size)
    }

    @Test
    fun `deleteMany limit interceptor mutators are silent no-ops on DELETE_CANDIDATES`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                // requireLimitAtMost(2) on DELETE_CANDIDATES MUST
                // NOT clamp the candidate fetch — that would turn
                // deleteMany into "delete first 2 matching rows"
                // without the caller knowing. Silent no-op per the
                // limit-shape rules.
                global(
                    GlobalQueryInterceptor { scope, _ -> scope.requireLimitAtMost(2) },
                    name = "cap-2",
                )
            }
        }
        repeat(5) { i -> client.posts.create { title = "p$i" }.saveAndLoad(testViewerContext).getOrThrow() }

        val deleted: Int = client.posts.deleteMany(testViewerContext, ).getOrThrow()
        assertEquals(5, deleted, "limit clamp must be silent no-op on DELETE_CANDIDATES; all 5 rows should be deleted")
    }

    // ---------- Edge-predicate target annotations bubble up ----------

}
