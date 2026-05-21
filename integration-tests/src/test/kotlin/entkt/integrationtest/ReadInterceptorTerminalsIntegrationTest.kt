package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.AbortQueryRejected
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.EntQueryRejectedException
import entkt.runtime.EntResult
import entkt.runtime.GlobalQueryInterceptor
import entkt.runtime.InterceptScope
import entkt.runtime.PrivacyContext
import entkt.runtime.QueryInterceptor
import entkt.runtime.ReadOperation
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase 4 coverage: every core read terminal runs the per-entity +
 * global interceptor chain and honors the post-interceptor spec when
 * calling the driver. Pins:
 *
 *  - Interceptor predicates flow into the driver call (rawCount /
 *    rawExists / visibleCount / first* / all* / byId* see the
 *    narrowed result set).
 *  - QueryContext carries the right operation per terminal (BY_ID
 *    vs FIRST vs ALL vs RAW_COUNT vs etc.) and the right
 *    entOperation (LOAD for byId, QUERY for everything else).
 *  - reject() short-circuits the chain and surfaces:
 *      *OrError variants → Err(QueryRejected)
 *      *OrThrow / non-result reads → EntQueryRejectedException
 *  - Global interceptors run AFTER per-entity ones (registration-order
 *    + per-entity-first per the engine contract).
 *
 * Edge / eager / explain wiring lands in Phases 5 / 8 and is not
 * covered here.
 */
class ReadInterceptorTerminalsIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    private fun seedPosts(client: EntClient, titles: List<String>): List<Post> =
        titles.map { client.posts.create { title = it }.saveOrThrow() }

    // ---------- Interceptor wiring per terminal ----------

    @Test
    fun `allOrThrow honors interceptor predicate added via scope addPredicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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

        assertEquals(2, client.posts.query().allOrThrow().size)
    }

    @Test
    fun `allOrError honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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

        val result = client.posts.query().allOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(1, (result as EntResult.Ok).value.size)
    }

    @Test
    fun `firstOrNull honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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

        val post = client.posts.query().firstOrNull()
        assertNotNull(post)
        assertEquals("match", post.title)
    }

    @Test
    fun `rawCount honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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

        assertEquals(2L, client.posts.query().rawCount())
    }

    @Test
    fun `rawExists honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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

        assertFalse(client.posts.query().rawExists())

        seedPosts(client, listOf("needle"))
        assertTrue(client.posts.query().rawExists())
    }

    @Test
    fun `visibleCount honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "v"))
                    },
                    name = "v-only",
                )
            }
        }
        seedPosts(client, listOf("v", "v", "x"))

        assertEquals(2L, client.posts.query().visibleCount())
    }

    @Test
    fun `byIdOrNull honors interceptor predicate -- denying row that does not match`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "match"))
                    },
                    name = "title-filter",
                )
            }
        }
        val matching = client.posts.create { title = "match" }.saveOrThrow()
        val nonMatching = client.posts.create { title = "mismatch" }.saveOrThrow()

        // byId on the matching row should still find it (id + title match).
        val foundMatching = client.posts.byIdOrNull(matching.id)
        assertNotNull(foundMatching)
        assertEquals(matching.id, foundMatching.id)

        // byId on the non-matching row should return null because the
        // interceptor's `title = "match"` predicate excludes it.
        val foundNonMatching = client.posts.byIdOrNull(nonMatching.id)
        assertNull(foundNonMatching)
    }

    @Test
    fun `byIdOrError returns Err(NotFound) when interceptor narrows row out`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "kept"))
                    },
                    name = "title-filter",
                )
            }
        }
        val excluded = client.posts.create { title = "filtered out" }.saveOrThrow()

        val result = client.posts.byIdOrError(excluded.id)
        assertTrue(result is EntResult.Err, "expected Err, got $result")
        assertTrue((result as EntResult.Err).error is EntError.NotFound)
    }

    // ---------- Rejection paths per terminal ----------

    @Test
    fun `interceptor reject surfaces as EntQueryRejectedException on allOrThrow`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.reject("no broad scans", code = "broad_scan_denied")
                    },
                    name = "broad-scan-guard",
                )
            }
        }
        val ex = assertFailsWith<EntQueryRejectedException> { client.posts.query().allOrThrow() }
        assertEquals("no broad scans", ex.queryRejected.reason)
        assertEquals("broad_scan_denied", ex.queryRejected.code)
        assertEquals("broad-scan-guard", ex.queryRejected.interceptor)
        assertEquals("Post", ex.queryRejected.entity)
        assertEquals(EntOperation.QUERY, ex.queryRejected.operation)
    }

    @Test
    fun `interceptor reject surfaces as Err(QueryRejected) on allOrError`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.reject("nope", code = "test_code")
                    },
                    name = "rejector",
                )
            }
        }
        val result = client.posts.query().allOrError()
        assertTrue(result is EntResult.Err)
        val err = (result as EntResult.Err).error
        assertTrue(err is EntError.QueryRejected, "expected QueryRejected, got $err")
        val q = err as EntError.QueryRejected
        assertEquals("nope", q.reason)
        assertEquals("test_code", q.code)
        assertEquals("rejector", q.interceptor)
    }

    @Test
    fun `interceptor reject surfaces as EntQueryRejectedException on firstOrNull`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rej",
                )
            }
        }
        assertFailsWith<EntQueryRejectedException> { client.posts.query().firstOrNull() }
    }

    @Test
    fun `interceptor reject surfaces as EntQueryRejectedException on rawCount`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rej",
                )
            }
        }
        assertFailsWith<EntQueryRejectedException> { client.posts.query().rawCount() }
    }

    @Test
    fun `interceptor reject surfaces as EntQueryRejectedException on byIdOrNull`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("denied", code = "byid_denied") },
                    name = "byid-rejector",
                )
            }
        }
        val ex = assertFailsWith<EntQueryRejectedException> { client.posts.byIdOrNull(1L) }
        // BY_ID maps to entOperation = LOAD per the engine contract.
        assertEquals(EntOperation.LOAD, ex.queryRejected.operation)
        assertEquals("byid_denied", ex.queryRejected.code)
    }

    @Test
    fun `interceptor reject surfaces as Err(QueryRejected) on byIdOrError`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rej",
                )
            }
        }
        val result = client.posts.byIdOrError(1L)
        assertTrue(result is EntResult.Err)
        assertTrue((result as EntResult.Err).error is EntError.QueryRejected)
    }

    @Test
    fun `interceptor reject is NOT swallowed by visibleByIdOrNull`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "rej",
                )
            }
        }
        // visibleByIdOrNull's catch is for PrivacyDeniedException
        // only — rejection is a hard config-level signal that should
        // NOT be silently converted to null.
        assertFailsWith<EntQueryRejectedException> { client.posts.visibleByIdOrNull(1L) }
    }

    // ---------- Context propagation per terminal ----------

    @Test
    fun `QueryContext carries the right ReadOperation per terminal`() {
        val driver = freshDriver()
        val seen = mutableListOf<ReadOperation>()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { _, ctx -> seen.add(ctx.operation) },
                    name = "observer",
                )
            }
        }
        seedPosts(client, listOf("a"))

        client.posts.query().allOrThrow()
        client.posts.query().firstOrNull()
        client.posts.query().rawCount()
        client.posts.query().rawExists()
        client.posts.query().visibleCount()
        client.posts.query().visibleExists()
        client.posts.byIdOrNull(1L)

        assertEquals(
            listOf(
                ReadOperation.ALL,
                ReadOperation.FIRST,
                ReadOperation.RAW_COUNT,
                ReadOperation.RAW_EXISTS,
                ReadOperation.VISIBLE_COUNT,
                ReadOperation.VISIBLE_EXISTS,
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
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(QueryInterceptor { _, _ -> order.add("per-entity-1") }, name = "p1")
                posts(QueryInterceptor { _, _ -> order.add("per-entity-2") }, name = "p2")
                global(GlobalQueryInterceptor { _, _ -> order.add("global-1") }, name = "g1")
                global(GlobalQueryInterceptor { _, _ -> order.add("global-2") }, name = "g2")
            }
        }
        seedPosts(client, listOf("x"))

        client.posts.query().allOrThrow()

        assertEquals(
            listOf("per-entity-1", "per-entity-2", "global-1", "global-2"),
            order,
        )
    }

    // ---------- Global interceptor mutators ----------

    @Test
    fun `global rejectIfLimitGreaterThan surfaces as EntQueryRejectedException with max_limit_exceeded code`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
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
        val ex = assertFailsWith<EntQueryRejectedException> { client.posts.query().allOrThrow() }
        assertEquals("max_limit_exceeded", ex.queryRejected.code)
        assertEquals("max-limit", ex.queryRejected.interceptor)
    }

    @Test
    fun `interceptor requireLimitAtMost clamps the driver fetch on allOrThrow`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.requireLimitAtMost(2) },
                    name = "cap-at-2",
                )
            }
        }
        seedPosts(client, listOf("a", "b", "c", "d", "e"))

        assertEquals(2, client.posts.query().allOrThrow().size)
    }

    // ---------- Sanity: no interceptors registered → identity behavior ----------

    @Test
    fun `with no interceptors registered, terminals behave exactly as before`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            // intentionally no interceptors block
        }
        seedPosts(client, listOf("a", "b", "c"))

        assertEquals(3, client.posts.query().allOrThrow().size)
        assertEquals(3L, client.posts.query().rawCount())
        assertTrue(client.posts.query().rawExists())
        assertNotNull(client.posts.query().firstOrNull())
    }
}
