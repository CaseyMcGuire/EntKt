@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.schema

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Compile-fail tests for delegate binding.
 *
 * The RFC leans on the compiler for two of its rejections rather than on
 * a runtime check, so they need compile-level coverage: a `var`
 * declaration must not compile (builders deliberately have no
 * `setValue`), and forward-referencing a delegated property must not
 * compile. Both are guarantees a runtime test cannot express.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
class DelegateBindingCompileFailTest {

    private fun compile(snippet: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "Snippet.kt",
                    """
                    @file:Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
                    import entkt.schema.*

                    $snippet
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            // `:schema` targets JVM 17 and the edge builders are reached
            // through `inline reified` helpers, so the snippet has to be
            // compiled at the same target — kctfork otherwise defaults to
            // 1.8 and refuses to inline the newer bytecode, which would
            // make every negative test here pass for the wrong reason.
            jvmTarget = "17"
            kotlincArguments = listOf("-Xskip-metadata-version-check")
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

    @Test
    fun `a delegated var field does not compile`() {
        // Builders intentionally implement `provideDelegate`/`getValue`
        // but not `setValue`, so a mutable declaration is rejected by
        // the compiler rather than needing a runtime guard.
        val result = compile(
            """
            class Post : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                var title by string("title")
            }
            """.trimIndent(),
        )
        assertCompileError(result, "setValue")
    }

    @Test
    fun `a delegated var edge does not compile`() {
        val result = compile(
            """
            class Tag : EntSchema("tags", clientName = "tags") {
                override fun id() = EntId.long()
            }
            class Post : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                var tags by hasMany<Tag>("post_tags")
            }
            """.trimIndent(),
        )
        assertCompileError(result, "setValue")
    }

    @Test
    fun `forward-referencing a delegated property does not compile`() {
        // Binding runs in declaration order, so a builder that referred
        // to a later declaration would read an uninitialized delegate.
        // The compiler catches it, same as it does for `=` declarations.
        val result = compile(
            """
            class Tag : EntSchema("tags", clientName = "tags") {
                override fun id() = EntId.long()
            }
            class Post : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                val tag by belongsTo<Tag>("tag").field(tagId)
                val tagId by long("tag_id")
            }
            """.trimIndent(),
        )
        assertCompileError(result, "must be initialized")
    }

    @Test
    fun `a delegated val field compiles and keeps its concrete type`() {
        // Positive control: the same harness must accept the canonical
        // form, otherwise the negative tests above could pass for the
        // wrong reason (e.g. a broken classpath).
        val result = compile(
            """
            class Tag : EntSchema("tags", clientName = "tags") {
                override fun id() = EntId.long()
            }
            class Post : EntSchema("posts", clientName = "posts") {
                override fun id() = EntId.long()
                val title: StringFieldBuilder by string("title").maxLength(64)
                val tagId: LongFieldBuilder by long("tag_id")
                val tag: BelongsToBuilder<Tag> by belongsTo<Tag>("tag").field(tagId)
            }
            """.trimIndent(),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Canonical delegated declarations must compile:\n${result.messages}",
        )
    }
}
