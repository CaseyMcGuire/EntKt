package entkt.flyway

import entkt.codegen.SchemaInspector
import entkt.codegen.buildEntitySchemas
import entkt.codegen.collectSchemas
import entkt.migrations.ManualMigrationRequiredException
import entkt.migrations.ManualMode
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * CLI entry point for the Flyway shadow migration workflow.
 *
 * Scans the runtime classpath for EntSchema classes, validates them,
 * then dispatches to [FlywayMigrationWorkflow] for the requested
 * command. A fresh Docker-backed Postgres container is started for
 * each invocation and destroyed when it completes.
 *
 * Usage from a Gradle JavaExec task:
 * ```kotlin
 * tasks.register<JavaExec>("generateFlywayMigration") {
 *     classpath = codegenRunner
 *     mainClass.set("entkt.flyway.FlywayMainKt")
 *     args("generate", migrationsDir.absolutePath)
 *     args("--description=add_posts")
 * }
 * ```
 *
 * Args: `<command> <migrationsDir> [options]`
 *
 * Commands: `validate`, `generate`, `verify`
 *
 * Options:
 * - `--image=<image>` or env `ENTKT_FLYWAY_POSTGRES_IMAGE` (default: pgvector/pgvector:pg16,
 *   a drop-in Postgres superset so vector schemas' `CREATE EXTENSION "vector"` works)
 * - `--db=<name>` or env `ENTKT_FLYWAY_POSTGRES_DB` (default: entkt_shadow)
 * - `--user=<user>` or env `ENTKT_FLYWAY_POSTGRES_USER` (default: postgres)
 * - `--password=<pass>` or env `ENTKT_FLYWAY_POSTGRES_PASSWORD` (default: postgres)
 * - `--exclude-tables=<t1,t2,...>` exclude these tables from management
 * - `--description=<desc>` (default: "migration")
 * - `--manual-mode=<FAIL|ACKNOWLEDGE_AND_ADVANCE>` (default: FAIL)
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: FlywayMain <validate|generate|verify> <migrationsDir> [options]")
        exitProcess(1)
    }

    val command = args[0]
    val migrationsDir = Path.of(args[1])
    val options = parseOptions(args.drop(2))

    val config = ShadowDockerConfig(
        image = options["image"]
            ?: System.getenv("ENTKT_FLYWAY_POSTGRES_IMAGE")
            ?: ShadowDockerConfig().image,
        databaseName = options["db"]
            ?: System.getenv("ENTKT_FLYWAY_POSTGRES_DB")
            ?: "entkt_shadow",
        user = options["user"]
            ?: System.getenv("ENTKT_FLYWAY_POSTGRES_USER")
            ?: "postgres",
        password = options["password"]
            ?: System.getenv("ENTKT_FLYWAY_POSTGRES_PASSWORD")
            ?: "postgres",
    )

    // Scan and validate schemas
    val classpath = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .map { File(it) }
    val schemaInputs = try {
        collectSchemas(classpath)
    } catch (e: Exception) {
        System.err.println("Schema collection failed:")
        System.err.println("  - ${e.message ?: e}")
        exitProcess(1)
    }
    if (schemaInputs.isEmpty()) {
        System.err.println("No EntSchema classes found on the classpath")
        exitProcess(1)
    }

    val validation = SchemaInspector.validate(schemaInputs)
    if (!validation.valid) {
        System.err.println("Schema validation failed:")
        for (error in validation.errors) {
            System.err.println("  - $error")
        }
        exitProcess(1)
    }

    val entitySchemas = buildEntitySchemas(schemaInputs)
    val excludeTables = options["exclude-tables"]
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()
    val workflow = FlywayMigrationWorkflow(config, excludeTables)
    val description = options["description"] ?: "migration"
    val manualMode = when (options["manual-mode"]?.uppercase()) {
        "ACKNOWLEDGE_AND_ADVANCE" -> ManualMode.ACKNOWLEDGE_AND_ADVANCE
        else -> ManualMode.FAIL
    }

    try {
        when (command) {
            "validate" -> {
                val result = workflow.validate(entitySchemas, migrationsDir)
                if (result.hasDrift) {
                    println("Schema drift detected:")
                    for (op in result.ops) println("  - ${entkt.migrations.describeOp(op)}")
                    for (op in result.manual) println("  - [manual] ${entkt.migrations.describeOp(op)}")
                    exitProcess(1)
                } else {
                    println("No schema drift detected.")
                }
            }
            "generate" -> {
                when (val result = workflow.generate(entitySchemas, migrationsDir, description, manualMode)) {
                    is GenerateResult.NoChanges -> println("No schema changes detected.")
                    is GenerateResult.Written -> {
                        println("Generated migration: ${result.filePath}")
                        println("  ${result.ops.size} auto-generated operation(s)")
                        if (result.manual.isNotEmpty()) {
                            println("  ${result.manual.size} manual operation(s) — see file header")
                        }
                    }
                }
            }
            "verify" -> {
                when (val result = workflow.generate(entitySchemas, migrationsDir, description, manualMode, verify = true)) {
                    is GenerateResult.NoChanges -> println("No schema changes detected.")
                    is GenerateResult.Written -> {
                        println("  ${result.ops.size} auto-generated operation(s)")
                        if (result.manual.isNotEmpty()) {
                            println("  ${result.manual.size} manual operation(s) — see file header")
                        }
                        when (result.verification) {
                            VerificationOutcome.Verified ->
                                println("Generated and verified migration: ${result.filePath}")
                            VerificationOutcome.SkippedManualOps -> {
                                System.err.println("Generated migration: ${result.filePath}")
                                System.err.println("  Verification failed: manual ops require user-authored SQL before the migration can be verified")
                                exitProcess(1)
                            }
                            VerificationOutcome.NotRequested ->
                                println("Generated migration: ${result.filePath}")
                        }
                    }
                }
            }
            else -> {
                System.err.println("Unknown command: $command")
                System.err.println("Valid commands: validate, generate, verify")
                exitProcess(1)
            }
        }
    } catch (e: ManualMigrationRequiredException) {
        System.err.println(e.message)
        exitProcess(1)
    } catch (e: VerificationFailedException) {
        System.err.println(e.message)
        exitProcess(1)
    } catch (e: IllegalArgumentException) {
        System.err.println("Configuration error: ${e.message}")
        exitProcess(1)
    }
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    for (arg in args) {
        if (arg.startsWith("--")) {
            val stripped = arg.removePrefix("--")
            val eqIndex = stripped.indexOf('=')
            if (eqIndex >= 0) {
                options[stripped.substring(0, eqIndex)] = stripped.substring(eqIndex + 1)
            } else {
                options[stripped] = ""
            }
        }
    }
    return options
}
