// Runtime contract tests for `EntInterceptorsConfig` exercise the
// raw `addEntity` / `addGlobal` / `entityInterceptorsFor` /
// `globals` methods directly. Those methods carry `@EntktInternal`
// because the string-keyed `scopeKey` pairing + the unchecked cast
// in `entityInterceptorsFor` form the soundness boundary that the
// typed `EntClientInterceptors` DSL would otherwise enforce. This
// test file opts in once at the file header — same pattern as the
// driver tests that fabricate edge predicates to exercise SQL
// rendering.
@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime
import entkt.runtime.query.FRAMEWORK_INTERCEPTOR_PREFIX
import entkt.runtime.query.GlobalQueryInterceptor
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.EntInterceptorsConfig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * coverage: the DSL-side [EntInterceptorsConfig] holder
 * generated `EntClientConfig` exposes through the `interceptors {
 * ... }` block. Validates the registration contract (mandatory
 * non-blank names, uniqueness scoped per-entity and per-globals,
 * framework: prefix reservation) so the codegen DSL surface can
 * rely on these failures landing eagerly at registration time
 * rather than at first read.
 */
class EntInterceptorsConfigTest {

    private class Post
    private class User

    private val noop: QueryInterceptor<Post> = QueryInterceptor { _, _ -> }
    private val noopUser: QueryInterceptor<User> = QueryInterceptor { _, _ -> }
    private val noopGlobal: GlobalQueryInterceptor = GlobalQueryInterceptor { _, _ -> }

    @Test
    fun `addEntity registers and entityInterceptorsFor returns in registration order`() {
        val config = EntInterceptorsConfig()
        val a: QueryInterceptor<Post> = QueryInterceptor { _, _ -> }
        val b: QueryInterceptor<Post> = QueryInterceptor { _, _ -> }
        config.addEntity("posts", "a", a)
        config.addEntity("posts", "b", b)

        val list = config.entityInterceptorsFor<Post>("posts")
        assertEquals(listOf("a", "b"), list.map { it.name })
        assertEquals(a, list[0].interceptor)
        assertEquals(b, list[1].interceptor)
    }

    @Test
    fun `entityInterceptorsFor returns empty list for unknown scope`() {
        val config = EntInterceptorsConfig()
        assertEquals(emptyList(), config.entityInterceptorsFor<Post>("does-not-exist"))
    }

    @Test
    fun `addGlobal registers and globals returns in registration order`() {
        val config = EntInterceptorsConfig()
        val a: GlobalQueryInterceptor = GlobalQueryInterceptor { _, _ -> }
        val b: GlobalQueryInterceptor = GlobalQueryInterceptor { _, _ -> }
        config.addGlobal("first", a)
        config.addGlobal("second", b)

        val all = config.globals()
        assertEquals(listOf("first", "second"), all.map { it.name })
        assertEquals(a, all[0].interceptor)
        assertEquals(b, all[1].interceptor)
    }

    @Test
    fun `duplicate name within same entity scope is rejected at registration`() {
        val config = EntInterceptorsConfig()
        config.addEntity("posts", "tenant-scope", noop)
        val ex = assertFailsWith<IllegalArgumentException> {
            config.addEntity("posts", "tenant-scope", noop)
        }
        assertTrue(ex.message!!.contains("tenant-scope"))
        assertTrue(ex.message!!.contains("posts"))
    }

    @Test
    fun `duplicate name across globals is rejected at registration`() {
        val config = EntInterceptorsConfig()
        config.addGlobal("audit", noopGlobal)
        val ex = assertFailsWith<IllegalArgumentException> {
            config.addGlobal("audit", noopGlobal)
        }
        assertTrue(ex.message!!.contains("audit"))
    }

    @Test
    fun `same name is allowed across different entity scopes`() {
        val config = EntInterceptorsConfig()
        config.addEntity("posts", "tenant-scope", noop)
        config.addEntity("users", "tenant-scope", noopUser)

        assertEquals(listOf("tenant-scope"), config.entityInterceptorsFor<Post>("posts").map { it.name })
        assertEquals(listOf("tenant-scope"), config.entityInterceptorsFor<User>("users").map { it.name })
    }

    @Test
    fun `same name is allowed across one entity scope and globals`() {
        val config = EntInterceptorsConfig()
        config.addEntity("posts", "audit", noop)
        config.addGlobal("audit", noopGlobal)

        assertEquals(listOf("audit"), config.entityInterceptorsFor<Post>("posts").map { it.name })
        assertEquals(listOf("audit"), config.globals().map { it.name })
    }

    @Test
    fun `blank name on addEntity is rejected`() {
        val config = EntInterceptorsConfig()
        val ex = assertFailsWith<IllegalArgumentException> {
            config.addEntity("posts", "  ", noop)
        }
        assertTrue(ex.message!!.contains("must not be blank"))
        assertTrue(ex.message!!.contains("posts"))
    }

    @Test
    fun `blank name on addGlobal is rejected`() {
        val config = EntInterceptorsConfig()
        val ex = assertFailsWith<IllegalArgumentException> {
            config.addGlobal("", noopGlobal)
        }
        assertTrue(ex.message!!.contains("must not be blank"))
        assertTrue(ex.message!!.contains("global"))
    }

    @Test
    fun `framework prefix on addEntity is rejected`() {
        val config = EntInterceptorsConfig()
        val ex = assertFailsWith<IllegalArgumentException> {
            config.addEntity("posts", "framework:soft-delete", noop)
        }
        assertTrue(ex.message!!.contains("reserved"))
        assertTrue(ex.message!!.contains("framework:"))
    }

    @Test
    fun `framework prefix on addGlobal is rejected`() {
        val config = EntInterceptorsConfig()
        val ex = assertFailsWith<IllegalArgumentException> {
            config.addGlobal("framework:audit", noopGlobal)
        }
        assertTrue(ex.message!!.contains("reserved"))
    }

    @Test
    fun `FRAMEWORK_INTERCEPTOR_PREFIX is the documented constant`() {
        assertEquals("framework:", FRAMEWORK_INTERCEPTOR_PREFIX)
    }
}
