@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compile-time proof of the edge-load handle contract (RFC "Generated
 * Edge Loading API"): every generated `load<Edge> { }` returns an
 * edge-specific `EdgeLoad<ParentScope>` handle. Ignoring the handle
 * compiles — strict privacy is the default, not something the caller
 * opts into — while `filterVisible()` returns the concrete parent scope
 * for continued configuration. The runtime behavior (strict denial
 * versus filtered visibility) is pinned by the string-assertion and
 * integration suites; this test pins the shape the application
 * compiles against, including the `@JvmOverloads` zero-block overload
 * Java callers use without Kotlin's default-argument marker.
 */
class EdgeLoadHandleCompileTest {

    private fun generatedSources(): List<SourceFile> {
        val car = Car()
        val user = User()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            car::class to car,
            user::class to user,
        )
        car.finalize(registry)
        user.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(car), SchemaInput(user)))
            .toCompileTestSources()
    }

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    @Test
    fun `loadEdge returns an EdgeLoad handle that may be ignored or chained`() {
        val result = compile(
            generatedSources() + SourceFile.kotlin(
                "EdgeLoadSnippet.kt",
                """
                package com.example.app

                import com.example.ent.CarQueryScope
                import com.example.ent.UserQuery
                import entkt.runtime.privacy.ViewerContext
                import entkt.runtime.query.EdgeLoad

                fun strictByDefault(q: CarQueryScope) {
                    // Ignoring the returned handle compiles: strict edge-load
                    // privacy needs no acknowledgement from the caller.
                    q.loadUser()
                }

                fun typedHandle(q: CarQueryScope) {
                    // The handle is the runtime EdgeLoad interface,
                    // parameterized on the concrete parent configuration scope.
                    val handle: EdgeLoad<CarQueryScope> = q.loadUser { }
                    val parent: CarQueryScope = handle.filterVisible()
                    parent.limit(1)
                }

                fun fluentToMany(q: UserQuery, viewerContext: ViewerContext) {
                    // filterVisible() on a to-many edge chains the same way.
                    val parent: UserQuery = q.configure { loadCars { limit(3) }.filterVisible() }
                    parent.firstOrNull(viewerContext)
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the EdgeLoad handle snippet to compile, got:\n${result.messages}",
        )
    }

    @Test
    fun `Java callers use the zero-block overload without a default-argument marker`() {
        val result = compile(
            generatedSources() + SourceFile.java(
                "EdgeLoadJavaSnippet.java",
                """
                package com.example.app;

                import com.example.ent.CarQueryScope;
                import com.example.ent.UserQueryScope;
                import entkt.runtime.query.EdgeLoad;

                public class EdgeLoadJavaSnippet {
                    // The @JvmOverloads zero-arg overload: no Function1 and no
                    // Kotlin default-argument marker required from Java.
                    public static CarQueryScope strict(CarQueryScope q) {
                        EdgeLoad<CarQueryScope> handle = q.loadUser();
                        return handle.filterVisible();
                    }

                    public static void ignoredHandle(UserQueryScope q) {
                        q.loadCars();
                    }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the Java zero-block overload snippet to compile, got:\n${result.messages}",
        )
    }
}
