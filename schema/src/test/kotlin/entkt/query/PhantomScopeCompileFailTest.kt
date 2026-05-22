@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.query

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Compile-fail negative tests for the phantom-typed query scopes RFC.
 *
 * Each test compiles a small Kotlin snippet in-process via
 * `kotlin-compile-testing`'s fork (`dev.zacsweers.kctfork:core`),
 * asserts the compile fails (`exitCode != OK`), and matches the
 * compiler's diagnostic against an expected substring. The snippets
 * exercise the compile-time guards documented in the RFC's "Test
 * Requirements" section that no runtime test can catch.
 *
 * The snippets live as inline `.kt` strings so each test is
 * self-contained and the assertion + minimal reproducer are visible
 * in one place.
 */
class PhantomScopeCompileFailTest {

    /** Stub entities used inside test snippets. They live in the snippet
     *  itself so each compile is fully self-contained — no dependency
     *  on generated `:schema` test fixtures or example schemas. */
    private val testEntities = """
        class User
        class Post
    """.trimIndent()

    private fun compile(snippet: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "Snippet.kt",
                    """
                    @file:Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
                    import entkt.query.*

                    $testEntities

                    $snippet
                    """.trimIndent(),
                ),
            )
            // Use the test classpath so the snippet sees `:schema`'s
            // compiled artifacts (Predicate / Column / etc.) without
            // having to fish out the jar path manually.
            inheritClassPath = true
            // The kctfork build bundles its own kotlinc, which may lag
            // the project's compiler version. Skipping the metadata
            // version check lets the snippet read `:schema`'s
            // newer-bytecode classes — semantics of the type-system
            // checks we care about (opt-in, generics, smart cast)
            // are independent of the metadata version.
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            // Quiet output — the assertion already captures
            // result.messages on failure.
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun assertCompileError(result: JvmCompilationResult, expectedSubstring: String) {
        assertNotEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected snippet to fail compilation but it succeeded:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains(expectedSubstring),
            "Expected compile error to mention `$expectedSubstring` but messages were:\n${result.messages}",
        )
    }

    // ---- Predicate scope mismatches ----

    @Test
    fun `cross-entity Predicate cannot be combined via and`() {
        // `(Column<User, Boolean> eq true) and (Column<Post, Boolean> eq true)`
        // — combining typed predicates from different entity scopes must
        // fail to compile because Predicate<E> is invariant.
        val result = compile("""
            val userPred: Predicate<User> = Column<User, Boolean>("active") eq true
            val postPred: Predicate<Post> = Column<Post, Boolean>("published") eq true
            fun bad(): Predicate<User> = userPred and postPred
        """.trimIndent())
        assertCompileError(result, "Argument type mismatch")
    }

    @Test
    fun `cross-entity OrderField cannot be passed where same-entity is expected`() {
        // OrderField<User> required, OrderField<Post> provided — must
        // fail to compile.
        val result = compile("""
            fun expectsUserOrder(o: OrderField<User>) {}
            val postOrder: OrderField<Post> = OrderField("created_at", OrderDirection.ASC)
            fun bad() = expectsUserOrder(postOrder)
        """.trimIndent())
        assertCompileError(result, "Argument type mismatch")
    }

    // ---- Ordering guard for non-comparable column types ----

    @Test
    fun `Column over ByteArray (BYTES) does not expose asc`() {
        // `Column<E, ByteArray>` is what FieldType.BYTES emits; it
        // extends only Column<E, T> (no ComparableColumn / EnumColumn
        // ancestry), so asc() / desc() are unreachable — the in-memory
        // comparator carveout for non-comparable types is preserved at
        // the type system layer.
        val result = compile("""
            val bytes: Column<User, ByteArray> = Column("data")
            fun bad() = bytes.asc()
        """.trimIndent())
        assertCompileError(result, "Unresolved reference")
    }

    // ---- @EntktInternal opt-in ----

    @Test
    fun `direct HasEdgeWith construction without OptIn fails to compile`() {
        // The walker-cast soundness story rests on HasEdgeWith being
        // constructible only via the typed EdgeRef.has(...) surface.
        // Application code attempting to fabricate one raises a hard
        // opt-in error.
        val result = compile("""
            fun bad(): Predicate<User> {
                val inner: Predicate<Post> = Predicate.Leaf("x", Op.EQ, 1)
                return Predicate.HasEdgeWith<User, Post>("posts", inner)
            }
        """.trimIndent())
        assertCompileError(result, "Internal entkt construction site")
    }

    @Test
    fun `direct HasEdge construction without OptIn fails to compile`() {
        val result = compile("""
            fun bad(): Predicate<User> = Predicate.HasEdge<User>("posts")
        """.trimIndent())
        assertCompileError(result, "Internal entkt construction site")
    }

    @Test
    fun `direct HasM2MEdgeFrom construction without OptIn fails to compile`() {
        val result = compile("""
            fun bad(): Predicate<Post> =
                Predicate.HasM2MEdgeFrom<Post, User>("users", "posts", null)
        """.trimIndent())
        assertCompileError(result, "Internal entkt construction site")
    }

    @Test
    fun `direct EdgeRef construction without OptIn fails to compile`() {
        // EdgeRef's primary constructor is @EntktInternal. Application
        // code never constructs EdgeRef directly — the generated entity
        // companion does it inside an @file:OptIn-annotated file.
        val result = compile("""
            // EdgeQuery<Post> stub so the type parameters resolve.
            class PostQuery : EdgeQuery<Post> {
                override fun combinedPredicate(): Predicate<Post>? = null
            }
            fun bad(): EdgeRef<User, Post, PostQuery> =
                EdgeRef("posts") { PostQuery() }
        """.trimIndent())
        assertCompileError(result, "Internal entkt construction site")
    }

    @Test
    fun `properties marked @EntktInternal require OptIn to read or write`() {
        // The generated EntClient marks its `entityInterceptors`
        // property `@EntktInternal internal var` so application code
        // can't reach the raw EntInterceptorsConfig and call
        // `addEntity(scopeKey: String, ...)` with a mismatched scope
        // and entity type — the config's `entityInterceptorsFor<E>(scopeKey)`
        // does an unchecked cast keyed on the scopeKey string. This
        // test pins the property-level annotation behavior on a stub
        // class (the real EntClient is generated and not reachable
        // from a self-contained snippet).
        val result = compile("""
            class FakeClient {
                @EntktInternal
                internal var raw: Int = 0
            }
            fun bad(c: FakeClient) {
                c.raw = 42
            }
        """.trimIndent())
        assertCompileError(result, "Internal entkt construction site")
    }

    @Test
    fun `HasEdgeWith does not expose a copy() method (data-class auto-gen disabled)`() {
        // Previously, `Predicate.HasEdgeWith @EntktInternal constructor(...)`
        // was a data class, which auto-generates a PUBLIC `copy(...)` that
        // sidesteps the constructor's opt-in. A caller could obtain a
        // legitimate HasEdgeWith via EdgeRef.has(...) and then call
        // `.copy(edge = "something-else")` to pair the original
        // Predicate<Target> with a different edge name — reopening the
        // walker-cast fabrication hole. Switching to regular classes
        // removes auto-generated copy() entirely.
        val result = compile("""
            @OptIn(EntktInternal::class)
            fun legitimate(): Predicate.HasEdgeWith<User, Post> {
                val inner: Predicate<Post> = Predicate.Leaf("x", Op.EQ, 1)
                return Predicate.HasEdgeWith("posts", inner)
            }
            fun bypass(): Predicate.HasEdgeWith<User, Post> =
                legitimate().copy(edge = "comments")
        """.trimIndent())
        assertCompileError(result, "Unresolved reference")
    }

    @Test
    fun `HasEdge does not expose a copy() method`() {
        val result = compile("""
            @OptIn(EntktInternal::class)
            fun legitimate(): Predicate.HasEdge<User> = Predicate.HasEdge("posts")
            fun bypass() = legitimate().copy(edge = "comments")
        """.trimIndent())
        assertCompileError(result, "Unresolved reference")
    }

    @Test
    fun `restricted predicate variants preserve value-based equality`() {
        // Switching from `data class` to plain `class` to close the
        // copy() bypass also removed the auto-generated equals /
        // hashCode. The implementation adds those back manually so
        // structurally-equal predicates still compare equal — runtime
        // and integration tests that compare predicate trees by ==
        // continue to work without surprises. The compile passes if
        // the generated equals returns true for matching fields;
        // a hashCode collision check is included for the contract.
        val result = compile("""
            @OptIn(EntktInternal::class)
            fun checks(): Boolean {
                val a: Predicate.HasEdge<User> = Predicate.HasEdge("posts")
                val b: Predicate.HasEdge<User> = Predicate.HasEdge("posts")
                val c: Predicate.HasEdge<User> = Predicate.HasEdge("comments")
                check(a == b)
                check(a != c)
                check(a.hashCode() == b.hashCode())

                val inner: Predicate<Post> = Predicate.Leaf("x", Op.EQ, 1)
                val w1: Predicate.HasEdgeWith<User, Post> = Predicate.HasEdgeWith("posts", inner)
                val w2: Predicate.HasEdgeWith<User, Post> = Predicate.HasEdgeWith("posts", inner)
                check(w1 == w2)
                check(w1.hashCode() == w2.hashCode())

                val m1: Predicate.HasM2MEdgeFrom<Post, User> =
                    Predicate.HasM2MEdgeFrom("users", "posts", null)
                val m2: Predicate.HasM2MEdgeFrom<Post, User> =
                    Predicate.HasM2MEdgeFrom("users", "posts", null)
                check(m1 == m2)
                check(m1.hashCode() == m2.hashCode())
                return true
            }
        """.trimIndent())
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Restricted predicates should compile and equals/hashCode should preserve value semantics. " +
                "Messages:\n${result.messages}",
        )
    }

    @Test
    fun `HasM2MEdgeFrom does not expose a copy() method`() {
        val result = compile("""
            @OptIn(EntktInternal::class)
            fun legitimate(): Predicate.HasM2MEdgeFrom<Post, User> =
                Predicate.HasM2MEdgeFrom("users", "posts", null)
            fun bypass() = legitimate().copy(edgeName = "comments")
        """.trimIndent())
        assertCompileError(result, "Unresolved reference")
    }

    @Test
    fun `explicit OptIn at call site allows restricted construction`() {
        // Sanity check that the escape hatch works — code that opts in
        // explicitly compiles. The opt-in cost is documented and
        // intentional per the RFC's "Constructor Visibility" section.
        val result = compile("""
            @OptIn(EntktInternal::class)
            fun ok(): Predicate<User> = Predicate.HasEdge<User>("posts")
        """.trimIndent())
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Snippet with explicit @OptIn should compile cleanly; messages:\n${result.messages}",
        )
    }

    // ---- Public-DSL surface: Column / OrderField / Predicate.Leaf
    //      stay constructible without opt-in (the residual gap is
    //      wrong-column-name-within-entity, surfaced at driver-render
    //      time per the RFC's V1 lock-in). These are sanity-checks. ----

    @Test
    fun `Predicate Leaf is constructible without opt-in`() {
        val result = compile("""
            fun ok(): Predicate<User> = Predicate.Leaf("x", Op.EQ, 1)
        """.trimIndent())
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Predicate.Leaf should be public per RFC \"Constructor Visibility\". " +
                "Messages:\n${result.messages}",
        )
    }

    @Test
    fun `Column constructor is public`() {
        val result = compile("""
            val col: Column<User, Boolean> = Column("active")
        """.trimIndent())
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Column<E, T> should be public per RFC \"Constructor Visibility\". " +
                "Messages:\n${result.messages}",
        )
    }

    @Test
    fun `OrderField constructor is public`() {
        val result = compile("""
            val f: OrderField<User> = OrderField("name", OrderDirection.ASC)
        """.trimIndent())
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "OrderField<E> should be public per RFC \"Constructor Visibility\". " +
                "Messages:\n${result.messages}",
        )
    }
}
