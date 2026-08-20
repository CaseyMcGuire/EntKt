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
import entkt.runtime.query.InterceptorEngine
import entkt.runtime.privacy.PrivacyContext
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
 *    time, so `.queryX().all()` captures source-step rejection as
 *    `ReadResult.Failed(EntQueryRejectedException)` instead of
 *    having queryX() throw.
 *  - Traversal-source annotations carry forward into the terminal
 *    QueryPlan.
 *  - Identity-based skipWalk: a caller-authored predicate that's
 *    structurally equal to a framework structural is still walked
 *    through the edge-predicate processor.
 *  - Recursion guard on the edge-predicate walker: a cyclic
 *    interceptor configuration trips the guard with a clear error,
 *    captured as `Failed(IllegalStateException)` by the terminal.
 *  - Raw-exists explain matches the runtime driver call shape
 *    (+ the limit(0) edge case).
 *  - requireNotRejected throws the original stored
 *    EntQueryRejectedException (entityType preserved) rather than
 *    synthesizing "<explain>" metadata.
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
        val firstPrivacy = PrivacyContext(Viewer.User("viewer-a"))
        val laterPrivacy = PrivacyContext(Viewer.User("viewer-b"))
        var providerCalls = 0
        val interceptorObservations = mutableListOf<Pair<ReadOperation, PrivacyContext>>()
        val loadContexts = mutableListOf<PrivacyContext>()
        val client = EntClient(driver) {
            privacyContext {
                providerCalls++
                if (providerCalls == 1) firstPrivacy else laterPrivacy
            }
            interceptors {
                users(
                    QueryInterceptor { _, ctx -> interceptorObservations += ctx.operation to ctx.privacy },
                    name = "user-context-observer",
                )
                articles(
                    QueryInterceptor { _, ctx -> interceptorObservations += ctx.operation to ctx.privacy },
                    name = "article-context-observer",
                )
            }
            policies {
                users(object : entkt.runtime.privacy.EntityPolicy<User, entkt.integrationtest.ent.UserPolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.UserPolicyScope) = scope.run {
                        privacy {
                            load(entkt.integrationtest.ent.UserLoadPrivacyRule { context, _ ->
                                loadContexts += context.privacy
                                entkt.runtime.privacy.PrivacyDecision.Allow
                            })
                        }
                    }
                })
                articles(object : entkt.runtime.privacy.EntityPolicy<Article, entkt.integrationtest.ent.ArticlePolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.ArticlePolicyScope) = scope.run {
                        privacy {
                            load(entkt.integrationtest.ent.ArticleLoadPrivacyRule { context, _ ->
                                loadContexts += context.privacy
                                entkt.runtime.privacy.PrivacyDecision.Allow
                            })
                        }
                    }
                })
            }
        }

        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val author = sys.users.create { name = "author"; email = "author@x" }.saveAndLoad().getOrThrow()
            sys.articles.create { title = "article"; authorId = author.id }.saveAndLoad().getOrThrow()
        }
        providerCalls = 0
        interceptorObservations.clear()
        loadContexts.clear()

        val articles = client.users.query().queryArticles {
            where(Predicate.HasEdge<Article>("author"))
            loadAuthor()
        }.all().getOrThrow()

        assertEquals(1, articles.size)
        assertEquals(1, providerCalls, "a read terminal must capture its PrivacyContext exactly once")
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
            assertSame(firstPrivacy, captured, "every nested read phase must receive the terminal's captured context")
        }
    }

    // ---------- traversal-source rejection captured by the terminal ----------

    @Test
    fun `traversal-source rejection surfaces as Failed(EntQueryRejectedException) on chained all`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        val result = target.all()
        val failed = assertIs<ReadResult.Failed>(result, "expected Failed, got $result")
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("src_rej", ex.code)
        assertEquals("user-rejector", ex.interceptor)
        assertEquals("User", ex.entityType)
    }

    @Test
    fun `traversal-source rejection surfaces as Failed on chained firstOrNull and rawCount`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(QueryInterceptor { scope, _ -> scope.reject("nope") }, name = "rej")
            }
        }
        val first = assertIs<ReadResult.Failed>(client.users.query().queryArticles().firstOrNull())
        assertIs<EntQueryRejectedException>(first.exception)
        val count = assertIs<ReadResult.Failed>(client.users.query().queryArticles().rawCount())
        assertIs<EntQueryRejectedException>(count.exception)
    }

    @Test
    fun `traversal-source rejection throws the stored exception through getOrThrow`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(QueryInterceptor { scope, _ -> scope.reject("nope", code = "src") }, name = "rej")
            }
        }
        val result = client.users.query().queryArticles().all()
        val failed = assertIs<ReadResult.Failed>(result)
        val thrown = assertFailsWith<EntQueryRejectedException> { result.getOrThrow() }
        assertSame(failed.exception, thrown)
        assertEquals("src", thrown.code)
    }

    @Test
    fun `traversal-source rejection surfaces as rejected QueryPlan on chained explain`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(QueryInterceptor { scope, _ -> scope.reject("src nope", code = "src") }, name = "rej")
            }
        }
        val plan = client.users.query().queryArticles().explainAll()
        assertTrue(plan.rejected)
        assertEquals("src", plan.rejectedCode)
        assertEquals("rej", plan.rejectedInterceptor)
        // requireNotRejected throws the original stored exception —
        // entityType is the source entity (User), not synthetic.
        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        assertEquals("User", ex.entityType)
    }

    // ---------- traversal-source annotations carry forward ----------

    @Test
    fun `traversal-source annotations surface on terminal QueryPlan`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { scope, _ -> scope.addAnnotation("tenant", "acme") },
                    name = "user-tenant",
                )
                articles(
                    QueryInterceptor { scope, _ -> scope.addAnnotation("step", "article-terminal") },
                    name = "article-step",
                )
            }
        }
        val plan = client.users.query().queryArticles().explainAll()
        // Both source-step ("tenant=acme" from User) and terminal-step
        // ("step=article-terminal" from Article) annotations are
        // present.
        assertEquals("acme", plan.annotations["tenant"])
        assertEquals("article-terminal", plan.annotations["step"])
    }

    @Test
    fun `terminal interceptor overwrites a source annotation with the same key (last-writer-wins)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                users(
                    QueryInterceptor { scope, _ -> scope.addAnnotation("step", "from-user") },
                    name = "u",
                )
                articles(
                    QueryInterceptor { scope, _ -> scope.addAnnotation("step", "from-article") },
                    name = "a",
                )
            }
        }
        val plan = client.users.query().queryArticles().explainAll()
        assertEquals("from-article", plan.annotations["step"])
    }

    // ---------- identity-based skipWalk ----------

    @Test
    fun `caller-authored HasEdge structurally equal to a framework-structural is still walked`() {
        // Construct a caller HasEdge whose target has a soft-delete-
        // style interceptor; the walker should fire that target
        // interceptor on the caller predicate (not skip it).
        val driver = freshDriver()
        var fired = false
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        client.users.query { where(User.articles.exists()) }.all().getOrThrow()
        assertTrue(fired, "Article EDGE_PREDICATE interceptor should fire for the caller's User.articles.exists()")
    }

    // ---------- recursion guard ----------

    @Test
    fun `edge-predicate interceptor cycle trips the recursion guard`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        val result = client.users.query().all()
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<IllegalStateException>(failed.exception)
        assertTrue(
            ex.message!!.contains("edge-predicate interceptor recursion exceeded depth"),
            "expected clear recursion-guard message, got: ${ex.message}",
        )
        // Sanity: the cap is the documented constant.
        assertEquals(32, InterceptorEngine.EDGE_PREDICATE_MAX_DEPTH)
    }

    // ---------- raw-exists explain matches runtime ----------

    @Test
    fun `explainRawExists with limit(0) shows limit 0, matching runtime`() {
        val driver = freshDriver()
        val client = EntClient(driver) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }
        // No interceptors so spec.limit = caller's limit(0) = 0.
        // Runtime: `minOf(1, 0 ?: 1) = 0` → driver.query with limit 0.
        // Explain must show the same.
        val plan = client.posts.query { limit(0) }.explainRawExists()
        assertNotNull(plan.root)
        val desc = plan.root.toString()
        assertTrue(
            desc.contains("LIMIT 0") || desc.contains("limit=0") || desc.contains("limit: 0"),
            "explainRawExists with limit(0) should show limit 0; was: $desc",
        )
    }

    @Test
    fun `explainRawExists drops orderBy and preserves offset, matching runtime`() {
        val driver = freshDriver()
        val client = EntClient(driver) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }
        // Runtime rawExists calls driver.query with orderBy =
        // emptyList() and offset = spec.offset. Explain must
        // mirror exactly. Pre-fix: explain kept spec.orderBy and
        // forced offset = null.
        val plan = client.posts.query {
            orderBy(OrderField("title", OrderDirection.ASC))
            offset(5)
        }.explainRawExists()
        assertNotNull(plan.root)
        val desc = plan.root.toString()
        // orderBy is dropped — should NOT mention "title" in the
        // explain output (the only place it could appear is in
        // ORDER BY since there's no predicate on title).
        assertTrue(
            !desc.contains("ORDER BY") && !desc.contains("orderBy=[OrderField"),
            "explainRawExists should drop orderBy to match runtime; was: $desc",
        )
        // offset is preserved.
        assertTrue(
            desc.contains("OFFSET 5") || desc.contains("offset=5") || desc.contains("offset: 5"),
            "explainRawExists should preserve caller offset; was: $desc",
        )
    }

    // ---------- Deferred traversal snapshots source at queryX() ----------

    @Test
    fun `mutating source query after queryX does not leak into target's terminal`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        // Two users, one article each. The post-queryX where on
        // the source MUST NOT affect what rows queryArticles
        // sees at terminal time — pre-snapshot the deferred
        // lambda would re-read `users.predicates` at terminal
        // time and include the late `name = "alice"` filter.
        val alice = client.users.create { name = "alice"; email = "alice@x" }.saveAndLoad().getOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@x" }.saveAndLoad().getOrThrow()
        client.articles.create { title = "alice-article"; authorId = alice.id }.saveAndLoad().getOrThrow()
        client.articles.create { title = "bob-article"; authorId = bob.id }.saveAndLoad().getOrThrow()

        val users = client.users.query()
        val articles = users.queryArticles()

        // Mutate the source AFTER queryX. If the lambda captures
        // `this` live, this where leaks into the bridge and
        // articles.all() returns only "alice-article".
        // If the lambda captures a snapshot, this where is
        // invisible to the bridge.
        users.where(Predicate.Leaf("name", Op.EQ, "alice"))

        val result = articles.all().getOrThrow()
        assertEquals(
            setOf("alice-article", "bob-article"),
            result.map { it.title }.toSet(),
            "queryX should snapshot source state at construction; post-queryX mutations to source must not leak into the target's terminal call",
        )
    }

    @Test
    fun `M2M traversal also snapshots source at queryX time`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
        // Seed: two posts, one tag each (linked via the junction
        // table). The snapshot case returns [keeperTag]; a leak
        // of the post-queryX `where(title eq "intruder")` into
        // the bridge would intersect to zero matching posts
        // (id = keeper.id has title = "keeper", not "intruder")
        // and return []. So [keeperTag] vs [] cleanly
        // distinguishes the two implementations.
        val keeper = client.posts.create { title = "keeper" }.saveAndLoad().getOrThrow()
        val intruder = client.posts.create { title = "intruder" }.saveAndLoad().getOrThrow()
        val keeperTag = client.tags.create { name = "keeper-tag" }.saveAndLoad().getOrThrow()
        val intruderTag = client.tags.create { name = "intruder-tag" }.saveAndLoad().getOrThrow()
        client.postTags.create { postId = keeper.id; tagId = keeperTag.id }.saveAndLoad().getOrThrow()
        client.postTags.create { postId = intruder.id; tagId = intruderTag.id }.saveAndLoad().getOrThrow()

        val posts = client.posts.query { where(Post.id eq keeper.id) }
        val tags = posts.queryTags()

        // Mutate AFTER queryX. Pre-snapshot fix this leaked into
        // the bridge predicate; with the snapshot it does not.
        posts.where(Predicate.Leaf("title", Op.EQ, "intruder"))

        val result = tags.all().getOrThrow()
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
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
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
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val u = sys.users.create { name = "denied-user"; email = "d@x" }.saveAndLoad().getOrThrow()
            sys.articles.create { title = "with-denied-author"; authorId = u.id }.saveAndLoad().getOrThrow()
        }

        val result = client.articles.query { loadAuthor() }.firstOrNull()
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
        // Eager denial is NOT root invisibility — visibleOrNull leaves
        // the failure untouched.
        assertIs<ReadResult.Failed>(result.visibleOrNull())
    }

    @Test
    fun `firstOrNull fails with a Root denial on a denied first row and visibleOrNull maps it to absence`() {
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
            privacyContext { PrivacyContext(Viewer.User("u1")) }
            policies {
                articles(object : entkt.runtime.privacy.EntityPolicy<Article, entkt.integrationtest.ent.ArticlePolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.ArticlePolicyScope) = scope.run {
                        privacy {
                            load(entkt.integrationtest.ent.ArticleLoadPrivacyRule { _, item ->
                                if (item.entity.published) entkt.runtime.privacy.PrivacyDecision.Allow
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
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val u = sys.users.create { name = "u"; email = "u@x" }.saveAndLoad().getOrThrow()
            sys.articles.create { title = "draft"; published = false; authorId = u.id }.saveAndLoad().getOrThrow()
            sys.articles.create { title = "published"; published = true; authorId = u.id }.saveAndLoad().getOrThrow()
        }

        // Order by title so "draft" is deterministically the selected
        // first row.
        val result = client.articles.query {
            orderBy(OrderField("title", OrderDirection.ASC))
        }.firstOrNull()
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
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "obs",
                )
            }
        }
        client.posts.create { title = "x" }.saveAndLoad().getOrThrow()
        client.posts.create { title = "y" }.saveAndLoad().getOrThrow()
        client.posts.deleteMany().getOrThrow()
        assertEquals(listOf(ReadOperation.DELETE_CANDIDATES), ops)
    }

    @Test
    fun `deleteMany honors interceptor-added predicate on candidate fetch`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        client.posts.create { title = "scope-A" }.saveAndLoad().getOrThrow()
        client.posts.create { title = "scope-B" }.saveAndLoad().getOrThrow()

        val deleted: Int = client.posts.deleteMany().getOrThrow()
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
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("no broad delete", code = "broad_delete_denied") },
                    name = "broad-delete-guard",
                )
            }
        }
        client.posts.create { title = "x" }.saveAndLoad().getOrThrow()
        val result = client.posts.deleteMany()
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
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
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
        repeat(5) { i -> client.posts.create { title = "p$i" }.saveAndLoad().getOrThrow() }

        val deleted: Int = client.posts.deleteMany().getOrThrow()
        assertEquals(5, deleted, "limit clamp must be silent no-op on DELETE_CANDIDATES; all 5 rows should be deleted")
    }

    // ---------- Edge-predicate target annotations bubble up ----------

    @Test
    fun `edge-predicate target interceptor annotations surface on outer QueryPlan`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                // Article EDGE_PREDICATE step adds annotations.
                // Without this fix they vanish — only spec.predicates
                // was reduced into the inner; spec.annotations was
                // discarded.
                articles(
                    QueryInterceptor { scope, _ ->
                        scope.addAnnotation("article-scoped", "true")
                        scope.addAnnotation("audit", "via-has")
                    },
                    name = "article-edge-annotator",
                )
            }
        }
        val plan = client.users.query {
            where(User.articles.has { where(Article.published eq true) })
        }.explainAll()
        assertEquals("true", plan.annotations["article-scoped"])
        assertEquals("via-has", plan.annotations["audit"])
    }

    @Test
    fun `outer-step annotation wins when edge-predicate target uses the same key`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                articles(
                    QueryInterceptor { scope, _ -> scope.addAnnotation("step", "from-article-edge") },
                    name = "article-annotator",
                )
                users(
                    QueryInterceptor { scope, _ -> scope.addAnnotation("step", "from-user-outer") },
                    name = "user-annotator",
                )
            }
        }
        val plan = client.users.query {
            where(User.articles.has { where(Article.published eq true) })
        }.explainAll()
        // Outer step (User) wins on key conflicts — matches the
        // traversal-source-vs-terminal direction (closer-to-caller
        // wins).
        assertEquals("from-user-outer", plan.annotations["step"])
    }

    @Test
    fun `requireNotRejected throws with the original entityType, not synthetic values`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope", code = "x") },
                    name = "post-rejector",
                )
            }
        }
        val plan = client.posts.query().explainAll()
        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        // Pre-fix: the entity was synthesized as "<explain>".
        assertEquals("Post", ex.entityType)
        assertEquals("nope", ex.reason)
        assertEquals("x", ex.code)
        assertEquals("post-rejector", ex.interceptor)
    }
}
