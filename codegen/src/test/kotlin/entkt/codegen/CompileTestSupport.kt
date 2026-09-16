@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.OutputStream

internal fun compileSources(sources: List<SourceFile>): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources
        // Generated code references the runtime and test-defined types.
        inheritClassPath = true
        // kctfork's compiler can lag behind the compiler used to build that classpath.
        kotlincArguments = listOf("-Xskip-metadata-version-check")
        jvmTarget = "17"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile()
