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
 *     args(packageName, outputDir, viewerType)   // optionally followed by jsonMapper
 * }
 * ```
 *
 * Args: [packageName] [outputDir] [viewerType] [jsonMapper] [generateEntExplorer]
 *
 * [viewerType] (required) is the fully qualified name of the application's
 * viewer type, a public, top-level, non-generic class or interface. Generated
 * terminals take `ViewerContext<ThatType>`. Only the name is used here: the
 * type is compiled with the generated sources, so a wrong name surfaces as an
 * unresolved reference when they compile. A type named after a Kotlin built-in
 * such as `String` or `Any` is rejected.
 *
 * [jsonMapper] (optional, default `kotlinx`) is the JSON mapper id stamped
 * into generated JSON-column metadata — `kotlinx`, `jackson`, or a
 * third-party codec id. Unknown ids are accepted here by design (codegen is
 * open to third-party codecs); a typo'd id is caught by the driver's
 * register() cross-check at startup.
 */
fun main(args: Array<String>) {
    require(args.size >= 3) {
        "Usage: GenerateMain <packageName> <outputDir> <viewerType> [jsonMapper] [generateEntExplorer]\n" +
            "viewerType is required: the fully qualified name of the application's viewer type, " +
            "such as com.example.auth.AppViewer"
    }
    val packageName = args[0]
    val outputDir = Path.of(args[1])
    val viewerType = args[2]
    val jsonMapper = args.getOrNull(3) ?: entkt.runtime.driver.JsonMapperIds.KOTLINX
    // arg 5 (optional, default false): generate the Ent Explorer adapters and registry.
    val generateEntExplorer = args.getOrNull(4)?.toBooleanStrictOrNull() ?: false

    val generator = try {
        EntGenerator(packageName, viewerType, jsonMapper, generateEntExplorer)
    } catch (e: IllegalArgumentException) {
        System.err.println("Invalid code generation options:")
        System.err.println("  - ${e.message ?: e}")
        System.exit(1)
        return
    }

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

    val files = generator.generate(schemas)
    files.forEach { it.writeTo(outputDir) }

    println("Generated ${files.size} files for ${schemas.size} schemas")
    println("Output directory: ${outputDir.toAbsolutePath()}")
}
