package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.ReadResult
import entkt.runtime.result.getOrThrow
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
 * Driver-failure tests are intentionally omitted on this surface; the
 * `Failed(exception)` capture path is identical to the read terminals
 * already exercised end-to-end in [QueryResultVariantsIntegrationTest].
 */
class AggregateResultVariantsIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    private fun freshClient(driver: PostgresDriver = freshDriver()): EntClient =
        EntClient(driver) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }

    // ---------- rawCount ----------

    @Test
    fun `rawCount happy path returns Success with the count`() {
        val client = freshClient()
        client.posts.create { title = "a" }.save().getOrThrow()
        client.posts.create { title = "b" }.save().getOrThrow()

        val result = client.posts.query().rawCount()
        val ok = assertIs<ReadResult.Success<Long>>(result)
        assertEquals(2L, ok.value)
    }

    @Test
    fun `rawCount honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "yes"))
                    },
                    name = "yes-only",
                )
            }
        }
        client.posts.create { title = "yes" }.save().getOrThrow()
        client.posts.create { title = "no" }.save().getOrThrow()
        client.posts.create { title = "yes" }.save().getOrThrow()

        val result = client.posts.query().rawCount()
        val ok = assertIs<ReadResult.Success<Long>>(result)
        assertEquals(2L, ok.value)
    }

    @Test
    fun `rawCount surfaces interceptor rejection as Failed(EntQueryRejectedException)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope", code = "rc_rej") },
                    name = "rc-rejector",
                )
            }
        }
        val result = client.posts.query().rawCount()
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

        val emptyResult = client.posts.query().rawExists()
        val emptyOk = assertIs<ReadResult.Success<Boolean>>(emptyResult)
        assertEquals(false, emptyOk.value)

        client.posts.create { title = "x" }.save().getOrThrow()

        val populatedResult = client.posts.query().rawExists()
        val populatedOk = assertIs<ReadResult.Success<Boolean>>(populatedResult)
        assertEquals(true, populatedOk.value)
    }

    @Test
    fun `rawExists honors interceptor predicate`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ ->
                        scope.addPredicate(Predicate.Leaf("title", Op.EQ, "needle"))
                    },
                    name = "needle-only",
                )
            }
        }
        client.posts.create { title = "haystack" }.save().getOrThrow()

        assertEquals(false, client.posts.query().rawExists().getOrThrow())

        client.posts.create { title = "needle" }.save().getOrThrow()
        assertEquals(true, client.posts.query().rawExists().getOrThrow())
    }

    @Test
    fun `rawExists surfaces interceptor rejection as Failed(EntQueryRejectedException)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "re-rejector",
                )
            }
        }
        val result = client.posts.query().rawExists()
        val failed = assertIs<ReadResult.Failed>(result)
        val rejected = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("nope", rejected.reason)
        assertEquals("re-rejector", rejected.interceptor)
    }
}
