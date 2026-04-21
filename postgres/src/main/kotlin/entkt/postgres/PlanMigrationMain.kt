package entkt.postgres

import entkt.codegen.buildEntitySchemas
import entkt.codegen.scanForSchemas
import java.io.File
import java.nio.file.Path

/**
 * Generic entry point for generating migration SQL files. Scans the
 * runtime classpath for EntSchema objects — no hardcoded schema list
 * needed.
 *
 * Usage from a Gradle JavaExec task:
 * ```kotlin
 * tasks.register<JavaExec>("generateMigrationFile") {
 *     classpath = yourSchemaClasspath
 *     mainClass.set("entkt.postgres.PlanMigrationMainKt")
 *     args(migrationsDir.absolutePath)
 *     args(project.findProperty("description")?.toString() ?: "migration")
 * }
 * ```
 *
 * Args: [migrationsDir] [description]
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: PlanMigrationMain <migrationsDir> [description]" }
    val migrationsDir = Path.of(args[0])
    val description = if (args.size > 1) args[1] else "migration"

    val classpath = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .map { File(it) }
    val schemas = buildEntitySchemas(scanForSchemas(classpath))
    check(schemas.isNotEmpty()) { "No EntSchema objects found on the classpath" }

    val planner = PostgresMigrator.planner()
    val plan = planner.plan(
        schemas = schemas,
        outputDir = migrationsDir,
        description = description,
    )

    if (plan.filePath != null) {
        println("Generated migration: ${plan.filePath}")
        println("  ${plan.ops.size} auto-applied operation(s)")
        if (plan.manual.isNotEmpty()) {
            println("  ${plan.manual.size} manual operation(s) — see file header")
        }
    } else {
        println("No schema changes detected.")
    }
}
