@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.OutputStream

/** The `viewerType` option these tests generate code with. */
internal const val TEST_VIEWER_TYPE = "com.example.auth.AppViewer"

/** [TEST_VIEWER_TYPE] as the class name the per-file generators take. */
internal val TEST_VIEWER = ClassName("com.example.auth", "AppViewer")

/** The application's declaration of [TEST_VIEWER_TYPE]. Generated sources do not compile without it. */
internal val testViewerSource: SourceFile = SourceFile.kotlin(
    "AppViewer.kt",
    """
    package com.example.auth

    import java.util.UUID

    /** `userId` is null for an unauthenticated viewer. */
    data class AppViewer(val userId: UUID?)
    """.trimIndent(),
)

/** Compiles [sources] together with [testViewerSource], the viewer type generated code is bound to. */
internal fun compileSources(sources: List<SourceFile>): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources + testViewerSource
        // Generated code references the runtime and test-defined types.
        inheritClassPath = true
        // kctfork's compiler can lag behind the compiler used to build that classpath.
        kotlincArguments = listOf("-Xskip-metadata-version-check")
        jvmTarget = "17"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile()
