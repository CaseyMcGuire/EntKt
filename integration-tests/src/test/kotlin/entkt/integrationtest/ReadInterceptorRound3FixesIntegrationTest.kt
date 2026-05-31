// Same opt-in story as ReadInterceptorReviewFixesIntegrationTest:
// directly fabricates `Predicate.HasEdgeWith(...)` to exercise the
// walker's per-edge dispatch.
@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.EntQueryRejectedException
import entkt.runtime.EntResult
import entkt.runtime.GlobalQueryInterceptor
import entkt.runtime.InterceptorEngine
import entkt.runtime.PrivacyContext
import entkt.runtime.QueryInterceptor
import entkt.runtime.ReadOperation
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the post-review round-3 fixes.
 *
 *  - Traversal-source interceptor invocation deferred to terminal
 *    time, so `.queryX().allOrError()` catches source-step rejection
 *    as `Err(QueryRejected)` instead of having queryX() throw.
 *  - Traversal-source annotations carry forward into the terminal
 *    QueryPlan.
 *  - Identity-based skipWalk: a caller-authored predicate that's
 *    structurally equal to a framework structural is still walked
 *    through the edge-predicate processor.
 *  - Recursion guard on the edge-predicate walker: a cyclic
 *    interceptor configuration trips the guard with a clear error.
 *  - Visible-explain matches runtime overfetch-cap shape (+ the
 *    limit(0) edge case for exists explains).
 *  - requireNotRejected preserves the original
 *    EntError.QueryRejected (entity, operation) rather than
 *    synthesizing "<explain>" / QUERY.
 */
