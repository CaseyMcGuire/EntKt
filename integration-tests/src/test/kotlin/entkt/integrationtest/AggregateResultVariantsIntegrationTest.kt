package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * coverage: canonical-result aggregate terminals `rawCount()` and
 * `rawExists()`, which return `ReadResult<Long>` / `ReadResult<Boolean>`
 * directly. Pins each terminal's mapping:
 *
 *  - happy path → `Success(value)`
 *  - interceptor predicates compose before the aggregate runs
 *  - interceptor rejection → `Failed(EntQueryRejectedException)` with
 *    direct `reason` / `code` / `interceptor` properties
 *
 * DatabaseDriver-failure tests are intentionally omitted on this surface; the
 * `Failed(exception)` capture path is identical to the read terminals
 * already exercised end-to-end in [QueryResultVariantsIntegrationTest].
 */
class AggregateResultVariantsIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    private fun freshClient(driver: PostgresDriver = freshDriver()): EntClient =
        EntClient(driver)

    // ---------- rawCount ----------

    @Test
    fun `rawCount happy path returns Success with the count`() {
        val client = freshClient()
        client.posts.create { title = "a" }.save(testViewerContext).getOrThrow()
        client.posts.create { title = "b" }.save(testViewerContext).getOrThrow()

        val result = client.posts.query().rawCount(testViewerContext)
        val ok = assertIs<ReadResult.Success<Long>>(result)
        assertEquals(2L, ok.value)
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
        client.posts.create { title = "yes" }.save(testViewerContext).getOrThrow()
        client.posts.create { title = "no" }.save(testViewerContext).getOrThrow()
        client.posts.create { title = "yes" }.save(testViewerContext).getOrThrow()

        val result = client.posts.query().rawCount(testViewerContext)
        val ok = assertIs<ReadResult.Success<Long>>(result)
        assertEquals(2L, ok.value)
    }

    @Test
    fun `rawCount surfaces interceptor rejection as Failed(EntQueryRejectedException)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope", code = "rc_rej") },
                    name = "rc-rejector",
                )
            }
        }
        val result = client.posts.query().rawCount(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val rejected = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("nope", rejected.reason)
        assertEquals("rc_rej", rejected.code)
        assertEquals("rc-rejector", rejected.interceptor)
    }

    // ---------- rawExists ----------

    @Test
    fun `rawExists happy paths`() {
        val client = freshClient()

        val emptyResult = client.posts.query().rawExists(testViewerContext)
        val emptyOk = assertIs<ReadResult.Success<Boolean>>(emptyResult)
        assertEquals(false, emptyOk.value)

        client.posts.create { title = "x" }.save(testViewerContext).getOrThrow()

        val populatedResult = client.posts.query().rawExists(testViewerContext)
        val populatedOk = assertIs<ReadResult.Success<Boolean>>(populatedResult)
        assertEquals(true, populatedOk.value)
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
        client.posts.create { title = "haystack" }.save(testViewerContext).getOrThrow()

        assertEquals(false, client.posts.query().rawExists(testViewerContext).getOrThrow())

        client.posts.create { title = "needle" }.save(testViewerContext).getOrThrow()
        assertEquals(true, client.posts.query().rawExists(testViewerContext).getOrThrow())
    }

    @Test
    fun `rawExists surfaces interceptor rejection as Failed(EntQueryRejectedException)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {

            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "re-rejector",
                )
            }
        }
        val result = client.posts.query().rawExists(testViewerContext)
        val failed = assertIs<ReadResult.Failed>(result)
        val rejected = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("nope", rejected.reason)
        assertEquals("re-rejector", rejected.interceptor)
    }
}
