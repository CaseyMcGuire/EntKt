package entkt.codegen

import java.io.File
import java.nio.file.Path

/**
 * Generic entry point for running entkt codegen. Scans the runtime
 * classpath for EntSchema objects — no hardcoded schema list needed.
 *
 * Usage from a Gradle JavaExec task:
 * ```kotlin
 * tasks.register<JavaExec>("generateEntkt") {
 *     classpath = yourSchemaClasspath
 *     mainClass.set("entkt.codegen.GenerateMainKt")
 *     args(packageName, outputDir)
 * }
 * ```
 *
 * Args: [packageName] [outputDir]
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: GenerateMain <packageName> <outputDir>" }
    val packageName = args[0]
    val outputDir = Path.of(args[1])

    val classpath = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .map { File(it) }
    val schemas = scanForSchemas(classpath)
    check(schemas.isNotEmpty()) { "No EntSchema objects found on the classpath" }

    outputDir.toFile().deleteRecursively()
    outputDir.toFile().mkdirs()

    val generator = EntGenerator(packageName)
    val files = generator.generate(schemas)
    files.forEach { it.writeTo(outputDir) }

    println("Generated ${files.size} files for ${schemas.size} schemas")
    println("Output directory: ${outputDir.toAbsolutePath()}")
}
