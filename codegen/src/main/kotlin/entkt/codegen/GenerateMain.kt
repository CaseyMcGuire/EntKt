package entkt.codegen

import java.io.File
import java.nio.file.Path

/**
 * Generic entry point for running entkt codegen. Scans the runtime
 * classpath for EntSchema classes — no hardcoded schema list needed.
 *
 * Uses [SchemaInspector.validate] as the shared validation boundary
 * so that schema errors produce structured diagnostics rather than
 * raw stacktraces.
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
    val schemas = try {
        collectSchemas(classpath)
    } catch (e: Exception) {
        System.err.println("Schema collection failed:")
        System.err.println("  - ${e.message ?: e}")
        System.exit(1)
        return
    }
    if (schemas.isEmpty()) {
        System.err.println("No EntSchema classes found on the classpath")
        System.exit(1)
        return
    }

    val validation = SchemaInspector.validate(schemas)
    if (!validation.valid) {
        System.err.println("Schema validation failed:")
        for (error in validation.errors) {
            System.err.println("  - $error")
        }
        System.exit(1)
        return
    }

    outputDir.toFile().deleteRecursively()
    outputDir.toFile().mkdirs()

    val generator = EntGenerator(packageName)
    val files = generator.generate(schemas)
    files.forEach { it.writeTo(outputDir) }

    println("Generated ${files.size} files for ${schemas.size} schemas")
    println("Output directory: ${outputDir.toAbsolutePath()}")
}
