package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.query.GlobalQueryInterceptor
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.ReadResult
import entkt.runtime.result.visibleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * coverage: every core read terminal runs the per-entity +
 * global interceptor chain and honors the post-interceptor spec when
 * calling the driver. Pins:
 *
 *  - Interceptor predicates flow into the driver call (rawCount /
 *    rawExists / firstOrNull / all / findById see the narrowed
 *    result set).
 *  - QueryContext carries the right operation per terminal (BY_ID
 *    vs FIRST vs ALL vs RAW_COUNT vs RAW_EXISTS vs RAW_AGGREGATE).
 *  - reject() short-circuits the chain and surfaces as
 *    `ReadResult.Failed(EntQueryRejectedException)` on every data
 *    terminal; `getOrThrow()` throws the stored exception instance.
 *  - Global interceptors run AFTER per-entity ones (registration-order
 *    + per-entity-first per the engine contract).
 *
 * Edge and eager wiring is covered by separate focused tests.
 */
class ReadInterceptorTerminalsIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    private fun seedPosts(client: EntClient, titles: List<String>): List<Post> =
        titles.map { client.posts.create { title = it }.saveAndLoad(testViewerContext).getOrThrow() }

    // ---------- Interceptor wiring per terminal ----------

    @Test
    fun `all honors interceptor predicate added via scope addPredicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "keep"))
                    },
                    name = "title-filter",
                )
            }
        }
        seedPosts(client, listOf("keep", "drop", "keep", "drop"))

        assertEquals(2, client.posts.query().all(testViewerContext).getOrThrow().size)
    }

    @Test
    fun `all Success match honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "keep"))
                    },
                    name = "title-filter",
                )
            }
        }
        seedPosts(client, listOf("keep", "drop"))

        val result = client.posts.query().all(testViewerContext)
        val success = assertIs<ReadResult.Success<List<Post>>>(result)
        assertEquals(1, success.value.size)
    }

    @Test
    fun `firstOrNull honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "match"))
                    },
                    name = "title-filter",
                )
            }
        }
        seedPosts(client, listOf("nope", "match", "nope"))

        val post = client.posts.query().firstOrNull(testViewerContext).getOrThrow()
        assertNotNull(post)
        assertEquals("match", post.title)
    }

    @Test
    fun `rawCount honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "yes"))
                    },
                    name = "yes-only",
                )
            }
        }
        seedPosts(client, listOf("yes", "yes", "no"))

        assertEquals(2L, client.posts.query().rawCount(testViewerContext).getOrThrow())
    }

    @Test
    fun `rawExists honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "needle"))
                    },
                    name = "needle-only",
                )
            }
        }
        seedPosts(client, listOf("haystack", "haystack"))

        assertFalse(client.posts.query().rawExists(testViewerContext).getOrThrow())

        seedPosts(client, listOf("needle"))
        assertTrue(client.posts.query().rawExists(testViewerContext).getOrThrow())
    }

    @Test
    fun `findById honors interceptor predicate -- narrowed-out row reads as absence`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "match"))
                    },
                    name = "title-filter",
                )
            }
        }
        val matching = client.posts.create { title = "match" }.saveAndLoad(testViewerContext).getOrThrow()
        val nonMatching = client.posts.create { title = "mismatch" }.saveAndLoad(testViewerContext).getOrThrow()

        // findById on the matching row should still find it (id + title match).
        val foundMatching = client.posts.findById(testViewerContext, matching.id).getOrThrow()
        assertNotNull(foundMatching)
        assertEquals(matching.id, foundMatching.id)

        // findById on the non-matching row is authoritative absence —
        // Success(null) — because the interceptor's `title = "match"`
        // predicate excludes it. (There is no not-found error state.)
        val foundNonMatching = client.posts.findById(testViewerContext, nonMatching.id)
        val success = assertIs<ReadResult.Success<Post?>>(foundNonMatching)
        assertNull(success.value)
    }

    // ---------- Rejection paths per terminal ----------

    @Test
    fun `interceptor reject surfaces as Failed(EntQueryRejectedException) on all`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.reject("no broad scans", code = "broad_scan_denied")
                    },
                    name = "broad-scan-guard",
                )
            }
        }
        val result = client.posts.query().all(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("no broad scans", ex.reason)
        assertEquals("broad_scan_denied", ex.code)
        assertEquals("broad-scan-guard", ex.interceptor)
        assertEquals("Post", ex.entityType)
    }

    @Test
    fun `getOrThrow on a rejected all throws the stored exception instance`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.reject("nope", code = "test_code")
                    },
                    name = "rejector",
                )
            }
        }
        val result = client.posts.query().all(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val thrown = assertFailsWith<EntQueryRejectedException> { result.getOrThrow() }
        assertSame(failed.exception, thrown)
        assertEquals("test_code", thrown.code)
        assertEquals("rejector", thrown.interceptor)
    }

    @Test
    fun `interceptor reject surfaces as Failed(EntQueryRejectedException) on firstOrNull`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rej",
                )
            }
        }
        val failed = assertIs<ReadResult.Failed>(client.posts.query().firstOrNull(testViewerContext))
        assertIs<EntQueryRejectedException>(failed.exception)
    }

    @Test
    fun `interceptor reject surfaces as Failed(EntQueryRejectedException) on rawCount`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rej",
                )
            }
        }
        val failed = assertIs<ReadResult.Failed>(client.posts.query().rawCount(testViewerContext))
        assertIs<EntQueryRejectedException>(failed.exception)
    }

    @Test
    fun `interceptor reject surfaces as Failed(EntQueryRejectedException) on findById`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("denied", code = "byid_denied") },
                    name = "byid-rejector",
                )
            }
        }
        val failed = assertIs<ReadResult.Failed>(client.posts.findById(testViewerContext, 1L))
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("byid_denied", ex.code)
        assertEquals("byid-rejector", ex.interceptor)
        assertEquals("Post", ex.entityType)
    }

    @Test
    fun `interceptor reject is NOT masked by visibleOrNull`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rej",
                )
            }
        }
        // visibleOrNull maps only Failed(EntPrivacyDeniedException with
        // origin Root) to Success(null) — rejection is a hard
        // config-level signal that must NOT be silently converted to
        // absence.
        val projected = client.posts.findById(testViewerContext, 1L).visibleOrNull()
        val failed = assertIs<ReadResult.Failed>(projected)
        assertIs<EntQueryRejectedException>(failed.exception)
    }

    // ---------- Context propagation per terminal ----------

    @Test
    fun `QueryContext carries the right ReadOperation per terminal`() {
        val driver = freshDriver()
        val seen = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { _, ctx -> seen.add(ctx.operation) },
                    name = "observer",
                )
            }
        }
        seedPosts(client, listOf("a"))

        client.posts.query().all(testViewerContext).getOrThrow()
        client.posts.query().firstOrNull(testViewerContext).getOrThrow()
        client.posts.query().rawCount(testViewerContext).getOrThrow()
        client.posts.query().rawExists(testViewerContext).getOrThrow()
        client.posts.query().rawSum(testViewerContext, Post.id).getOrThrow()
        client.posts.findById(testViewerContext, 1L).getOrThrow()

        assertEquals(
            listOf(
                ReadOperation.ALL,
                ReadOperation.FIRST,
                ReadOperation.RAW_COUNT,
                ReadOperation.RAW_EXISTS,
                ReadOperation.RAW_AGGREGATE,
                ReadOperation.BY_ID,
            ),
            seen,
        )
    }

    // ---------- Per-entity then global ordering ----------

    @Test
    fun `per-entity interceptors run before globals in registration order`() {
        val driver = freshDriver()
        val order = mutableListOf<String>()
        val client = EntClient(driver) {

            interceptors {
                posts(QueryInterceptor { _, _ -> order.add("per-entity-1") }, name = "p1")
                posts(QueryInterceptor { _, _ -> order.add("per-entity-2") }, name = "p2")
                global(GlobalQueryInterceptor { _, _ -> order.add("global-1") }, name = "g1")
                global(GlobalQueryInterceptor { _, _ -> order.add("global-2") }, name = "g2")
            }
        }
        seedPosts(client, listOf("x"))

        client.posts.query().all(testViewerContext).getOrThrow()

        assertEquals(
            listOf("per-entity-1", "per-entity-2", "global-1", "global-2"),
            order,
        )
    }

    // ---------- Global interceptor mutators ----------

    @Test
    fun `global rejectIfLimitGreaterThan surfaces as Failed with max_limit_exceeded code`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                global(
                    GlobalQueryInterceptor { scope, _ ->
                        scope.rejectIfLimitGreaterThan(10) { "max 10 rows" }
                    },
                    name = "max-limit",
                )
            }
        }
        // No limit set → effective limit is null → exceeds 10.
        val failed = assertIs<ReadResult.Failed>(client.posts.query().all(testViewerContext))
        val ex = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("max_limit_exceeded", ex.code)
        assertEquals("max-limit", ex.interceptor)
    }

    @Test
    fun `interceptor requireLimitAtMost clamps the driver fetch on all`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.requireLimitAtMost(2) },
                    name = "cap-at-2",
                )
            }
        }
        seedPosts(client, listOf("a", "b", "c", "d", "e"))

        assertEquals(2, client.posts.query().all(testViewerContext).getOrThrow().size)
    }

    // ---------- Sanity: no interceptors registered → identity behavior ----------

    @Test
    fun `with no interceptors registered, terminals behave exactly as before`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            // intentionally no interceptors block
        }
        seedPosts(client, listOf("a", "b", "c"))

        assertEquals(3, client.posts.query().all(testViewerContext).getOrThrow().size)
        assertEquals(3L, client.posts.query().rawCount(testViewerContext).getOrThrow())
        assertTrue(client.posts.query().rawExists(testViewerContext).getOrThrow())
        assertNotNull(client.posts.query().firstOrNull(testViewerContext).getOrThrow())
    }
}
