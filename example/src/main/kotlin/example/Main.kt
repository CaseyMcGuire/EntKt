package example

import entkt.codegen.EntGenerator
import entkt.codegen.SchemaInput
import java.nio.file.Paths

/**
 * Runs the entkt code generator against the example schemas and writes
 * the result to the given output directory. The first command-line
 * argument is the output directory; if omitted, defaults to
 * `build/generated/entkt` relative to the working directory.
 *
 * This module also serves as the codegen runner for `example-demo` —
 * that module invokes this main via a JavaExec task.
 *
 * Run directly with: ./gradlew :example:run
 */
fun main(args: Array<String>) {
    val outputDir = if (args.isNotEmpty()) {
        Paths.get(args[0])
    } else {
        Paths.get("build/generated/entkt")
    }

    val schemas = listOf(
        SchemaInput("User", User),
        SchemaInput("Post", Post),
        SchemaInput("Tag", Tag),
    )

    outputDir.toFile().deleteRecursively()
    outputDir.toFile().mkdirs()

    val generator = EntGenerator("example.ent")
    generator.writeTo(outputDir, schemas)

    println("Generated ${schemas.size * 4} files for ${schemas.size} schemas:")
    outputDir.toFile().walkTopDown()
        .filter { it.extension == "kt" }
        .sorted()
        .forEach { file ->
            println("  ${file.relativeTo(outputDir.toFile())}")
        }
    println()
    println("Output directory: ${outputDir.toAbsolutePath()}")
}