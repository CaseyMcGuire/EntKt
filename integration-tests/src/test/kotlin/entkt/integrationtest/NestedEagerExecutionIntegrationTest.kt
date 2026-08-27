package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyItem
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleQuery
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntPrivacyReadClient
import entkt.integrationtest.ent.Group
import entkt.integrationtest.ent.GroupPolicyScope
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyItem
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.query.Op
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.query.EdgeStep
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.SelectedEdgeStep
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the set-based eager executor contract from phase 1 of the
 * set-based eager graph loader RFC
 * (`docs/possible-features/query/set-based-eager-graph-loader.md`)
 * with query counts and callback traces:
 *
 * - One nested driver query and one nested EAGER_LOAD interceptor
 *   pass per LOGICAL EDGE STEP — never once per populated parent
 *   group. The nested pass receives the retained targets flattened
 *   in effective target order and deduplicated by target ID at
 *   first occurrence; its structural IN predicate holds that
 *   ordered distinct union.
 * - Nested-loaded copies are the canonical targets for the step:
 *   every parent association is rebuilt from them, and a target
 *   shared by several parents is nested-loaded once while staying
 *   attached to every source.
 * - Nested privacy rules receive one ordered distinct union batch
 *   per logical edge step; a strict denial projects the first
 *   denied target from that one batch and reports the full path.
 * - Sibling edges execute depth-first in schema-declaration order
 *   regardless of `load{Name}` call order; a descendant failure
 *   under an earlier sibling stops later siblings' callbacks and
 *   queries.
 * - A configured eager path with empty inputs still gets exactly
 *   one interceptor pass (with an empty structural IN) while
 *   driver reads stay data-gated and no privacy rule sees an
 *   empty batch.
 * - belongsTo, hasOne, hasMany, and manyToMany all run through the
 *   same set-based shape.
 *
 * The effective-ordering contract is pinned separately in
 * [EagerEffectiveOrderIntegrationTest].
 */
class NestedEagerExecutionIntegrationTest : PostgresTestBase() {

    private fun bypassClient(recording: RecordingDriver): EntClient =
        EntClient(recording)

    /** The structural `field IN (...)` values of an eager subquery's shape. */
    private fun structuralInValues(predicates: List<Predicate<*>>, field: String): List<Any?> =
        (
            predicates
                .filterIsInstance<Predicate.Leaf<*>>()
                .single { it.field == field && it.op == Op.IN }
                .value as List<*>
            ).toList()

    // Fail-closed fixtures: entities on the read path that aren't the
    // privacy subject still need an explicit load-allow under a
    // non-bypass viewer.
    private fun openGroups() = object : EntityPolicy<Group, GroupPolicyScope> {
        override fun configure(scope: GroupPolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    private fun openUsers() = object : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    private fun openArticles() = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run { privacy { load(allowAll) } }
    }

    // ---- one nested pass per logical edge step ----

    @Test
    fun `nested eager queries under a has-many run once per configured path, not once per parent group`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = bypassClient(recording)
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val b = client.users.create { name = "B"; email = "b@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val c = client.users.create { name = "C"; email = "c@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.users.create { name = "D"; email = "d@example.com" }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "a2"; authorId = a.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "b1"; authorId = b.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "c1"; authorId = c.id }.save(testViewerContext).getOrThrow()
        recording.reset()

        client.users.query { loadArticles { loadAuthor() } }.all(testViewerContext).getOrThrow()

        // One root query, one batched articles query, and ONE nested
        // author query for the union of all three populated parent
        // groups. The query count is bounded by configured eager
        // paths, never by how many parents have data.
        assertEquals(
            listOf(
                "query:users",
                "queryDirectToMany:articles",
                "query:users",
            ),
            recording.calls,
        )
    }