class ReadInterceptorRound3FixesIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    // ---------- traversal-source rejection caught by *OrError ----------

    @Test
    fun `traversal-source rejection surfaces as Err(QueryRejected) on chained allOrError`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                users(
                    QueryInterceptor { scope, _ -> scope.reject("source nope", code = "src_rej") },
                    name = "user-rejector",
                )
            }
        }
        // queryArticles() must NOT throw; the rejection materializes
        // when allOrError() runs its source-step inside its try/catch.
        val target = client.users.query().queryArticles()
        val result = target.allOrError()
        assertTrue(result is EntResult.Err, "expected Err, got $result")
        val err = (result as EntResult.Err).error
        assertTrue(err is EntError.QueryRejected)
        assertEquals("src_rej", (err as EntError.QueryRejected).code)
        assertEquals("user-rejector", err.interceptor)
        assertEquals("User", err.entity)
    }

    @Test
    fun `traversal-source rejection surfaces as Err(QueryRejected) on chained firstOrError and rawCountOrError`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                users(QueryInterceptor { scope, _ -> scope.reject("nope") }, name = "rej")
            }
        }
        assertTrue(client.users.query().queryArticles().firstOrError() is EntResult.Err)
        assertTrue(client.users.query().queryArticles().rawCountOrError() is EntResult.Err)
    }

    @Test
    fun `traversal-source rejection still throws on chained allOrThrow path`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                users(QueryInterceptor { scope, _ -> scope.reject("nope", code = "src") }, name = "rej")
            }
        }
        val ex = assertFailsWith<EntQueryRejectedException> {
            client.users.query().queryArticles().allOrThrow()
        }
        assertEquals("src", ex.queryRejected.code)
    }

    @Test
    fun `traversal-source rejection surfaces as rejected QueryPlan on chained explain`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                users(QueryInterceptor { scope, _ -> scope.reject("src nope", code = "src") }, name = "rej")
            }
        }
        val plan = client.users.query().queryArticles().explainAllOrThrow()
        assertTrue(plan.rejected)
        assertEquals("src", plan.rejectedCode)
        assertEquals("rej", plan.rejectedInterceptor)
        // requireNotRejected preserves the original rejection metadata
        // (entity = User, operation = QUERY for EDGE_TRAVERSAL).
        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        assertEquals("User", ex.queryRejected.entity)
        assertEquals(EntOperation.QUERY, ex.queryRejected.operation)
    }

    // ---------- traversal-source annotations carry forward ----------

    @Test
    fun `traversal-source annotations surface on terminal QueryPlan`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        val plan = client.users.query().queryArticles().explainAllOrThrow()
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
            privacyContext { PrivacyContext(Viewer.System) }
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
        val plan = client.users.query().queryArticles().explainAllOrThrow()
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
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                articles(
                    QueryInterceptor { _, _ -> fired = true },
                    name = "article-edge-observer",
                )
            }
        }
        // User.articles.has() — HasEdge("articles") added by caller.
        // No traversal context, no extraStructural, so skipWalk is
        // empty — the walker must process it and fire Article.interceptors.
        client.users.query { where(User.articles.exists()) }.allOrThrow()
        assertTrue(fired, "Article EDGE_PREDICATE interceptor should fire for the caller's User.articles.has()")
    }

    // ---------- recursion guard ----------

    @Test
    fun `edge-predicate interceptor cycle trips the recursion guard`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        // Should throw a clear IllegalStateException rather than
        // a StackOverflowError.
        val ex = assertFailsWith<IllegalStateException> {
            client.users.query().allOrThrow()
        }
        assertTrue(
            ex.message!!.contains("edge-predicate interceptor recursion exceeded depth"),
            "expected clear recursion-guard message, got: ${ex.message}",
        )
        // Sanity: the cap is the documented constant.
        assertEquals(32, InterceptorEngine.EDGE_PREDICATE_MAX_DEPTH)
    }

    // ---------- visible-explain matches runtime ----------

    @Test
    fun `explainVisibleExists with limit(0) shows limit 0, matching runtime`() {
        val driver = freshDriver()
        val client = EntClient(driver) { privacyContext { PrivacyContext(Viewer.System) } }

        // No interceptors so spec.limit = caller's limit(0) = 0.
        // Runtime: `minOf(1, 0 ?: 1) = 0` → driver.query with limit 0.
        // Explain must show the same.
        val plan = client.posts.query { limit(0) }.explainVisibleExists()
        assertNotNull(plan.root)
        // The plan's root description encodes the limit; we just
        // assert it doesn't have a non-zero LIMIT.
        val desc = plan.root.toString()
        assertTrue(
            desc.contains("LIMIT 0") || desc.contains("limit=0") || desc.contains("limit: 0"),
            "explainVisibleExists with limit(0) should show limit 0; was: $desc",
        )
    }

    @Test
    fun `explainRawExists with limit(0) shows limit 0, matching runtime`() {
        val driver = freshDriver()
        val client = EntClient(driver) { privacyContext { PrivacyContext(Viewer.System) } }
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
        val client = EntClient(driver) { privacyContext { PrivacyContext(Viewer.System) } }
        // Runtime rawExists calls driver.query with orderBy =
        // emptyList() and offset = spec.offset. Explain must
        // mirror exactly. Pre-fix: explain kept spec.orderBy and
        // forced offset = null.
        val plan = client.posts.query {
            orderBy(entkt.query.OrderField("title", entkt.query.OrderDirection.ASC))
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

    @Test
    fun `explainVisibleExists no-privacy fast path drops orderBy and preserves offset`() {
        val driver = freshDriver()
        // Post has no LOAD privacy → visibleExists goes through
        // the no-privacy fast path, which is identical to rawExists.
        // Explain on this path must drop orderBy + preserve offset
        // (pre-fix: explain kept spec.orderBy and forced offset = null).
        val client = EntClient(driver) { privacyContext { PrivacyContext(Viewer.System) } }
        val plan = client.posts.query {
            orderBy(entkt.query.OrderField("title", entkt.query.OrderDirection.ASC))
            offset(3)
        }.explainVisibleExists()
        assertNotNull(plan.root)
        val desc = plan.root.toString()
        assertTrue(
            !desc.contains("ORDER BY") && !desc.contains("orderBy=[OrderField"),
            "explainVisibleExists no-privacy path should drop orderBy; was: $desc",
        )
        assertTrue(
            desc.contains("OFFSET 3") || desc.contains("offset=3") || desc.contains("offset: 3"),
            "explainVisibleExists no-privacy path should preserve caller offset; was: $desc",
        )
    }

    // ---------- requireNotRejected preserves rejection ----------

    // ---------- Deferred traversal snapshots source at queryX() ----------

    @Test
    fun `mutating source query after queryX does not leak into target's terminal`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
        }
        // Two users, one article each. The post-queryX where on
        // the source MUST NOT affect what posts queryArticles
        // sees at terminal time — pre-snapshot the deferred
        // lambda would re-read `users.predicates` at terminal
        // time and include the late `name = "alice"` filter.
        val alice = client.users.create { name = "alice"; email = "alice@x" }.saveOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@x" }.saveOrThrow()
        client.articles.create { title = "alice-article"; authorId = alice.id }.saveOrThrow()
        client.articles.create { title = "bob-article"; authorId = bob.id }.saveOrThrow()

        val users = client.users.query()
        val articles = users.queryArticles()

        // Mutate the source AFTER queryX. If the lambda captures
        // `this` live, this where leaks into the bridge and
        // articles.allOrThrow() returns only "alice-article".
        // If the lambda captures a snapshot, this where is
        // invisible to the bridge.
        users.where(entkt.query.Predicate.Leaf("name", Op.EQ, "alice"))

        val result = articles.allOrThrow()
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
            privacyContext { PrivacyContext(Viewer.System) }
        }
        // Seed: two posts, one tag each (linked via the junction
        // table). The snapshot case returns [keeperTag]; a leak
        // of the post-queryX `where(title eq "intruder")` into
        // the bridge would intersect to zero matching posts
        // (id = keeper.id has title = "keeper", not "intruder")
        // and return []. So [keeperTag] vs [] cleanly
        // distinguishes the two implementations.
        val keeper = client.posts.create { title = "keeper" }.saveOrThrow()
        val intruder = client.posts.create { title = "intruder" }.saveOrThrow()
        val keeperTag = client.tags.create { name = "keeper-tag" }.saveOrThrow()
        val intruderTag = client.tags.create { name = "intruder-tag" }.saveOrThrow()
        client.postTags.create { postId = keeper.id; tagId = keeperTag.id }.saveOrThrow()
        client.postTags.create { postId = intruder.id; tagId = intruderTag.id }.saveOrThrow()

        val posts = client.posts.query { where(Post.id eq keeper.id) }
        val tags = posts.queryTags()

        // Mutate AFTER queryX. Pre-snapshot fix this leaked into
        // the bridge predicate; with the snapshot it does not.
        posts.where(entkt.query.Predicate.Leaf("title", Op.EQ, "intruder"))

        val result = tags.allOrThrow()
        assertEquals(
            listOf("keeper-tag"),
            result.map { it.name },
            "M2M queryX should snapshot source state at construction; post-queryX mutations must not leak into the bridge predicate",
        )
    }

    // ---------- firstVisibleOrNull eager-target denial propagates ----------

    @Test
    fun `firstVisibleOrNull throws on eager-target privacy denial (does not swallow as root invisibility)`() {
        val driver = freshDriver()
        // Article repo allows everything; Author repo denies the
        // specific viewer. firstVisibleOrNull on Article with an
        // eager `.withAuthor()` should THROW PrivacyDeniedException
        // when the article's author is denied — NOT silently skip
        // to the next article (the contract is visible filtering is
        // root-only, eager target denials are strict).
        val viewer = entkt.runtime.Viewer.User("denied-user")
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(object : entkt.runtime.EntityPolicy<Article, entkt.integrationtest.ent.ArticlePolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.ArticlePolicyScope) = scope.run {
                        privacy { load(entkt.integrationtest.ent.ArticleLoadPrivacyRule { entkt.runtime.PrivacyDecision.Allow }) }
                    }
                })
                users(object : entkt.runtime.EntityPolicy<User, entkt.integrationtest.ent.UserPolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.UserPolicyScope) = scope.run {
                        privacy { load(entkt.integrationtest.ent.UserLoadPrivacyRule { entkt.runtime.PrivacyDecision.Deny("user is hidden") }) }
                    }
                })
            }
        }
        // Seed via system viewer (bypasses policies).
        client.withPrivacyContext(PrivacyContext(entkt.runtime.Viewer.System)) { sys ->
            val u = sys.users.create { name = "denied-user"; email = "d@x" }.saveOrThrow()
            sys.articles.create { title = "with-denied-author"; authorId = u.id }.saveOrThrow()
        }

        assertFailsWith<entkt.runtime.PrivacyDeniedException> {
            client.articles.query().withAuthor().firstVisibleOrNull()
        }
    }

    @Test
    fun `firstVisibleOrNull still skips root-invisible rows silently`() {
        val driver = freshDriver()
        // Setup: two articles, one published, one draft. Article
        // privacy: deny the draft. firstVisibleOrNull should skip
        // the draft and return the published one — NOT throw and
        // NOT return null when a visible row exists past the
        // denied ones. Use a non-System viewer because System
        // bypasses privacy entirely (in which case the draft
        // would be returned and the test would defeat itself).
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(entkt.runtime.Viewer.User("u1")) }
            policies {
                articles(object : entkt.runtime.EntityPolicy<Article, entkt.integrationtest.ent.ArticlePolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.ArticlePolicyScope) = scope.run {
                        privacy {
                            load(entkt.integrationtest.ent.ArticleLoadPrivacyRule { ctx ->
                                if (ctx.entity.published) entkt.runtime.PrivacyDecision.Allow
                                else entkt.runtime.PrivacyDecision.Deny("draft")
                            })
                        }
                    }
                })
                users(object : entkt.runtime.EntityPolicy<User, entkt.integrationtest.ent.UserPolicyScope> {
                    override fun configure(scope: entkt.integrationtest.ent.UserPolicyScope) = scope.run {
                        privacy { load(entkt.integrationtest.ent.UserLoadPrivacyRule { entkt.runtime.PrivacyDecision.Allow }) }
                    }
                })
            }
        }
        // Seed via System viewer (bypasses privacy) so the create
        // path's post-write LOAD check on "draft" doesn't trip.
        client.withPrivacyContext(PrivacyContext(entkt.runtime.Viewer.System)) { sys ->
            val u = sys.users.create { name = "u"; email = "u@x" }.saveOrThrow()
            sys.articles.create { title = "draft"; published = false; authorId = u.id }.saveOrThrow()
            sys.articles.create { title = "published"; published = true; authorId = u.id }.saveOrThrow()
        }

        val result = client.articles.query().firstVisibleOrNull()
        assertNotNull(result)
        assertEquals("published", result.title)
    }

    // ---------- deleteMany routes through DELETE_CANDIDATES interceptors ----------

    @Test
    fun `deleteMany fires interceptors with DELETE_CANDIDATES operation`() {
        val driver = freshDriver()
        val ops = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { _, ctx -> ops.add(ctx.operation) },
                    name = "obs",
                )
            }
        }
        client.posts.create { title = "x" }.saveOrThrow()
        client.posts.create { title = "y" }.saveOrThrow()
        client.withTransaction { tx ->
            tx.posts.deleteMany()
        }
        assertEquals(listOf(ReadOperation.DELETE_CANDIDATES), ops)
    }

    @Test
    fun `deleteMany honors interceptor-added predicate on candidate fetch`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        client.posts.create { title = "scope-A" }.saveOrThrow()
        client.posts.create { title = "scope-B" }.saveOrThrow()

        val deleted: Int = client.withTransaction { tx -> tx.posts.deleteMany() }
        assertEquals(1, deleted)
        // Verify scope-B survived by inspecting the raw table
        // (byIdOrNull would also hit the interceptor's
        // `title = scope-A` filter and return null for the
        // survivor — that's correct uniform interceptor
        // behavior, but doesn't help us verify physical survival).
        val remainingRows = driver.query("posts", emptyList(), emptyList(), null, null)
        assertEquals(1, remainingRows.size)
        assertEquals("scope-B", remainingRows.single()["title"])
    }

    @Test
    fun `deleteMany interceptor rejection throws EntQueryRejectedException`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("no broad delete", code = "broad_delete_denied") },
                    name = "broad-delete-guard",
                )
            }
        }
        client.posts.create { title = "x" }.saveOrThrow()
        val ex = assertFailsWith<EntQueryRejectedException> {
            client.withTransaction { tx -> tx.posts.deleteMany() }
        }
        assertEquals("broad_delete_denied", ex.queryRejected.code)
        assertEquals(EntOperation.DELETE, ex.queryRejected.operation)
        assertEquals("Post", ex.queryRejected.entity)
    }

    @Test
    fun `deleteMany limit interceptor mutators are silent no-ops on DELETE_CANDIDATES`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        repeat(5) { i -> client.posts.create { title = "p$i" }.saveOrThrow() }

        val deleted: Int = client.withTransaction { tx -> tx.posts.deleteMany() }
        assertEquals(5, deleted, "limit clamp must be silent no-op on DELETE_CANDIDATES; all 5 rows should be deleted")
    }

    // ---------- Edge-predicate target annotations bubble up ----------

    @Test
    fun `edge-predicate target interceptor annotations surface on outer QueryPlan`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        }.explainAllOrThrow()
        assertEquals("true", plan.annotations["article-scoped"])
        assertEquals("via-has", plan.annotations["audit"])
    }

    @Test
    fun `outer-step annotation wins when edge-predicate target uses the same key`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        }.explainAllOrThrow()
        // Outer step (User) wins on key conflicts — matches the
        // traversal-source-vs-terminal direction (closer-to-caller
        // wins).
        assertEquals("from-user-outer", plan.annotations["step"])
    }

    @Test
    fun `requireNotRejected throws with the original entity and operation, not synthetic values`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope", code = "x") },
                    name = "post-rejector",
                )
            }
        }
        val plan = client.posts.query().explainAllOrThrow()
        val ex = assertFailsWith<EntQueryRejectedException> { plan.requireNotRejected() }
        // Pre-fix: ex.queryRejected.entity was synthesized as "<explain>".
        assertEquals("Post", ex.queryRejected.entity)
        assertEquals(EntOperation.QUERY, ex.queryRejected.operation)
        assertEquals("nope", ex.queryRejected.reason)
        assertEquals("x", ex.queryRejected.code)
        assertEquals("post-rejector", ex.queryRejected.interceptor)
    }
}
