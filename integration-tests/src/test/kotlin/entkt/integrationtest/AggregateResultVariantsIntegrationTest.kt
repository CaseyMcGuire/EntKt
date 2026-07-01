package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.result.EntError
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntResult
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.privacy.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * coverage: structured-result aggregate terminals
 * `rawCountOrError`, `visibleCountOrError`, `rawExistsOrError`,
 * `visibleExistsOrError`. Pins each variant's failure-mode mapping:
 *
 *  - happy path → `Ok(value)`
 *  - interceptor rejection → `Err(QueryRejected)`
 *  - driver failure → `Err(DriverFailure)` (covered by the
 *    classifier — exercised end-to-end in other suites)
 *
 * Visible-* variants additionally cover the eager-edge
 * `Err(PrivacyDenied)` path (no eager edges here on the count/exists
 * shape, so the regression is already covered by [allOrError]).
 *
 * Driver-failure tests are intentionally omitted on this surface;
 * the classifier path is identical to the *OrError variants on read
 * terminals which are already exercised end-to-end in
 * [QueryResultVariantsIntegrationTest].
 */
class AggregateResultVariantsIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    private fun freshClient(driver: PostgresDriver = freshDriver()): EntClient =
        EntClient(driver) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }

    // ---------- rawCountOrError ----------

    @Test
    fun `rawCountOrError happy path returns Ok with the count`() {
        val client = freshClient()
        client.posts.create { title = "a" }.saveOrThrow()
        client.posts.create { title = "b" }.saveOrThrow()

        val result = client.posts.query().rawCountOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(2L, (result as EntResult.Ok).value)
    }

    @Test
    fun `rawCountOrError honors interceptor predicate`() {
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
        client.posts.create { title = "yes" }.saveOrThrow()
        client.posts.create { title = "no" }.saveOrThrow()
        client.posts.create { title = "yes" }.saveOrThrow()

        val result = client.posts.query().rawCountOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(2L, (result as EntResult.Ok).value)
    }

    @Test
    fun `rawCountOrError surfaces interceptor rejection as Err(QueryRejected)`() {
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
        val result = client.posts.query().rawCountOrError()
        assertTrue(result is EntResult.Err)
        val err = (result as EntResult.Err).error
        assertTrue(err is EntError.QueryRejected, "expected QueryRejected, got $err")
        assertEquals("rc_rej", (err as EntError.QueryRejected).code)
        assertEquals(EntOperation.QUERY, err.operation)
    }

    // ---------- visibleCountOrError ----------

    @Test
    fun `visibleCountOrError happy path returns Ok with the count`() {
        val client = freshClient()
        client.posts.create { title = "a" }.saveOrThrow()
        client.posts.create { title = "b" }.saveOrThrow()

        val result = client.posts.query().visibleCountOrError()
        assertTrue(result is EntResult.Ok)
        assertEquals(2L, (result as EntResult.Ok).value)
    }

    @Test
    fun `visibleCountOrError surfaces interceptor rejection as Err(QueryRejected)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "vc-rejector",
                )
            }
        }
        val result = client.posts.query().visibleCountOrError()
        assertTrue(result is EntResult.Err)
        assertTrue((result as EntResult.Err).error is EntError.QueryRejected)
    }

    // ---------- rawExistsOrError ----------

    @Test
    fun `rawExistsOrError happy paths`() {
        val client = freshClient()

        val emptyResult = client.posts.query().rawExistsOrError()
        assertTrue(emptyResult is EntResult.Ok)
        assertEquals(false, (emptyResult as EntResult.Ok).value)

        client.posts.create { title = "x" }.saveOrThrow()

        val populatedResult = client.posts.query().rawExistsOrError()
        assertTrue(populatedResult is EntResult.Ok)
        assertEquals(true, (populatedResult as EntResult.Ok).value)
    }

    @Test
    fun `rawExistsOrError honors interceptor predicate`() {
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
        client.posts.create { title = "haystack" }.saveOrThrow()

        val miss = client.posts.query().rawExistsOrError()
        assertTrue(miss is EntResult.Ok)
        assertEquals(false, (miss as EntResult.Ok).value)

        client.posts.create { title = "needle" }.saveOrThrow()
        val hit = client.posts.query().rawExistsOrError()
        assertTrue(hit is EntResult.Ok)
        assertEquals(true, (hit as EntResult.Ok).value)
    }

    @Test
    fun `rawExistsOrError surfaces interceptor rejection as Err(QueryRejected)`() {
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
        val result = client.posts.query().rawExistsOrError()
        assertTrue(result is EntResult.Err)
        assertTrue((result as EntResult.Err).error is EntError.QueryRejected)
    }

    // ---------- visibleExistsOrError ----------

    @Test
    fun `visibleExistsOrError happy paths`() {
        val client = freshClient()

        val emptyResult = client.posts.query().visibleExistsOrError()
        assertTrue(emptyResult is EntResult.Ok)
        assertEquals(false, (emptyResult as EntResult.Ok).value)

        client.posts.create { title = "x" }.saveOrThrow()
        val populatedResult = client.posts.query().visibleExistsOrError()
        assertTrue(populatedResult is EntResult.Ok)
        assertEquals(true, (populatedResult as EntResult.Ok).value)
    }

    @Test
    fun `visibleExistsOrError surfaces interceptor rejection as Err(QueryRejected)`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    QueryInterceptor { scope, _ -> scope.reject("nope") },
                    name = "ve-rejector",
                )
            }
        }
        val result = client.posts.query().visibleExistsOrError()
        assertTrue(result is EntResult.Err)
        assertTrue((result as EntResult.Err).error is EntError.QueryRejected)
    }
}
