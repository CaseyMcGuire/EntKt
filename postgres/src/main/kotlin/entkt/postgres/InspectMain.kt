package entkt.postgres

import entkt.codegen.ExplainedSchemaGraph
import entkt.codegen.SchemaInput
import entkt.codegen.SchemaInspector
import entkt.codegen.buildEntitySchemas
import entkt.codegen.collectSchemas
import entkt.migrations.MigrationOp
import entkt.migrations.NormalizedSchema
import java.io.File

/**
 * Entry point for `validateEntSchemas` and `explainEntSchemas`. Scans
 * the runtime classpath for EntSchema classes without finalizing, then
 * delegates to [SchemaInspector] which handles finalization and
 * validation inside its own error handling.
 *
 * Args:
 * - `validate` — run validation, print diagnostics
 * - `explain [--format=text|json|sql]` — print explained output
 *
 * Exit code 0 = success, 1 = validation errors or no schemas found.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "validate"
    val format = args.firstOrNull { it.startsWith("--format=") }
        ?.removePrefix("--format=")
        ?: "text"
    val filter = args.firstOrNull { it.startsWith("--filter=") }
        ?.removePrefix("--filter=")

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

    when (mode) {
        "validate" -> {
            val result = SchemaInspector.validate(schemas)
            if (result.valid) {
                println("Schema validation passed (${schemas.size} schemas)")
            } else {
                System.err.println("Schema validation failed:")
                for (error in result.errors) {
                    System.err.println("  - $error")
                }
                System.exit(1)
            }
        }
        "explain" -> {
            val validation = SchemaInspector.validate(schemas)
            if (!validation.valid) {
                System.err.println("Schema validation failed:")
                for (error in validation.errors) {
                    System.err.println("  - $error")
                }
                System.exit(1)
                return
            }
            val graph = SchemaInspector.filter(SchemaInspector.explain(schemas), filter)
            when (format) {
                "text" -> print(SchemaInspector.renderText(graph))
                "json" -> println(SchemaInspector.renderJson(graph))
                "sql" -> renderSql(graph, schemas, filtered = !filter.isNullOrBlank())
                else -> {
                    System.err.println("Unknown format: $format (expected 'text', 'json', or 'sql')")
                    System.exit(1)
                }
            }
        }
        else -> {
            System.err.println("Unknown mode: $mode (expected 'validate' or 'explain')")
            System.exit(1)
        }
    }
}

/**
 * Render the explained graph as DDL. Operations are ordered so that all
 * CREATE TABLEs precede indexes, which precede FK constraints — ensuring
 * parent tables exist before children reference them.
 *
 * When [filtered] is true, the output is a partial excerpt that may
 * reference tables not included in the output. A warning header is
 * printed so the user knows the DDL is not directly runnable.
 */
private fun renderSql(graph: ExplainedSchemaGraph, schemas: List<SchemaInput>, filtered: Boolean) {
    if (filtered) {
        println("-- NOTE: --filter is active; output may reference tables not shown here.")
        println("-- This is a partial DDL excerpt, not a runnable migration.")
        println()
    }
    val matchingTables = graph.schemas.map { it.tableName }.toSet()
    val entitySchemas = buildEntitySchemas(schemas)
    val typeMapper = PostgresTypeMapper()
    val renderer = PostgresSqlRenderer(typeMapper)
    val normalized = NormalizedSchema.fromEntitySchemas(entitySchemas, typeMapper)

    val tables = entitySchemas.mapNotNull { es ->
        if (es.table !in matchingTables) return@mapNotNull null
        val table = normalized.tables[es.table] ?: return@mapNotNull null
        val name = graph.schemas.firstOrNull { it.tableName == es.table }?.schemaName ?: es.table
        name to table
    }

    for ((name, table) in tables) {
        println("-- Schema: $name")
        println()
        for (stmt in renderer.render(MigrationOp.CreateTable(table))) {
            println("$stmt;")
        }
        println()
    }
    for ((_, table) in tables) {
        for (idx in table.indexes) {
            for (stmt in renderer.render(MigrationOp.AddIndex(table.name, idx))) {
                println("$stmt;")
            }
        }
    }
    for ((_, table) in tables) {
        for (fk in table.foreignKeys) {
            for (stmt in renderer.render(MigrationOp.AddForeignKey(table.name, fk))) {
                println("$stmt;")
            }
        }
    }
}