    @Test
    fun `the nested pass sees the ordered distinct union of every parent group's structural values`() {
        val driver = resetAndDriver()
        val nestedPasses = mutableListOf<Pair<List<EdgeStep>, List<Any?>>>()
        val nestedContexts = mutableListOf<ViewerContext>()
        val rootContext = testBypassContext("test")
        val client = EntClient(driver) {

            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            nestedPasses.add(
                                ctx.path to structuralInValues(scope.shape.predicates, "id"),
                            )
                            nestedContexts.add(ctx.viewerContext)
                        }
                    },
                    name = "nested-author-observer",
                )
            }
        }
        val alice = client.users.create { name = "alice"; email = "alice@example.com" }.saveAndLoad(rootContext).getOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@example.com" }.saveAndLoad(rootContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = alice.id }.save(rootContext).getOrThrow()
        client.articles.create { title = "a2"; authorId = alice.id }.save(rootContext).getOrThrow()
        client.articles.create { title = "a0"; authorId = bob.id }.save(rootContext).getOrThrow()

        client.users.query {
            loadArticles {
                orderBy(Article.title.asc())
                loadAuthor()
            }
        }.all(rootContext).getOrThrow()

        // Exactly one nested pass for the step, carrying the union of
        // both groups' author FK values in effective target order
        // with duplicates removed at first occurrence: bob first —
        // his article "a0" sorts before alice's — even though alice
        // was created first and has the lower id, and alice once
        // despite authoring two articles.
        val nestedPath = listOf(
            EdgeStep(User::class, "articles", Article::class),
            EdgeStep(Article::class, "author", User::class),
        )
        assertEquals(listOf(nestedPath), nestedPasses.map { it.first })
        assertEquals(listOf(listOf<Any?>(bob.id, alice.id)), nestedPasses.map { it.second })
        // The terminal-captured ViewerContext instance reaches the
        // nested pass unchanged — never a copy or a re-sampled
        // provider value.
        assertSame(rootContext, nestedContexts.single())
    }

    @Test
    fun `nested eager queries under a many-to-many run once per configured path`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = bypassClient(recording)
        val g1 = client.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "g2" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val u2 = client.users.create { name = "U2"; email = "u2@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val shared = client.users.create { name = "S"; email = "s@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = shared.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = u2.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = shared.id; role = "member" }.save(testViewerContext).getOrThrow()
        recording.reset()

        client.groups.query { loadUsers { loadArticles() } }.all(testViewerContext).getOrThrow()

        // One junction query, one target query, and ONE nested
        // articles query for the union of both source groups — the
        // shared member no longer multiplies nested work.
        assertEquals(
            listOf(
                "query:groups",
                "query:memberships",
                "query:users",
                "queryDirectToMany:articles",
            ),
            recording.calls,
        )
    }

    @Test
    fun `the nested pass under a many-to-many sees the deduplicated union of every source group's members`() {
        val driver = resetAndDriver()
        val nestedPasses = mutableListOf<List<Any?>>()
        val client = EntClient(driver) {

            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            nestedPasses.add(structuralInValues(scope.shape.predicates, "author_id"))
                        }
                    },
                    name = "nested-articles-observer",
                )
            }
        }
        val g1 = client.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "g2" }.saveAndLoad(testViewerContext).getOrThrow()
        val a = client.users.create { name = "a-member"; email = "am@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val b = client.users.create { name = "b-member"; email = "bm@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val z = client.users.create { name = "z-shared"; email = "zs@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = a.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = b.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = z.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = z.id; role = "member" }.save(testViewerContext).getOrThrow()

        client.groups.query {
            loadUsers {
                orderBy(User.name.asc())
                loadArticles()
            }
        }.all(testViewerContext).getOrThrow()

        // One nested pass whose structural IN holds every member of
        // every source group, in effective target order, with the
        // member shared by both groups appearing exactly once.
        assertEquals(listOf(listOf<Any?>(a.id, b.id, z.id)), nestedPasses)
    }

    @Test
    fun `a shared many-to-many target is nested-loaded once and every source receives the same canonical copy`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = bypassClient(recording)
        val g1 = client.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "g2" }.saveAndLoad(testViewerContext).getOrThrow()
        val shared = client.users.create { name = "S"; email = "s@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = shared.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = shared.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "s1"; authorId = shared.id }.save(testViewerContext).getOrThrow()
        recording.reset()

        val groups = client.groups.query { loadUsers { loadArticles() } }.all(testViewerContext).getOrThrow()

        // The shared member was decoded and nested-loaded once —
        // exactly one nested articles query — and both groups hold
        // the SAME canonical nested-loaded copy, articles edge
        // populated in each.
        assertEquals(1, recording.calls.count { it == "queryDirectToMany:articles" })
        val inG1 = groups.single { it.name == "g1" }.edges.users.requireLoaded().single()
        val inG2 = groups.single { it.name == "g2" }.edges.users.requireLoaded().single()
        assertSame(inG1, inG2)
        assertEquals(listOf("s1"), inG1.edges.articles.requireLoaded().map { it.title })
    }

    // ---- only retained targets reach the nested pass ----

    @Test
    fun `a filterVisible-denied target does not reach the nested pass`() {
        val viewerContext = ViewerContext(Viewer.User(999L))
        val nestedPasses = mutableListOf<List<Any?>>()
        val client = EntClient(resetAndDriver()) {

            policies {
                groups(openGroups())
                articles(openArticles())
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<EntPrivacyReadClient, UserLoadPrivacyItem> { _, batch ->
                                batch.decideEach {
                                    if (it.entity.name == "hidden") {
                                        PrivacyDecision.Deny("member hidden")
                                    } else {
                                        PrivacyDecision.Allow
                                    }
                                }
                            })
                        }
                    }
                })
            }
            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            nestedPasses.add(structuralInValues(scope.shape.predicates, "author_id"))
                        }
                    },
                    name = "nested-articles-observer",
                )
            }
        }
        val visibleId = run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val g1 = sys.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
            val visible = sys.users.create { name = "a-member"; email = "am@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            val hidden = sys.users.create { name = "hidden"; email = "h@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.memberships.create { groupId = g1.id; userId = visible.id; role = "member" }.save(testViewerContext).getOrThrow()
            sys.memberships.create { groupId = g1.id; userId = hidden.id; role = "member" }.save(testViewerContext).getOrThrow()
            sys.articles.create { title = "a1"; authorId = visible.id }.save(testViewerContext).getOrThrow()
            sys.articles.create { title = "h1"; authorId = hidden.id }.save(testViewerContext).getOrThrow()
            visible.id
        }

        val groups = client.groups.query {
            loadUsers { loadArticles() }.filterVisible()
        }.all(viewerContext).getOrThrow()

        // The denied member never triggers a nested read: its id is
        // absent from the nested structural IN, and its articles are
        // absent from the graph.
        assertEquals(listOf(listOf<Any?>(visibleId)), nestedPasses)
        val members = groups.single().edges.users.requireLoaded()
        assertEquals(listOf("a-member"), members.map { it.name })
        assertEquals(listOf("a1"), members.single().edges.articles.requireLoaded().map { it.title })
    }

    @Test
    fun `a target outside the per-parent window does not reach the nested pass`() {
        val nestedPasses = mutableListOf<List<Any?>>()
        val client = EntClient(resetAndDriver()) {

            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            nestedPasses.add(structuralInValues(scope.shape.predicates, "author_id"))
                        }
                    },
                    name = "nested-articles-observer",
                )
            }
        }
        val g1 = client.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
        val a = client.users.create { name = "a-member"; email = "am@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val b = client.users.create { name = "b-member"; email = "bm@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = a.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = b.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "b1"; authorId = b.id }.save(testViewerContext).getOrThrow()

        val groups = client.groups.query {
            loadUsers {
                orderBy(User.name.asc())
                limit(1)
                loadArticles()
            }
        }.all(testViewerContext).getOrThrow()

        // The member the window excludes never triggers a nested
        // read: only the retained member's id reaches the nested
        // structural IN.
        assertEquals(listOf(listOf<Any?>(a.id)), nestedPasses)
        val members = groups.single().edges.users.requireLoaded()
        assertEquals(listOf("a-member"), members.map { it.name })
        assertEquals(listOf("a1"), members.single().edges.articles.requireLoaded().map { it.title })
    }

    // ---- belongs-to and has-one run through the same shape ----

    @Test
    fun `nested eager loading under a belongs-to runs once for the batched target set`() {
        val recording = RecordingDriver(resetAndDriver())
        val nestedInValues = mutableListOf<List<Any?>>()
        val client = EntClient(recording) {

            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            nestedInValues.add(structuralInValues(scope.shape.predicates, "author_id"))
                        }
                    },
                    name = "nested-articles-observer",
                )
            }
        }
        val alice = client.users.create { name = "alice"; email = "alice@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val bob = client.users.create { name = "bob"; email = "bob@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = alice.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "a2"; authorId = alice.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "b1"; authorId = bob.id }.save(testViewerContext).getOrThrow()
        recording.reset()

        val articles = client.articles.query { loadAuthor { loadArticles() } }.all(testViewerContext).getOrThrow()

        assertEquals(
            listOf(
                "query:articles",
                "query:users",
                "queryDirectToMany:articles",
            ),
            recording.calls,
        )
        assertEquals(1, nestedInValues.size, "one nested pass for the batched target set")
        assertEquals(setOf<Any?>(alice.id, bob.id), nestedInValues.single().toSet())

        // Attachment uses the canonical nested-loaded copies: the
        // author attached to each article carries its own loaded
        // articles edge, and both of alice's articles hold the SAME
        // author instance.
        val byTitle = articles.associateBy { it.title }
        val aliceCopy = assertNotNull(byTitle.getValue("a1").edges.author.requireLoaded())
        assertEquals(listOf("a1", "a2"), aliceCopy.edges.articles.requireLoaded().map { it.title })
        assertSame(aliceCopy, byTitle.getValue("a2").edges.author.requireLoaded())
        val bobCopy = assertNotNull(byTitle.getValue("b1").edges.author.requireLoaded())
        assertEquals(listOf("b1"), bobCopy.edges.articles.requireLoaded().map { it.title })
    }

    @Test
    fun `a has-one eager load batches, nests once, and attaches a single nullable target`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = bypassClient(recording)
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val b = client.users.create { name = "B"; email = "b@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.users.create { name = "C"; email = "c@example.com" }.save(testViewerContext).getOrThrow()
        client.profiles.create { bio = "a-bio"; ownerId = a.id }.save(testViewerContext).getOrThrow()
        client.profiles.create { bio = "b-bio"; ownerId = b.id }.save(testViewerContext).getOrThrow()
        recording.reset()

        val users = client.users.query { loadProfile { loadOwner() } }.all(testViewerContext).getOrThrow()

        // Grouped like has-many — one batched profiles query, ONE
        // nested owner query for both groups' union — but attached
        // as a single nullable value: the profile-less user gets
        // Loaded(null), never Unloaded.
        assertEquals(
            listOf(
                "query:users",
                "query:profiles",
                "query:users",
            ),
            recording.calls,
        )
        val byName = users.associateBy { it.name }
        assertEquals("a-bio", byName.getValue("A").edges.profile.requireLoaded()?.bio)
        assertEquals(a.id, byName.getValue("A").edges.profile.requireLoaded()?.edges?.owner?.requireLoaded()?.id)
        assertEquals("b-bio", byName.getValue("B").edges.profile.requireLoaded()?.bio)
        assertNull(byName.getValue("C").edges.profile.requireLoaded())
    }

    @Test
    fun `a positive offset empties a has-one window without a driver fetch`() {
        val recording = RecordingDriver(resetAndDriver())
        var profileEagerPasses = 0
        val client = EntClient(recording) {

            interceptors {
                profiles(
                    QueryInterceptor { _, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) profileEagerPasses++
                    },
                    name = "profile-pass-counter",
                )
            }
        }
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.profiles.create { bio = "a-bio"; ownerId = a.id }.save(testViewerContext).getOrThrow()
        recording.reset()

        val users = client.users.query { loadProfile { offset(1) } }.all(testViewerContext).getOrThrow()

        // The unique inverse guarantees at most one row per source,
        // so offset(1) steps past the only candidate: no target
        // fetch, Loaded(null) — while the interceptor pass still
        // fires exactly once.
        assertEquals(1, profileEagerPasses)
        assertNull(users.single().edges.profile.requireLoaded())
        assertEquals(listOf("query:users"), recording.calls)
    }

    // ---- depth-first precedence in schema-declaration order ----

    @Test
    fun `a nested pass completes before any later sibling edge starts`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = bypassClient(recording)
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val b = client.users.create { name = "B"; email = "b@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "b1"; authorId = b.id }.save(testViewerContext).getOrThrow()
        recording.reset()

        // Selected in reverse declaration order: User declares
        // articles, then directories, then groups.
        client.users.query {
            loadGroups()
            loadDirectories()
            loadArticles { loadAuthor() }
        }.all(testViewerContext).getOrThrow()

        // Execution is depth-first in schema-declaration order, not
        // call order: articles and its single set-batched nested
        // author query complete before the directories sibling
        // reaches the driver, and the groups junction runs last.
        // Directories issues its target fetch (parents exist) and
        // merely matches nothing; the groups junction discovers no
        // target IDs, so only its target query is skipped.
        assertEquals(
            listOf(
                "query:users",
                "queryDirectToMany:articles",
                "query:users",
                "queryDirectToMany:legacy_people_tbl",
                "query:memberships",
            ),
            recording.calls,
        )
    }

    @Test
    fun `a rejection under an earlier sibling's nested pass stops later sibling callbacks and queries`() {
        val recording = RecordingDriver(resetAndDriver())
        var directoryInterceptorFires = 0
        val client = EntClient(recording) {

            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            scope.reject("no nested authors", code = "nested_rej")
                        }
                    },
                    name = "nested-author-rejector",
                )
                people(
                    QueryInterceptor { _, _ -> directoryInterceptorFires++ },
                    name = "directory-observer",
                )
            }
        }
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()
        recording.reset()

        val result = client.users.query {
            loadArticles { loadAuthor() }
            loadDirectories()
        }.all(testViewerContext)

        // The descendant rejection under articles fails the terminal
        // before the directories sibling runs any callback or query.
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("nested_rej", ex.code)
        assertEquals("nested-author-rejector", ex.interceptor)
        assertEquals("User", ex.entityType)
        assertEquals(0, directoryInterceptorFires, "later siblings must see no callback after a descendant failure")
        assertEquals(listOf("query:users", "queryDirectToMany:articles"), recording.calls)
    }

    @Test
    fun `a rejection under a many-to-many's nested pass stops later nested siblings`() {
        val recording = RecordingDriver(resetAndDriver())
        var directoryInterceptorFires = 0
        val client = EntClient(recording) {

            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            scope.reject("no nested articles", code = "nested_articles_rej")
                        }
                    },
                    name = "nested-articles-rejector",
                )
                people(
                    QueryInterceptor { _, _ -> directoryInterceptorFires++ },
                    name = "directory-observer",
                )
            }
        }
        val g1 = client.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "g2" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val u2 = client.users.create { name = "U2"; email = "u2@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "member" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = u2.id; role = "member" }.save(testViewerContext).getOrThrow()
        recording.reset()

        // Under the M2M, User declares articles before directories, so
        // the union pass's nested articles rejection fires before the
        // directories sibling runs a callback or query.
        val result = client.groups.query {
            loadUsers {
                loadArticles()
                loadDirectories()
            }
        }.all(testViewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("nested_articles_rej", ex.code)
        assertEquals("Article", ex.entityType)
        assertEquals(0, directoryInterceptorFires, "later nested siblings must see no callback after the rejection")
        assertEquals(listOf("query:groups", "query:memberships", "query:users"), recording.calls)
    }

    @Test
    fun `a junction driver failure precedes the target interceptor pass`() {
        val boom = IllegalStateException("junction storage unavailable")
        val real = resetAndDriver()
        val failingJunction = object : DatabaseDriver by real {
            override fun query(
                table: String,
                predicates: List<Predicate<*>>,
                orderBy: List<OrderField<*>>,
                limit: Int?,
                offset: Int?,
            ): List<Map<String, Any?>> =
                if (table == "memberships") throw boom
                else real.query(table, predicates, orderBy, limit, offset)
        }
        var userInterceptorFires = 0
        val client = EntClient(failingJunction) {

            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            userInterceptorFires++
                            scope.reject("should never be reached", code = "unreachable")
                        }
                    },
                    name = "target-rejector",
                )
            }
        }
        client.groups.create { name = "g1" }.save(testViewerContext).getOrThrow()

        val result = client.groups.query { loadUsers() }.all(testViewerContext)

        // Association discovery completes before the target
        // interceptor pass, so the junction I/O failure is the
        // observed error — the rejecting interceptor never ran.
        val failed = assertIs<ReadResult.Failed>(result)
        assertSame(boom, failed.exception)
        assertEquals(0, userInterceptorFires, "the target interceptor must not run after a junction failure")
    }

    @Test
    fun `a malformed null junction target stays visible to the interceptor but never becomes an association`() {
        val passes = mutableListOf<List<Any?>>()
        val client = EntClient(resetAndDriver()) {

            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            passes.add(structuralInValues(scope.shape.predicates, "id"))
                        }
                    },
                    name = "user-pass-observer",
                )
            }
        }
        val g1 = client.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
        val a = client.users.create { name = "a-member"; email = "am@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = a.id; role = "member" }.save(testViewerContext).getOrThrow()
        // Malformed row: a junction membership with no target user.
        client.memberships.create { groupId = g1.id; role = "dangling" }.save(testViewerContext).getOrThrow()

        val groups = client.groups.query { loadUsers() }.all(testViewerContext).getOrThrow()

        // The interceptor's structural predicate exposes the complete
        // discovered target-value list — the malformed null included —
        // but the null never becomes a storage match or an association.
        assertEquals(setOf<Any?>(a.id, null), passes.single().toSet())
        assertEquals(
            listOf("a-member"),
            groups.single().edges.users.requireLoaded().map { it.name },
        )
    }

    // ---- context and child-query state cannot be corrupted ----

    @Test
    fun `mutating an exposed context path cannot corrupt later interceptors or descendant steps`() {
        val observedPaths = mutableListOf<List<EdgeStep>>()
        val nestedPaths = mutableListOf<List<EdgeStep>>()
        val client = EntClient(resetAndDriver()) {

            interceptors {
                articles(
                    QueryInterceptor { _, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            // Hostile: the unmodifiable snapshot throws
                            // instead of letting the shared context be
                            // corrupted for everyone after this point.
                            runCatching { (ctx.path as? MutableList<*>)?.clear() }
                        }
                    },
                    name = "hostile-path-clearer",
                )
                articles(
                    QueryInterceptor { _, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) observedPaths.add(ctx.path)
                    },
                    name = "path-observer",
                )
                users(
                    QueryInterceptor { _, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) nestedPaths.add(ctx.path)
                    },
                    name = "nested-path-observer",
                )
            }
        }
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()

        client.users.query { loadArticles { loadAuthor() } }.all(testViewerContext).getOrThrow()

        val articlesStep = listOf(EdgeStep(User::class, "articles", Article::class))
        val nestedStep = articlesStep + EdgeStep(Article::class, "author", User::class)
        assertEquals(listOf(articlesStep), observedPaths, "the later interceptor sees the intact path")
        assertEquals(listOf(nestedStep), nestedPaths, "the descendant step sees the intact path")
    }

    @Test
    fun `a captured eager child query executed independently behaves as a root read`() {
        var captured: ArticleQuery? = null
        val contexts = mutableListOf<entkt.runtime.query.QueryContext>()
        val client = EntClient(resetAndDriver()) {

            interceptors {
                articles(
                    QueryInterceptor { _, ctx -> contexts.add(ctx) },
                    name = "context-observer",
                )
            }
        }
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()
        val query = client.users.query()
        query.loadArticles { captured = this }

        // Run the parent's terminal, which seeds the child's traversal
        // context for its eager step.
        query.all(testViewerContext).getOrThrow()
        contexts.clear()

        // Executed on its own, the captured child is what it was
        // constructed as: a root ArticleQuery — no stale eager
        // attribution from the parent's earlier runs.
        assertNotNull(captured).all(testViewerContext).getOrThrow()
        val ctx = contexts.single()
        assertEquals(ReadOperation.ALL, ctx.operation)
        assertEquals(emptyList(), ctx.path)
        assertNull(ctx.sourceEntity)
        assertNull(ctx.edgeName)
        assertEquals(Article::class, ctx.rootEntity)
    }

    // ---- an eager step's window is frozen with its spec ----

    @Test
    fun `mid-flight bounds mutation of a captured eager query affects only later executions`() {
        val recording = RecordingDriver(resetAndDriver())
        var captured: ArticleQuery? = null
        val client = EntClient(recording) {

            interceptors {
                articles(
                    QueryInterceptor { _, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) captured?.limit(1)
                    },
                    name = "mid-flight-bounds-mutator",
                )
            }
        }
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "a2"; authorId = a.id }.save(testViewerContext).getOrThrow()
        client.articles.create { title = "a3"; authorId = a.id }.save(testViewerContext).getOrThrow()
        val query = client.users.query()
        var target: ArticleQuery? = null
        query.loadArticles { target = this }
        captured = target

        // The step's window comes from the spec frozen before the
        // interceptor chain ran, so the mid-flight limit(1) cannot
        // shift what this execution loads.
        val first = query.all(testViewerContext).getOrThrow().single()
        assertEquals(3, first.edges.articles.requireLoaded().size)

        // The mutation persisted on the captured query, so — like any
        // other post-terminal mutation — it governs later executions.
        captured = null
        val second = query.all(testViewerContext).getOrThrow().single()
        assertEquals(1, second.edges.articles.requireLoaded().size)
    }

    // ---- nested privacy: one union batch per logical edge step ----

    @Test
    fun `nested privacy rules receive one ordered distinct union batch per logical edge step`() {
        val viewerContext = ViewerContext(Viewer.User(999L))
        val invocations = mutableListOf<List<String>>()
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                groups(openGroups())
                users(openUsers())
                articles(object : EntityPolicy<Article, ArticlePolicyScope> {
                    override fun configure(scope: ArticlePolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<EntPrivacyReadClient, ArticleLoadPrivacyItem> { _, batch ->
                                invocations += batch.map { it.entity.title }
                                batch.decideEach { PrivacyDecision.Allow }
                            })
                        }
                    }
                })
            }
        }
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val g1 = sys.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
            val g2 = sys.groups.create { name = "g2" }.saveAndLoad(testViewerContext).getOrThrow()
            val a = sys.users.create { name = "a-member"; email = "am@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            val b = sys.users.create { name = "b-member"; email = "bm@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.memberships.create { groupId = g1.id; userId = a.id; role = "member" }.save(testViewerContext).getOrThrow()
            sys.memberships.create { groupId = g2.id; userId = b.id; role = "member" }.save(testViewerContext).getOrThrow()
            sys.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()
            sys.articles.create { title = "b1"; authorId = b.id }.save(testViewerContext).getOrThrow()
        }

        client.groups.query { loadUsers { loadArticles() } }.all(viewerContext).getOrThrow()

        // Both source groups' members feed ONE nested articles pass,
        // so the LOAD-privacy rule sees one batch holding the union
        // in effective target order — not one batch per group.
        assertEquals(listOf(listOf("a1", "b1")), invocations)
    }

    @Test
    fun `a strict nested denial projects the first denial from the union batch with the full path`() {
        val viewerContext = ViewerContext(Viewer.User(999L))
        val driver = resetAndDriver()
        val client = EntClient(driver) {

            policies {
                groups(openGroups())
                users(openUsers())
                articles(object : EntityPolicy<Article, ArticlePolicyScope> {
                    override fun configure(scope: ArticlePolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<EntPrivacyReadClient, ArticleLoadPrivacyItem> { _, batch ->
                                batch.decideEach {
                                    if (it.entity.title.startsWith("secret")) {
                                        PrivacyDecision.Deny("article hidden")
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
        val deniedId = run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val g1 = sys.groups.create { name = "g1" }.saveAndLoad(testViewerContext).getOrThrow()
            val g2 = sys.groups.create { name = "g2" }.saveAndLoad(testViewerContext).getOrThrow()
            val a = sys.users.create { name = "a-member"; email = "am@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            val b = sys.users.create { name = "b-member"; email = "bm@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.memberships.create { groupId = g1.id; userId = a.id; role = "member" }.save(testViewerContext).getOrThrow()
            sys.memberships.create { groupId = g2.id; userId = b.id; role = "member" }.save(testViewerContext).getOrThrow()
            // The denied article belongs to the SECOND group's member
            // but is created first, so it leads the union's effective
            // order — under per-group recursion the first group's
            // clean article would have been evaluated first.
            val denied = sys.articles.create { title = "secret-b"; authorId = b.id }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "a1"; authorId = a.id }.save(testViewerContext).getOrThrow()
            denied.id
        }

        val result = client.groups.query { loadUsers { loadArticles() } }.all(viewerContext)

        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        val origin = assertIs<LoadDenialOrigin.SelectedEdgePath>(ex.origin)
        assertEquals(
            listOf(
                SelectedEdgeStep("Group", "users", "User"),
                SelectedEdgeStep("User", "articles", "Article"),
            ),
            origin.steps,
        )
        assertEquals(1, ex.denials.size)
        assertEquals(deniedId, ex.denials.single().entityKey.value)
    }

    // ---- empty passes: exactly one, with no driver or privacy work ----

    @Test
    fun `an empty root gives each configured eager path exactly one interceptor pass and no privacy work`() {
        val recording = RecordingDriver(resetAndDriver())
        val articlePasses = mutableListOf<List<Any?>>()
        val nestedAuthorPasses = mutableListOf<List<Any?>>()
        val articlePrivacyBatches = mutableListOf<List<String>>()
        val userPrivacyBatches = mutableListOf<List<String>>()
        val client = EntClient(recording) {

            policies {
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<EntPrivacyReadClient, UserLoadPrivacyItem> { _, batch ->
                                userPrivacyBatches += batch.map { it.entity.name }
                                batch.decideEach { PrivacyDecision.Allow }
                            })
                        }
                    }
                })
                articles(object : EntityPolicy<Article, ArticlePolicyScope> {
                    override fun configure(scope: ArticlePolicyScope) = scope.run {
                        privacy {
                            load(batchPrivacyRule<EntPrivacyReadClient, ArticleLoadPrivacyItem> { _, batch ->
                                articlePrivacyBatches += batch.map { it.entity.title }
                                batch.decideEach { PrivacyDecision.Allow }
                            })
                        }
                    }
                })
            }
            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            articlePasses.add(structuralInValues(scope.shape.predicates, "author_id"))
                        }
                    },
                    name = "article-pass-observer",
                )
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            nestedAuthorPasses.add(structuralInValues(scope.shape.predicates, "id"))
                        }
                    },
                    name = "nested-author-pass-observer",
                )
            }
        }
        run {
            val sys = client
            val testViewerContext = testBypassContext("seed")
            val author = sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
            sys.articles.create { title = "a1"; authorId = author.id }.save(testViewerContext).getOrThrow()
        }
        recording.reset()

        client.users.query {
            where(User.name eq "nobody")
            loadArticles { loadAuthor() }
        }.all(testViewerContext).getOrThrow()

        // The root matched nothing, so both configured eager paths run
        // exactly one interceptor pass with an EMPTY structural IN,
        // driver reads stay data-gated, and no privacy rule ever sees
        // an empty batch.
        assertEquals(listOf(emptyList<Any?>()), articlePasses, "one empty pass for the configured articles edge")
        assertEquals(listOf(emptyList<Any?>()), nestedAuthorPasses, "one empty pass for the configured nested author edge")
        assertEquals(emptyList(), articlePrivacyBatches, "an empty privacy batch invokes no privacy rule")
        assertEquals(emptyList(), userPrivacyBatches, "neither on the root nor on the nested path")
        assertEquals(listOf("query:users"), recording.calls, "only the root read reaches the driver")
    }

    @Test
    fun `an eager edge with parents but no rows still makes exactly one nested pass`() {
        val recording = RecordingDriver(resetAndDriver())
        val articlePasses = mutableListOf<List<Any?>>()
        val nestedAuthorPasses = mutableListOf<List<Any?>>()
        val client = EntClient(recording) {

            interceptors {
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            articlePasses.add(structuralInValues(scope.shape.predicates, "author_id"))
                        }
                    },
                    name = "article-pass-observer",
                )
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            nestedAuthorPasses.add(structuralInValues(scope.shape.predicates, "id"))
                        }
                    },
                    name = "nested-author-pass-observer",
                )
            }
        }
        val a = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val b = client.users.create { name = "B"; email = "b@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        recording.reset()

        client.users.query { loadArticles { loadAuthor() } }.all(testViewerContext).getOrThrow()

        // Both parents exist, so the articles fetch runs — it matches
        // nothing, leaving zero parent groups. The nested author edge
        // still gets exactly ONE empty pass — not one per parent — and
        // issues no query.
        assertEquals(1, articlePasses.size, "one pass for the configured articles edge")
        assertEquals(setOf<Any?>(a.id, b.id), articlePasses.single().toSet())
        assertEquals(listOf(emptyList<Any?>()), nestedAuthorPasses, "exactly one empty nested pass")
        assertEquals(listOf("query:users", "queryDirectToMany:articles"), recording.calls)
    }

    @Test
    fun `a many-to-many junction that discovers nothing still makes exactly one pass per configured path`() {
        val recording = RecordingDriver(resetAndDriver())
        val userPasses = mutableListOf<List<Any?>>()
        val nestedArticlePasses = mutableListOf<List<Any?>>()
        val client = EntClient(recording) {

            interceptors {
                users(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            userPasses.add(structuralInValues(scope.shape.predicates, "id"))
                        }
                    },
                    name = "user-pass-observer",
                )
                articles(
                    QueryInterceptor { scope, ctx ->
                        if (ctx.operation == ReadOperation.EAGER_LOAD) {
                            nestedArticlePasses.add(structuralInValues(scope.shape.predicates, "author_id"))
                        }
                    },
                    name = "nested-article-pass-observer",
                )
            }
        }
        client.groups.create { name = "g1" }.save(testViewerContext).getOrThrow()
        client.groups.create { name = "g2" }.save(testViewerContext).getOrThrow()
        recording.reset()

        client.groups.query { loadUsers { loadArticles() } }.all(testViewerContext).getOrThrow()

        // Parents exist, so the junction query runs — it discovers no
        // target IDs, so the M2M target edge and its nested child each
        // get exactly ONE empty interceptor pass and no target query.
        assertEquals(listOf(emptyList<Any?>()), userPasses, "one empty pass for the configured M2M edge")
        assertEquals(listOf(emptyList<Any?>()), nestedArticlePasses, "one empty pass for its nested child")
        assertEquals(listOf("query:groups", "query:memberships"), recording.calls)
    }
}
