@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compile-time proof of the eager-load handle contract (RFC
 * "Eager-Edge Privacy"): every generated `with<Edge> { }` returns an
 * edge-specific `EagerLoad<ParentQuery>` handle. Ignoring the handle
 * compiles — strict privacy is the default, not something the caller
 * opts into — while `filterVisible()` returns the concrete parent query
 * for continued fluent composition. The runtime behavior (strict denial
 * versus filtered visibility) is pinned by the string-assertion and
 * integration suites; this test pins the shape the application
 * compiles against.
 */
class EagerLoadHandleCompileTest {

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
            .generate(listOf(SchemaInput("Car", car), SchemaInput("User", user)))
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
    fun `withEdge returns an EagerLoad handle that may be ignored or chained`() {
        val result = compile(
            generatedSources() + SourceFile.kotlin(
                "EagerSnippet.kt",
                """
                package com.example.app

                import com.example.ent.CarQuery
                import com.example.ent.UserQuery
                import entkt.runtime.query.EagerLoad

                fun strictByDefault(q: CarQuery) {
                    // Ignoring the returned handle compiles: strict eager
                    // privacy needs no acknowledgement from the caller.
                    q.withUser()
                }

                fun typedHandle(q: CarQuery) {
                    // The handle is the runtime EagerLoad interface,
                    // parameterized on the concrete parent query type.
                    val handle: EagerLoad<CarQuery> = q.withUser { }
                    val parent: CarQuery = handle.filterVisible()
                    parent.limit(1)
                }

                fun fluentToMany(q: UserQuery) {
                    // filterVisible() on a to-many edge chains the same way.
                    val parent: UserQuery = q.withCars { limit(3) }.filterVisible()
                    parent.firstOrNull()
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected the EagerLoad handle snippet to compile, got:\n${result.messages}",
        )
    }
}
