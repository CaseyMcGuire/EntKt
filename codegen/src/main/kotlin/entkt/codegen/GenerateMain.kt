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
 *     args(packageName, outputDir)          // optionally: args(packageName, outputDir, jsonMapper)
 * }
 * ```
 *
 * Args: [packageName] [outputDir] [jsonMapper] [viewer]
 *
 * [jsonMapper] (optional, default `kotlinx`) is the JSON mapper id stamped
 * into generated JSON-column metadata — `kotlinx`, `jackson`, or a
 * third-party codec id. Unknown ids are accepted here by design (codegen is
 * open to third-party codecs); a typo'd id is caught by the driver's
 * register() cross-check at startup.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: GenerateMain <packageName> <outputDir> [jsonMapper] [viewer]" }
    val packageName = args[0]
    val outputDir = Path.of(args[1])
    val jsonMapper = args.getOrNull(2) ?: entkt.runtime.driver.JsonMapperIds.KOTLINX
    // arg 4 (optional, default false): emit the opt-in ent-viewer bridge.
    val viewer = args.getOrNull(3)?.toBooleanStrictOrNull() ?: false

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

    val generator = EntGenerator(packageName, jsonMapper, viewer)
    val files = generator.generate(schemas)
    files.forEach { it.writeTo(outputDir) }

    println("Generated ${files.size} files for ${schemas.size} schemas")
    println("Output directory: ${outputDir.toAbsolutePath()}")
}
