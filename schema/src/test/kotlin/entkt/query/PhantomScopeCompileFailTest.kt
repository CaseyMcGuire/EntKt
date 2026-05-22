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
