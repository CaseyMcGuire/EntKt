package entkt.postgres

import entkt.codegen.SchemaInspector
import entkt.codegen.buildEntitySchemas
import entkt.codegen.collectSchemas
import java.io.File
import java.nio.file.Path

/**
 * Generic entry point for generating migration SQL files. Scans the
 * runtime classpath for EntSchema objects — no hardcoded schema list
 * needed.
 *
 * Uses [SchemaInspector.validate] as the shared validation boundary
 * so that schema errors produce structured diagnostics rather than
 * raw stacktraces.
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

    val entitySchemas = buildEntitySchemas(schemas)

    val planner = PostgresMigrator.planner()
    val plan = planner.plan(
        schemas = entitySchemas,
        outputDir = migrationsDir,
        description = description,
    )

    if (plan.filePath != null) {
        println("Generated migration: ${plan.filePath}")
        println("  ${plan.ops.size} auto-generated operation(s)")
        if (plan.manual.isNotEmpty()) {
            println("  ${plan.manual.size} manual operation(s) — see file header")
        }
    } else {
        println("No schema changes detected.")
    }
}
