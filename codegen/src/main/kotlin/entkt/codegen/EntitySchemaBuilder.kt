package entkt.codegen

import entkt.runtime.ColumnMetadata
import entkt.runtime.EdgeMetadata
import entkt.runtime.EntitySchema
import entkt.runtime.ForeignKeyRef
import entkt.runtime.IdStrategy
import entkt.runtime.IndexMetadata
import entkt.schema.EntSchema
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Scan the given classpath entries (directories and JARs) for [EntSchema]
 * object instances. Returns them as [SchemaInput] ready for codegen or
 * [buildEntitySchemas].
 */
fun scanForSchemas(classpath: Iterable<File>): List<SchemaInput> {
    val classLoader = URLClassLoader(
        classpath.map { it.toURI().toURL() }.toTypedArray(),
        EntSchema::class.java.classLoader,
    )
    val schemas = mutableListOf<SchemaInput>()
    val failures = mutableListOf<String>()
    for (file in classpath) {
        when {
            file.isDirectory -> scanDirectory(file, file, classLoader, schemas, failures)
            file.isFile && file.extension == "jar" -> scanJar(file, classLoader, schemas, failures)
        }
    }
    if (schemas.isEmpty() && failures.isNotEmpty()) {
        val detail = failures.joinToString("\n  - ", prefix = "\n  - ")
        error(
            "No EntSchema objects found. The following classes failed to load " +
                "(missing dependency?) and may include your schemas:$detail",
        )
    }
    return schemas
}

private fun scanDirectory(
    root: File,
    dir: File,
    classLoader: ClassLoader,
    out: MutableList<SchemaInput>,
    failures: MutableList<String>,
) {
    dir.listFiles()?.forEach { file ->
        if (file.isDirectory) {
            scanDirectory(root, file, classLoader, out, failures)
        } else if (file.extension == "class") {
            val className = file.relativeTo(root).path
                .removeSuffix(".class")
                .replace(File.separatorChar, '.')
            tryLoadSchema(classLoader, className, failures)?.let { out.add(it) }
        }
    }
}

private fun scanJar(
    jar: File,
    classLoader: ClassLoader,
    out: MutableList<SchemaInput>,
    failures: MutableList<String>,
) {
    JarFile(jar).use { jf ->
        for (entry in jf.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name
            if (!name.endsWith(".class")) continue
            if ("META-INF/" in name) continue
            val className = name.removeSuffix(".class").replace('/', '.')
            tryLoadSchema(classLoader, className, failures)?.let { out.add(it) }
        }
    }
}

private fun tryLoadSchema(
    classLoader: ClassLoader,
    className: String,
    failures: MutableList<String>,
): SchemaInput? {
    val clazz = try {
        classLoader.loadClass(className)
    } catch (_: ClassNotFoundException) {
        return null
    } catch (e: LinkageError) {
        failures.add("$className: ${e.message}")
        return null
    }
    if (!EntSchema::class.java.isAssignableFrom(clazz)) return null
    val instance = clazz.kotlin.objectInstance as? EntSchema ?: return null
    return SchemaInput(clazz.simpleName, instance)
}

/**
 * Builds [EntitySchema] objects directly from [SchemaInput] definitions,
 * using the same logic as the codegen emitter but producing runtime
 * objects instead of KotlinPoet code.
 *
 * This is used by the Gradle plugin's `generateMigrationFile` task so it can
 * compute schema diffs without needing the compiled generated code.
 */
fun buildEntitySchemas(inputs: List<SchemaInput>): List<EntitySchema> {
    val schemaNames = inputs.associate { it.schema to it.name }
    return inputs.map { input ->
        buildEntitySchema(input.name, input.schema, schemaNames)
    }
}

private fun buildEntitySchema(
    name: String,
    schema: entkt.schema.EntSchema,
    schemaNames: Map<entkt.schema.EntSchema, String>,
): EntitySchema {
    val table = tableNameFor(name)
    val columns = columnMetadataFor(schema, schemaNames)
    val schemaIndexes = schema.indexes() + schema.mixins().flatMap { it.indexes() }
    val colMap = fieldColumnMap(schema)

    return EntitySchema(
        table = table,
        idColumn = "id",
        idStrategy = IdStrategy.valueOf(idStrategyName(schema)),
        columns = columns.map { col ->
            ColumnMetadata(
                name = col.name,
                type = col.type,
                nullable = col.nullable,
                primaryKey = col.primaryKey,
                unique = col.unique,
                references = col.references?.let { (t, c) -> ForeignKeyRef(t, c, col.onDelete) },
            )
        },
        edges = buildEdgeMap(schema, schemaNames),
        indexes = schemaIndexes.map { idx ->
            IndexMetadata(
                columns = idx.fields.map { colMap[it] ?: it },
                unique = idx.unique,
                storageKey = idx.storageKey,
                where = idx.where,
            )
        },
    )
}

private fun buildEdgeMap(
    schema: entkt.schema.EntSchema,
    schemaNames: Map<entkt.schema.EntSchema, String>,
): Map<String, EdgeMetadata> {
    val forwardEntries = schema.edges().mapNotNull { edge ->
        val targetName = schemaNames[edge.target] ?: return@mapNotNull null
        val join = if (edge.through != null) {
            resolveM2MEdgeJoin(edge, schema, schemaNames)
        } else {
            resolveEdgeJoin(edge, schema)
        } ?: return@mapNotNull null
        edge.name to EdgeMetadata(
            targetTable = tableNameFor(targetName),
            sourceColumn = join.sourceColumn,
            targetColumn = join.targetColumn,
            junctionTable = join.junctionTable,
            junctionSourceColumn = join.junctionSourceColumn,
            junctionTargetColumn = join.junctionTargetColumn,
        )
    }

    val reverseEntries = reverseM2MEdgeEntries(schema, schemaNames).map { (edgeName, targetTable, join) ->
        edgeName to EdgeMetadata(
            targetTable = targetTable,
            sourceColumn = join.sourceColumn,
            targetColumn = join.targetColumn,
            junctionTable = join.junctionTable,
            junctionSourceColumn = join.junctionSourceColumn,
            junctionTargetColumn = join.junctionTargetColumn,
        )
    }

    return (forwardEntries + reverseEntries).toMap()
}
