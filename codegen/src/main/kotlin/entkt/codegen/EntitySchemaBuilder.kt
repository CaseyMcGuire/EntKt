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
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

/**
 * Scan the given classpath entries (directories and JARs) for [EntSchema]
 * subclasses. Instantiates each class, collects all schema instances,
 * finalizes cross-schema references, and returns [SchemaInput] ready for
 * codegen or [buildEntitySchemas].
 */
fun scanForSchemas(classpath: Iterable<File>): List<SchemaInput> {
    val schemas = collectSchemas(classpath)
    ensureFinalized(schemas)
    return schemas
}

/**
 * Scan the given classpath entries for [EntSchema] subclasses and
 * instantiate them, but do **not** finalize or validate. The returned
 * inputs are unfinalized — callers must run [ensureFinalized] (or pass
 * them to [SchemaInspector.validate]/[SchemaInspector.explain]) before
 * accessing edges or generating code.
 */
fun collectSchemas(classpath: Iterable<File>): List<SchemaInput> {
    val classLoader = URLClassLoader(
        classpath.map { it.toURI().toURL() }.toTypedArray(),
        EntSchema::class.java.classLoader,
    )
    val schemas = mutableListOf<SchemaInput>()
    val loadFailures = mutableListOf<String>()
    val schemaFailures = mutableListOf<String>()
    for (file in classpath) {
        when {
            file.isDirectory -> scanDirectory(file, file, classLoader, schemas, loadFailures, schemaFailures)
            file.isFile && file.extension == "jar" -> scanJar(file, classLoader, schemas, loadFailures, schemaFailures)
        }
    }
    // Concrete EntSchema subclasses that couldn't be instantiated are always
    // fatal — silently dropping them would remove entities from codegen output.
    if (schemaFailures.isNotEmpty()) {
        val detail = schemaFailures.joinToString("\n  - ", prefix = "\n  - ")
        error("Found EntSchema classes that could not be loaded:$detail")
    }
    // Generic class-loading failures are only reported when no schemas were
    // found at all — they may include the user's schemas behind a missing dep.
    if (schemas.isEmpty() && loadFailures.isNotEmpty()) {
        val detail = loadFailures.joinToString("\n  - ", prefix = "\n  - ")
        error(
            "No EntSchema classes found. The following classes failed to load " +
                "(missing dependency?) and may include your schemas:$detail",
        )
    }
    // Sort by schema name so output is deterministic regardless of
    // classpath scan order (directory walk, JAR entry order).
    return schemas.sortedBy { it.name }
}

private fun scanDirectory(
    root: File,
    dir: File,
    classLoader: ClassLoader,
    out: MutableList<SchemaInput>,
    loadFailures: MutableList<String>,
    schemaFailures: MutableList<String>,
) {
    dir.listFiles()?.forEach { file ->
        if (file.isDirectory) {
            scanDirectory(root, file, classLoader, out, loadFailures, schemaFailures)
        } else if (file.extension == "class") {
            val className = file.relativeTo(root).path
                .removeSuffix(".class")
                .replace(File.separatorChar, '.')
            tryLoadSchema(classLoader, className, loadFailures, schemaFailures)?.let { out.add(it) }
        }
    }
}

private fun scanJar(
    jar: File,
    classLoader: ClassLoader,
    out: MutableList<SchemaInput>,
    loadFailures: MutableList<String>,
    schemaFailures: MutableList<String>,
) {
    JarFile(jar).use { jf ->
        for (entry in jf.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name
            if (!name.endsWith(".class")) continue
            if ("META-INF/" in name) continue
            val className = name.removeSuffix(".class").replace('/', '.')
            tryLoadSchema(classLoader, className, loadFailures, schemaFailures)?.let { out.add(it) }
        }
    }
}

