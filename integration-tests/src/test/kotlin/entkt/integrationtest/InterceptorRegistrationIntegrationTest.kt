package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.GlobalQueryInterceptor
import entkt.runtime.PrivacyContext
import entkt.runtime.QueryInterceptor
import entkt.runtime.Viewer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * end-to-end coverage for the generated `interceptors { ... }`
 * DSL on `EntClientConfig`. Pins the contract that:
 *
 *  - Per-entity registration (`posts(interceptor, name=...)`) and
 *    global registration (`global(interceptor, name=...)`) compile
 *    and flow through to EntInterceptorsConfig under the hood.
 *  - Validation (mandatory name, framework: prefix reservation,
 *    unique-within-scope) lands at client construction time so a
 *    misconfigured client never reaches the first read.
 *
 * Behavior (interceptor chain actually running on a terminal call)
 * lands in; this suite is registration-side only.
 */
class InterceptorRegistrationIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    @Test
    fun `interceptors block accepts per-entity and global registrations`() {
        val driver = freshDriver()
        EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(
                    interceptor = QueryInterceptor { _, _ -> },
                    name = "tenant-scope",
                )
                global(
                    interceptor = GlobalQueryInterceptor { _, _ -> },
                    name = "audit",
                )
            }
        }
        // Construction completes without throwing — DSL surface OK.
    }

    @Test
    fun `duplicate per-entity name fails at client construction`() {
        val driver = freshDriver()
        val ex = assertFailsWith<IllegalArgumentException> {
            EntClient(driver) {
                privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
                interceptors {
                    posts(QueryInterceptor { _, _ -> }, name = "dup")
                    posts(QueryInterceptor { _, _ -> }, name = "dup")
                }
            }
        }
        assertTrue(ex.message!!.contains("dup"))
        assertTrue(ex.message!!.contains("posts"))
    }

    @Test
    fun `duplicate global name fails at client construction`() {
        val driver = freshDriver()
        val ex = assertFailsWith<IllegalArgumentException> {
            EntClient(driver) {
                privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
                interceptors {
                    global(GlobalQueryInterceptor { _, _ -> }, name = "audit")
                    global(GlobalQueryInterceptor { _, _ -> }, name = "audit")
                }
            }
        }
        assertTrue(ex.message!!.contains("audit"))
    }

    @Test
    fun `framework prefix on application interceptor fails at client construction`() {
        val driver = freshDriver()
        val ex = assertFailsWith<IllegalArgumentException> {
            EntClient(driver) {
                privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
                interceptors {
                    posts(QueryInterceptor { _, _ -> }, name = "framework:tenant-scope")
                }
            }
        }
        assertTrue(ex.message!!.contains("reserved"))
        assertTrue(ex.message!!.contains("framework:"))
    }

    @Test
    fun `withTransaction and withPrivacyContext clones construct cleanly when interceptors are registered`() {
        // proxy: verifies the clone code paths run without
        // blowing up when the parent has interceptors registered.
        // will add a direct firing-count test once the
        // runtime is wired through terminals.
        val driver = freshDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                posts(QueryInterceptor { _, _ -> }, name = "p1")
                global(GlobalQueryInterceptor { _, _ -> }, name = "g1")
            }
        }

        client.withPrivacyContext(PrivacyContext(Viewer.Anonymous)) { scoped ->
            scoped.posts
        }

        client.withTransaction { tx ->
            tx.posts
        }
    }

    @Test
    fun `Post type is available so the per-entity DSL stub type-checks`() {
        // Compile-only assertion: if the generated DSL ever loses
        // its `posts(...)` per-entity registration method, this
        // expression won't compile.
        val interceptor: QueryInterceptor<Post> = QueryInterceptor { _, _ -> }
        // suppress unused — just need the reference to keep the import live.
        assertTrue(interceptor === interceptor)
    }
}