private fun tryLoadSchema(
    classLoader: ClassLoader,
    className: String,
    loadFailures: MutableList<String>,
    schemaFailures: MutableList<String>,
): SchemaInput? {
    val clazz = try {
        classLoader.loadClass(className)
    } catch (_: ClassNotFoundException) {
        return null
    } catch (e: LinkageError) {
        loadFailures.add("$className: ${e.message}")
        return null
    }
    if (!EntSchema::class.java.isAssignableFrom(clazz)) return null
    // Skip abstract base classes — they can't be instantiated and are intentionally not schemas
    if (java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) return null
    // Skip anonymous, local, synthetic, and non-static inner classes —
    // only named top-level or static-nested classes are schemas
    if (clazz.isAnonymousClass || clazz.isLocalClass || clazz.isSynthetic) return null
    if (clazz.isMemberClass && !java.lang.reflect.Modifier.isStatic(clazz.modifiers)) return null
    if (clazz.simpleName.isNullOrEmpty()) return null
    // Concrete EntSchema subclass failures are always fatal — silently
    // dropping a schema would remove entities from codegen output.
    if (clazz.kotlin.objectInstance != null) {
        schemaFailures.add("${clazz.simpleName}: object singleton (EntSchema must be a class, not an object)")
        return null
    }
    val instance = try {
        clazz.kotlin.createInstance() as? EntSchema ?: return null
    } catch (e: IllegalArgumentException) {
        // createInstance() throws IllegalArgumentException when there is no
        // callable no-arg or all-defaults constructor.
        schemaFailures.add("${clazz.simpleName}: no no-arg or all-defaults constructor")
        return null
    } catch (e: Exception) {
        schemaFailures.add("${clazz.simpleName}: construction failed: ${e.cause?.message ?: e.message}")
        return null
    }
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
    ensureFinalized(inputs)
    val schemaNames = inputs.associate { it.schema to it.name }
    return inputs.map { input ->
        buildEntitySchema(input.name, input.schema, schemaNames)
    }
}

private fun buildEntitySchema(
    name: String,
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): EntitySchema {
    val table = schema.tableName
    val columns = columnMetadataFor(schema, schemaNames)
    val schemaIndexes = schema.indexes()
    val idxColMap = indexableColumnMap(schema, schemaNames)

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
                comment = col.comment,
            )
        },
        edges = buildEdgeMap(schema, schemaNames),
        indexes = schemaIndexes.map { idx ->
            IndexMetadata(
                columns = idx.fields.map { idxColMap[it] ?: error("Index references field '$it' but no field with that name exists on the schema") },
                unique = idx.unique,
                name = idx.name,
                where = idx.where,
            )
        },
    )
}

private fun buildEdgeMap(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): Map<String, EdgeMetadata> {
    val forwardEntries = schema.edges().mapNotNull { edge ->
        val join = if (edge.kind is entkt.schema.EdgeKind.ManyToMany) {
            resolveM2MEdgeJoin(edge, schema, schemaNames)
        } else {
            resolveEdgeJoin(edge, schema)
        } ?: return@mapNotNull null
        edge.name to EdgeMetadata(
            targetTable = edge.target.tableName,
            sourceColumn = join.sourceColumn,
            targetColumn = join.targetColumn,
            junctionTable = join.junctionTable,
            junctionSourceColumn = join.junctionSourceColumn,
            junctionTargetColumn = join.junctionTargetColumn,
            comment = edge.comment,
        )
    }

    val reverseEntries = reverseM2MEdgeEntries(schema, schemaNames).map { entry ->
        entry.name to EdgeMetadata(
            targetTable = entry.targetTable,
            sourceColumn = entry.join.sourceColumn,
            targetColumn = entry.join.targetColumn,
            junctionTable = entry.join.junctionTable,
            junctionSourceColumn = entry.join.junctionSourceColumn,
            junctionTargetColumn = entry.join.junctionTargetColumn,
        )
    }

    val allEntries = forwardEntries + reverseEntries
    val map = mutableMapOf<String, EdgeMetadata>()
    for ((name, meta) in allEntries) {
        val existing = map.put(name, meta)
        if (existing != null) {
            error("Duplicate edge name '$name' — edge names must be unique per entity (including reverse M2M edges)")
        }
    }
    return map
}
